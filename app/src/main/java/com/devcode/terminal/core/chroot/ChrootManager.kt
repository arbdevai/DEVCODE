package com.devcode.terminal.core.chroot

import com.devcode.terminal.core.root.RootManager
import com.devcode.terminal.core.root.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Session metadata descriptor.
 */
data class SessionInfo(
    val id: String,
    val cmd: String,
    val pid: Long,
    val startedAt: Long
)

/**
 * Internal tracking entry for an active session.
 */
internal data class ActiveSession(
    val info: SessionInfo,
    val process: Process
)

/**
 * Ubuntu Chroot Engine Manager.
 *
 * Safety invariants:
 * - [lifecycleMutex] serialises all mount/unmount and destructive operations.
 * - Destructive operations (e.g. wipe / unmount-all for cleanup) are rejected
 *   while sessions are still running in the process registry.
 * - When stopping a session, child processes within the target process group / PID
 *   are killed to prevent orphaned background tasks inside the chroot.
 * - Virtual filesystem mounts check `/proc/mounts` first; `/dev/pts` is mounted
 *   without clobbering the host Android global devpts instance.
 */
object ChrootManager {

    const val UBUNTU_ROOT = "/data/local/devcode/ubuntu"
    const val WORKSPACE = "/home/coder/projects"
    private const val SESSION_RUN_DIR = "/run/devcode/sessions"

    private const val DEFAULT_ENV =
        "export HOME=/home/coder USER=coder TERM=xterm-256color PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** Serialises mount, unmount, and setup lifecycles. */
    private val lifecycleMutex = Mutex()

    /** Process registry tracking all in-flight session handles. */
    private val sessions = ConcurrentHashMap<String, ActiveSession>()

    // ---- Status checks ----------------------------------------------------

    /**
     * Checks if the Ubuntu rootfs is installed by testing for an executable bash binary.
     */
    suspend fun isInstalled(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = RootManager.runAsRoot("test -x $UBUNTU_ROOT/bin/bash")
            result.isSuccess
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks if essential chroot filesystems (/proc) are currently mounted.
     */
    suspend fun isMounted(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = RootManager.runAsRoot("grep -q \" $UBUNTU_ROOT/proc \" /proc/mounts")
            result.isSuccess
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Returns true if there are registered active sessions whose processes are alive.
     * Direct API exposed to avoid reflection from UbuntuManager / UI.
     */
    fun hasActiveSessions(): Boolean {
        pruneDeadSessions()
        return sessions.isNotEmpty()
    }

    /**
     * Executes [block] while holding the lifecycle mutex.
     * Used by UbuntuManager for atomic install/repair/remove operations.
     */
    suspend fun <T> withEnvironmentLock(block: suspend () -> T): T {
        return lifecycleMutex.withLock {
            block()
        }
    }

    // ---- Mount lifecycle --------------------------------------------------

    /**
     * Internal unlocked mount implementation. Caller must hold [lifecycleMutex]
     * or ensure lock is acquired.
     */
    internal suspend fun mountLocked(): Boolean = withContext(Dispatchers.IO) {
        try {
            val mountScript = """
                R="$UBUNTU_ROOT"
                mkdir -p "${'$'}R/proc" "${'$'}R/sys" "${'$'}R/dev" "${'$'}R/dev/pts" "${'$'}R/etc" "${'$'}R$SESSION_RUN_DIR"

                # 1. Procfs
                grep -q " ${'$'}R/proc " /proc/mounts || mount -t proc proc "${'$'}R/proc"

                # 2. Sysfs
                grep -q " ${'$'}R/sys " /proc/mounts || mount -t sysfs sysfs "${'$'}R/sys"

                # 3. Dev bind mount (slave to avoid propagating chroot dev nodes to host)
                if ! grep -q " ${'$'}R/dev " /proc/mounts; then
                    mount --bind /dev "${'$'}R/dev"
                    mount --make-slave "${'$'}R/dev" 2>/dev/null || true
                fi

                # 4. Devpts: mount dedicated instance with newinstance to protect Android global pts
                if ! grep -q " ${'$'}R/dev/pts " /proc/mounts; then
                    mount -t devpts -o newinstance,ptmxmode=0666 devpts "${'$'}R/dev/pts" 2>/dev/null \
                        || mount -t devpts devpts "${'$'}R/dev/pts" 2>/dev/null \
                        || mount --bind /dev/pts "${'$'}R/dev/pts" 2>/dev/null \
                        || true
                fi

                # 5. Shared storage (/sdcard)
                if [ -d /sdcard ]; then
                    mkdir -p "${'$'}R/sdcard"
                    grep -q " ${'$'}R/sdcard " /proc/mounts || mount --bind /sdcard "${'$'}R/sdcard" 2>/dev/null || true
                elif [ -d /storage/emulated/0 ]; then
                    mkdir -p "${'$'}R/sdcard"
                    grep -q " ${'$'}R/sdcard " /proc/mounts || mount --bind /storage/emulated/0 "${'$'}R/sdcard" 2>/dev/null || true
                fi

                # 6. DNS resolver
                printf "nameserver 8.8.8.8\nnameserver 1.1.1.1\n" > "${'$'}R/etc/resolv.conf"
            """.trimIndent()

            val result = RootManager.runAsRoot(mountScript)
            result.isSuccess || isMounted()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Internal unlocked unmount implementation.
     */
    internal suspend fun unmountLocked(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!force && hasActiveSessions()) {
                return@withContext false
            }

            val unmountScript = """
                R="$UBUNTU_ROOT"
                umount -l "${'$'}R/sdcard" 2>/dev/null || true
                umount -l "${'$'}R/dev/pts" 2>/dev/null || true
                umount -l "${'$'}R/dev" 2>/dev/null || true
                umount -l "${'$'}R/sys" 2>/dev/null || true
                umount -l "${'$'}R/proc" 2>/dev/null || true
            """.trimIndent()

            RootManager.runAsRoot(unmountScript)
            !isMounted()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Mounts proc, sysfs, dev, devpts, shared storage, and writes DNS configuration.
     * Thread-safe and idempotent; protected by [lifecycleMutex].
     */
    suspend fun mountAll(): Boolean = lifecycleMutex.withLock {
        mountLocked()
    }

    /**
     * Lazily unmounts all mounted chroot partitions in reverse order.
     * Rejects operation if active registered sessions are running, unless [force] is true.
     */
    suspend fun unmountAll(force: Boolean = false): Boolean = lifecycleMutex.withLock {
        unmountLocked(force)
    }

    // ---- Session management -----------------------------------------------

    /**
     * Starts a non-interactive one-shot session running [cmd] as user `coder`.
     * Returns the created session ID.
     */
    suspend fun startSession(cmd: String): String = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        try {
            mountAll()

            val escapedCmd = cmd.replace("'", "'\\''")
            val markerFile = "$SESSION_RUN_DIR/$sessionId.pid"

            val fullCommand = "$DEFAULT_ENV; chroot $UBUNTU_ROOT /bin/sh -c 'mkdir -p $SESSION_RUN_DIR && /bin/su - coder -c \"echo \\$\\$ > $markerFile; exec setsid $escapedCmd\"'"

            val process = ProcessBuilder("su", "-c", fullCommand)
                .redirectErrorStream(false)
                .start()

            val pid = extractPid(process)
            val info = SessionInfo(
                id = sessionId,
                cmd = cmd,
                pid = pid,
                startedAt = System.currentTimeMillis()
            )

            sessions[sessionId] = ActiveSession(info = info, process = process)
            sessionId
        } catch (_: Throwable) {
            sessionId
        }
    }

    /**
     * Spawns an interactive bash process inside the chroot under user `coder`
     * and registers it under [id].
     *
     * Uses `/usr/bin/script` PTY wrapper when available to support interactive CLI,
     * colors, and terminal applications.
     */
    suspend fun openInteractiveSession(id: String): Process? = lifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (!mountLocked()) {
                    return@withContext null
                }

                val markerFile = "$SESSION_RUN_DIR/$id.pid"
                // Interactive command with PTY wrapper support via script -qefc
                val fullCommand = """
                    $DEFAULT_ENV
                    mkdir -p "$UBUNTU_ROOT$SESSION_RUN_DIR"
                    if [ -x "$UBUNTU_ROOT/usr/bin/script" ]; then
                        chroot "$UBUNTU_ROOT" /usr/bin/script -qefc "/bin/su - coder -c 'echo \$\$ > $markerFile; exec /bin/bash -i'" /dev/null
                    else
                        chroot "$UBUNTU_ROOT" /bin/sh -c "/bin/su - coder -c 'echo \$\$ > $markerFile; exec setsid /bin/bash -i'"
                    fi
                """.trimIndent()

                val process = ProcessBuilder("su", "-c", fullCommand)
                    .redirectErrorStream(false)
                    .start()

                val pid = extractPid(process)
                val info = SessionInfo(
                    id = id,
                    cmd = "/bin/bash (interactive)",
                    pid = pid,
                    startedAt = System.currentTimeMillis()
                )

                sessions[id] = ActiveSession(info = info, process = process)
                process
            } catch (_: Throwable) {
                null
            }
        }
    }

    /**
     * Stops and kills the session process and all its child processes in the chroot.
     */
    suspend fun stopSession(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val active = sessions.remove(id) ?: return@withContext false
            val pid = active.info.pid
            val markerPath = "$UBUNTU_ROOT$SESSION_RUN_DIR/$id.pid"

            val killScript = """
                if [ -f "$markerPath" ]; then
                    INNER_PID="$(cat "$markerPath" 2>/dev/null)"
                    if [ -n "${'$'}INNER_PID" ]; then
                        pkill -9 -P "${'$'}INNER_PID" 2>/dev/null || true
                        kill -9 -"${'$'}INNER_PID" 2>/dev/null || kill -9 "${'$'}INNER_PID" 2>/dev/null || true
                    fi
                    rm -f "$markerPath"
                fi
                if [ $pid -gt 0 ]; then
                    pkill -9 -P $pid 2>/dev/null || true
                    kill -9 $pid 2>/dev/null || true
                fi
            """.trimIndent()

            RootManager.runAsRoot(killScript)

            try {
                active.process.destroyForcibly()
            } catch (_: Throwable) {}

            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Removes session from the registry without killing.
     */
    fun releaseSession(id: String) {
        sessions.remove(id)
        val markerPath = "$UBUNTU_ROOT$SESSION_RUN_DIR/$id.pid"
        try {
            File(markerPath).delete()
        } catch (_: Throwable) {}
    }

    /**
     * Stops all active sessions in the registry.
     */
    suspend fun stopAll() = withContext(Dispatchers.IO) {
        val keys = sessions.keys().toList()
        for (id in keys) {
            stopSession(id)
        }
    }

    /**
     * Lists active session metadata, pruning dead handles.
     */
    suspend fun listSessions(): List<SessionInfo> = withContext(Dispatchers.IO) {
        pruneDeadSessions()
        sessions.values.map { it.info }
    }

    private fun pruneDeadSessions() {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.value.process.isAlive) {
                val markerPath = "$UBUNTU_ROOT$SESSION_RUN_DIR/${entry.key}.pid"
                try {
                    File(markerPath).delete()
                } catch (_: Throwable) {}
                iterator.remove()
            }
        }
    }

    /**
     * Safely extracts the native OS PID from a [Process] handle.
     */
    private fun extractPid(process: Process): Long {
        return try {
            val pidMethod = process.javaClass.getMethod("pid")
            pidMethod.invoke(process) as Long
        } catch (_: Throwable) {
            try {
                val pidField = process.javaClass.getDeclaredField("pid")
                pidField.isAccessible = true
                (pidField.get(process) as Number).toLong()
            } catch (_: Throwable) {
                -1L
            }
        }
    }
}
