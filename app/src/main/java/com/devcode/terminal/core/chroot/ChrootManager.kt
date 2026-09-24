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
     * Checks if essential chroot filesystems (/proc) are currently mounted for target root.
     */
    suspend fun isMounted(root: String = UBUNTU_ROOT): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = RootManager.runAsRoot("grep -q \" $root/proc \" /proc/mounts")
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
     * Mounts chroot virtual filesystems for a specific target root (e.g. main UBUNTU_ROOT
     * or a temporary extraction directory).
     *
     * Isolation invariants:
     * - Mount point is isolated via `--make-rprivate` so it does not propagate to other chroots on the device.
     * - `/dev` uses `--make-rslave` to avoid publishing devices to the host or other chroots.
     * - `/dev/pts` uses a dedicated `newinstance` mount to isolate DEVCODE pseudo-terminals.
     */
    internal suspend fun mountTargetLocked(targetRoot: String = UBUNTU_ROOT): Boolean = withContext(Dispatchers.IO) {
        try {
            val mountScript = """
                R="$targetRoot"
                # Isolate mount hierarchy so other chroots on the device are never affected
                mount --make-rprivate "${'$'}R" 2>/dev/null || true
                mkdir -p "${'$'}R/proc" "${'$'}R/sys" "${'$'}R/dev" "${'$'}R/dev/pts" "${'$'}R/etc" "${'$'}R$SESSION_RUN_DIR"

                # 1. Procfs
                grep -q " ${'$'}R/proc " /proc/mounts || mount -t proc proc "${'$'}R/proc"

                # 2. Sysfs
                grep -q " ${'$'}R/sys " /proc/mounts || mount -t sysfs sysfs "${'$'}R/sys"

                # 3. Dev bind mount with recursive slave
                if ! grep -q " ${'$'}R/dev " /proc/mounts; then
                    mount --bind /dev "${'$'}R/dev"
                    mount --make-rslave "${'$'}R/dev" 2>/dev/null || mount --make-slave "${'$'}R/dev" 2>/dev/null || true
                fi

                # 4. Devpts: isolated dedicated instance
                if ! grep -q " ${'$'}R/dev/pts " /proc/mounts; then
                    mount -t devpts -o newinstance,ptmxmode=0666,mode=620 devpts "${'$'}R/dev/pts" 2>/dev/null \
                        || mount -t devpts devpts "${'$'}R/dev/pts" 2>/dev/null \
                        || mount --bind /dev/pts "${'$'}R/dev/pts" 2>/dev/null \
                        || true
                fi

                # Essential standard device nodes inside chroot
                [ -e "${'$'}R/dev/null" ] || mknod -m 666 "${'$'}R/dev/null" c 1 3 2>/dev/null || true
                [ -e "${'$'}R/dev/zero" ] || mknod -m 666 "${'$'}R/dev/zero" c 1 5 2>/dev/null || true
                [ -e "${'$'}R/dev/random" ] || mknod -m 666 "${'$'}R/dev/random" c 1 8 2>/dev/null || true
                [ -e "${'$'}R/dev/urandom" ] || mknod -m 666 "${'$'}R/dev/urandom" c 1 9 2>/dev/null || true
                chmod 666 "${'$'}R/dev/pts/ptmx" 2>/dev/null || true
                rm -f "${'$'}R/dev/ptmx" 2>/dev/null || true
                ln -s pts/ptmx "${'$'}R/dev/ptmx" 2>/dev/null || true

                # Session runtime directory with universal write permission
                mkdir -p "${'$'}R$SESSION_RUN_DIR"
                chmod 777 "${'$'}R$SESSION_RUN_DIR" 2>/dev/null || true

                # 5. Shared storage (/sdcard) - only mount for the primary installation
                if [ "${'$'}R" = "$UBUNTU_ROOT" ]; then
                    if [ -d /sdcard ]; then
                        mkdir -p "${'$'}R/sdcard"
                        grep -q " ${'$'}R/sdcard " /proc/mounts || mount --bind /sdcard "${'$'}R/sdcard" 2>/dev/null || true
                        mount --make-slave "${'$'}R/sdcard" 2>/dev/null || true
                    elif [ -d /storage/emulated/0 ]; then
                        mkdir -p "${'$'}R/sdcard"
                        grep -q " ${'$'}R/sdcard " /proc/mounts || mount --bind /storage/emulated/0 "${'$'}R/sdcard" 2>/dev/null || true
                        mount --make-slave "${'$'}R/sdcard" 2>/dev/null || true
                    fi
                fi

                # 6. DNS resolver
                mkdir -p "${'$'}R/etc"
                if [ -L "${'$'}R/etc/resolv.conf" ]; then rm -f "${'$'}R/etc/resolv.conf"; fi
                printf "nameserver 8.8.8.8\nnameserver 1.1.1.1\n" > "${'$'}R/etc/resolv.conf"
            """.trimIndent()

            val result = RootManager.runAsRoot(mountScript)
            result.isSuccess || isMounted(targetRoot)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Unmounts all virtual filesystems specifically under [targetRoot].
     * Never touches or affects any other chroot or system mount points.
     */
    internal suspend fun unmountTargetLocked(targetRoot: String = UBUNTU_ROOT, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!force && targetRoot == UBUNTU_ROOT && hasActiveSessions()) {
                return@withContext false
            }

            val unmountScript = """
                R="$targetRoot"
                # Explicit reverse unmount of known DEVCODE mount points
                for m in "${'$'}R/sdcard" "${'$'}R/dev/pts" "${'$'}R/dev" "${'$'}R/sys" "${'$'}R/proc"; do
                    if grep -q " ${'$'}m " /proc/mounts 2>/dev/null; then
                        umount -l "${'$'}m" 2>/dev/null || umount "${'$'}m" 2>/dev/null || true
                    fi
                done
                # Catch any residual submounts strictly under target root
                awk -v r="${'$'}R/" '${'$'}2 ~ "^"r {print ${'$'}2}' /proc/mounts 2>/dev/null | sort -r | while read -r p; do
                    [ -n "${'$'}p" ] && umount -l "${'$'}p" 2>/dev/null || true
                done
            """.trimIndent()

            RootManager.runAsRoot(unmountScript)
            !isMounted(targetRoot)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Internal unlocked mount implementation for UBUNTU_ROOT. Caller must hold [lifecycleMutex].
     */
    internal suspend fun mountLocked(): Boolean = mountTargetLocked(UBUNTU_ROOT)

    /**
     * Internal unlocked unmount implementation for UBUNTU_ROOT.
     */
    internal suspend fun unmountLocked(force: Boolean = false): Boolean = unmountTargetLocked(UBUNTU_ROOT, force)

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

    // ---- Chroot Binary Resolution ----------------------------------------

    @Volatile private var resolvedChrootCmd: String? = null

    /**
     * Resolves the valid chroot command on Android host across diverse root environments:
     * 1. Standard PATH: command -v chroot
     * 2. System binaries: /system/bin/chroot, /system/xbin/chroot
     * 3. Toybox chroot: /system/bin/toybox chroot
     * 4. Magisk busybox: /data/adb/magisk/busybox chroot
     * 5. KernelSU busybox: /data/adb/ksu/bin/busybox chroot
     * 6. APatch busybox: /data/adb/ap/bin/busybox chroot
     * 7. Fallback: "chroot"
     */
    suspend fun getChrootExecutable(): String = withContext(Dispatchers.IO) {
        val cached = resolvedChrootCmd
        if (cached != null) return@withContext cached

        val probeScript = """
            if command -v chroot >/dev/null 2>&1; then
                echo "chroot"
            elif [ -x /system/bin/chroot ]; then
                echo "/system/bin/chroot"
            elif [ -x /system/xbin/chroot ]; then
                echo "/system/xbin/chroot"
            elif /system/bin/toybox chroot --help >/dev/null 2>&1 || [ -x /system/bin/toybox ]; then
                if /system/bin/toybox chroot / /system/bin/sh -c 'true' 2>/dev/null; then
                    echo "/system/bin/toybox chroot"
                fi
            elif [ -x /data/adb/magisk/busybox ]; then
                echo "/data/adb/magisk/busybox chroot"
            elif [ -x /data/adb/ksu/bin/busybox ]; then
                echo "/data/adb/ksu/bin/busybox chroot"
            elif [ -x /data/adb/ap/bin/busybox ]; then
                echo "/data/adb/ap/bin/busybox chroot"
            elif command -v busybox >/dev/null 2>&1; then
                echo "busybox chroot"
            else
                echo "chroot"
            fi
        """.trimIndent()

        val r = RootManager.runAsRoot(probeScript, 5_000L)
        val resolved = r.stdout.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "chroot"
        resolvedChrootCmd = resolved
        resolved
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
            val chrootBin = getChrootExecutable()

            val escapedCmd = cmd.replace("'", "'\\''")
            val markerFile = "$SESSION_RUN_DIR/$sessionId.pid"

            val fullCommand = "$DEFAULT_ENV; mkdir -p $UBUNTU_ROOT$SESSION_RUN_DIR; chmod 777 $UBUNTU_ROOT$SESSION_RUN_DIR; $chrootBin $UBUNTU_ROOT /bin/sh -c '/bin/su - coder -c \"echo \\$\\$ > $markerFile; exec setsid $escapedCmd\"'"

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
                val chrootBin = getChrootExecutable()

                val markerFile = "$SESSION_RUN_DIR/$id.pid"
                // Interactive command with PTY wrapper support via script -qefc
                val fullCommand = """
                    $DEFAULT_ENV
                    mkdir -p "$UBUNTU_ROOT$SESSION_RUN_DIR"
                    chmod 777 "$UBUNTU_ROOT$SESSION_RUN_DIR" 2>/dev/null || true
                    touch "$UBUNTU_ROOT$markerFile" 2>/dev/null || true
                    chmod 666 "$UBUNTU_ROOT$markerFile" 2>/dev/null || true
                    if [ -x "$UBUNTU_ROOT/usr/bin/script" ]; then
                        $chrootBin "$UBUNTU_ROOT" /usr/bin/script -qefc "/bin/su - coder -c 'echo \$\$ > $markerFile; exec /bin/bash -i'" /dev/null
                    else
                        $chrootBin "$UBUNTU_ROOT" /bin/sh -c "/bin/su - coder -c 'echo \$\$ > $markerFile; exec setsid /bin/bash -i'"
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
