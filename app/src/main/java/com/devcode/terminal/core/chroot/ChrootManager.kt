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
     * - Mount point is isolated via `mount --make-rprivate /` and `--make-rprivate $R`.
     * - `$R/dev` is mounted as an independent isolated `tmpfs` so the Android host `/dev` is NEVER touched or modified!
     * - Character nodes inside `$R/dev` are bind-mounted individually or created inside the tmpfs.
     * - `/dev/pts` uses an isolated dedicated `newinstance` devpts mount.
     * - `$R/dev/ptmx` symlinks to `pts/ptmx` STRICTLY inside the chroot tmpfs.
     */
    internal suspend fun mountTargetLocked(targetRoot: String = UBUNTU_ROOT): Boolean = withContext(Dispatchers.IO) {
        try {
            val mountScript = """
                R="$targetRoot"
                # 1. Private mount namespace hierarchy to prevent leak to host or other chroots
                mount --make-rprivate / 2>/dev/null || true
                mount --make-rprivate "${'$'}R" 2>/dev/null || true
                # Remount rootfs with suid enabled so su binary can switch UID
                mount --bind "${'$'}R" "${'$'}R" 2>/dev/null || true
                mount -o remount,suid,dev "${'$'}R" 2>/dev/null || true
                mkdir -p "${'$'}R/proc" "${'$'}R/sys" "${'$'}R/dev" "${'$'}R/dev/pts" "${'$'}R/dev/shm" "${'$'}R/etc" "${'$'}R$SESSION_RUN_DIR"

                # 2. Procfs
                grep -q " ${'$'}R/proc " /proc/mounts || mount -t proc proc "${'$'}R/proc"

                # 3. Sysfs
                grep -q " ${'$'}R/sys " /proc/mounts || mount -t sysfs sysfs "${'$'}R/sys"

                # 4. Dedicated isolated tmpfs on target dev with explicit dev,rw options (never nodev!)
                if ! grep -q " ${'$'}R/dev " /proc/mounts; then
                    mount -t tmpfs -o mode=755,dev,rw dev "${'$'}R/dev"
                    mkdir -p "${'$'}R/dev/pts" "${'$'}R/dev/shm"
                fi

                # 5. Dedicated devpts newinstance inside chroot tmpfs with standard tty gid (gid=5)
                if ! grep -q " ${'$'}R/dev/pts " /proc/mounts; then
                    mount -t devpts devpts "${'$'}R/dev/pts" -o newinstance,gid=5,mode=620,ptmxmode=666 2>/dev/null \
                        || mount -t devpts devpts "${'$'}R/dev/pts" -o gid=5,mode=620,ptmxmode=666 2>/dev/null \
                        || mount -t devpts devpts "${'$'}R/dev/pts" 2>/dev/null \
                        || true
                fi

                # 6. Create standard character devices directly with mknod -m 666 inside chroot tmpfs
                # Guarantees standard 0666 permissions so coder user can always write to /dev/null
                rm -f "${'$'}R/dev/null" "${'$'}R/dev/zero" "${'$'}R/dev/full" "${'$'}R/dev/random" "${'$'}R/dev/urandom" "${'$'}R/dev/tty"
                mknod -m 666 "${'$'}R/dev/null" c 1 3 2>/dev/null || (touch "${'$'}R/dev/null" && mount --bind /dev/null "${'$'}R/dev/null" 2>/dev/null) || true
                mknod -m 666 "${'$'}R/dev/zero" c 1 5 2>/dev/null || (touch "${'$'}R/dev/zero" && mount --bind /dev/zero "${'$'}R/dev/zero" 2>/dev/null) || true
                mknod -m 666 "${'$'}R/dev/full" c 1 7 2>/dev/null || (touch "${'$'}R/dev/full" && mount --bind /dev/full "${'$'}R/dev/full" 2>/dev/null) || true
                mknod -m 666 "${'$'}R/dev/random" c 1 8 2>/dev/null || (touch "${'$'}R/dev/random" && mount --bind /dev/random "${'$'}R/dev/random" 2>/dev/null) || true
                mknod -m 666 "${'$'}R/dev/urandom" c 1 9 2>/dev/null || (touch "${'$'}R/dev/urandom" && mount --bind /dev/urandom "${'$'}R/dev/urandom" 2>/dev/null) || true
                mknod -m 666 "${'$'}R/dev/tty" c 5 0 2>/dev/null || (touch "${'$'}R/dev/tty" && mount --bind /dev/tty "${'$'}R/dev/tty" 2>/dev/null) || true
                chmod 666 "${'$'}R/dev/null" "${'$'}R/dev/zero" "${'$'}R/dev/full" "${'$'}R/dev/random" "${'$'}R/dev/urandom" "${'$'}R/dev/tty" 2>/dev/null || true

                # 7. Standard device links strictly inside chroot tmpfs (NEVER touches host /dev!)
                ln -sf pts/ptmx "${'$'}R/dev/ptmx"
                chmod 666 "${'$'}R/dev/pts/ptmx" 2>/dev/null || true
                chmod 666 "${'$'}R/dev/ptmx" 2>/dev/null || true
                ln -sf /proc/self/fd "${'$'}R/dev/fd" 2>/dev/null || true
                ln -sf /proc/self/fd/0 "${'$'}R/dev/stdin" 2>/dev/null || true
                ln -sf /proc/self/fd/1 "${'$'}R/dev/stdout" 2>/dev/null || true
                ln -sf /proc/self/fd/2 "${'$'}R/dev/stderr" 2>/dev/null || true

                # Ensure su has setuid permission inside rootfs
                chmod 4755 "${'$'}R/bin/su" "${'$'}R/usr/bin/su" 2>/dev/null || true

                # 8. Session runtime directory with universal write permission
                mkdir -p "${'$'}R$SESSION_RUN_DIR"
                chmod 777 "${'$'}R$SESSION_RUN_DIR" 2>/dev/null || true
                mkdir -p "${'$'}R/run/devcode"
                chmod 777 "${'$'}R/run/devcode" 2>/dev/null || true

                # 9. Start persistent background root daemon inside chroot for universal sudo bridge
                FIFO="${'$'}R/run/devcode/sudo.fifo"
                rm -f "${'$'}FIFO" 2>/dev/null || true
                mkfifo -m 666 "${'$'}FIFO" 2>/dev/null || true
                chmod 666 "${'$'}FIFO" 2>/dev/null || true

                # Launch daemon inside chroot with setsid so it survives subshell exits
                setsid chroot "${'$'}R" /bin/sh -c '
                    FIFO="/run/devcode/sudo.fifo"
                    while [ -p "$FIFO" ]; do
                        if read -r req < "$FIFO"; then
                            [ -z "$req" ] && continue
                            SPID=$(printf "%s\n" "$req" | cut -d"|" -f1)
                            SDIR=$(printf "%s\n" "$req" | cut -d"|" -f2)
                            SCMD=$(printf "%s\n" "$req" | cut -d"|" -f3-)
                            (
                                cd "$SDIR" 2>/dev/null || cd /root
                                export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                                export HOME=/root
                                export USER=root
                                export TERM=xterm-256color
                                OUT="/run/devcode/sudo.out.$SPID"
                                if [ -p "$OUT" ]; then
                                    eval "$SCMD" > "$OUT" 2>&1
                                else
                                    eval "$SCMD"
                                fi
                                echo "$?" > "/run/devcode/sudo.ret.$SPID"
                            ) &
                        fi
                    done
                ' </dev/null >/dev/null 2>&1 &

                # 10. Shared storage (/sdcard) - only mount for the primary installation
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

                # 10. DNS resolver
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
                # 1. Unmount sdcard
                if grep -q " ${'$'}R/sdcard " /proc/mounts 2>/dev/null; then
                    umount -l "${'$'}R/sdcard" 2>/dev/null || true
                fi

                # 2. Unmount individual device nodes in target dev
                for node in null zero full random urandom tty; do
                    if grep -q " ${'$'}R/dev/${'$'}node " /proc/mounts 2>/dev/null; then
                        umount -l "${'$'}R/dev/${'$'}node" 2>/dev/null || true
                    fi
                done

                # 3. Unmount dev/pts and dev tmpfs
                if grep -q " ${'$'}R/dev/pts " /proc/mounts 2>/dev/null; then
                    umount -l "${'$'}R/dev/pts" 2>/dev/null || true
                fi
                if grep -q " ${'$'}R/dev " /proc/mounts 2>/dev/null; then
                    umount -l "${'$'}R/dev" 2>/dev/null || true
                fi

                # 4. Unmount sys and proc
                if grep -q " ${'$'}R/sys " /proc/mounts 2>/dev/null; then
                    umount -l "${'$'}R/sys" 2>/dev/null || true
                fi
                if grep -q " ${'$'}R/proc " /proc/mounts 2>/dev/null; then
                    umount -l "${'$'}R/proc" 2>/dev/null || true
                fi

                # 5. Catch any residual submounts strictly under target root
                awk -v r="${'$'}R/" '${'$'}2 ~ "^"r {print ${'$'}2}' /proc/mounts 2>/dev/null | sort -r | while read -r p; do
                    [ -n "${'$'}p" ] && umount -l "${'$'}p" 2>/dev/null || true
                done

                # 6. Clean up sudo bridge FIFO and return files
                rm -f "${'$'}R/run/devcode/sudo.fifo" "${'$'}R/run/devcode/sudo.ret."* 2>/dev/null || true
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
                val termLog = "$SESSION_RUN_DIR/term-$id.log"
                // Launch PTY session: use regular term log file if /dev/null cannot be opened by script
                val fullCommand = """
                    $DEFAULT_ENV
                    mkdir -p "$UBUNTU_ROOT$SESSION_RUN_DIR"
                    chmod 777 "$UBUNTU_ROOT$SESSION_RUN_DIR" 2>/dev/null || true
                    touch "$UBUNTU_ROOT$markerFile" 2>/dev/null || true
                    chmod 666 "$UBUNTU_ROOT$markerFile" 2>/dev/null || true
                    touch "$UBUNTU_ROOT$termLog" 2>/dev/null || true
                    chmod 666 "$UBUNTU_ROOT$termLog" 2>/dev/null || true
                    if [ -x "$UBUNTU_ROOT/usr/bin/script" ]; then
                        $chrootBin "$UBUNTU_ROOT" /bin/su - coder -c "echo \$\$ > '$markerFile'; OUT_FILE='$termLog'; [ -w /dev/null ] && OUT_FILE='/dev/null'; exec /usr/bin/script -qefc 'exec /bin/bash -i' \"${'$'}OUT_FILE\""
                    else
                        $chrootBin "$UBUNTU_ROOT" /bin/su - coder -c "echo \$\$ > '$markerFile'; exec /bin/bash -i"
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
     * Deploys the standalone `devcode` CLI script to /data/local/devcode/bin/devcode
     * which supports: devcode stop, devcode status, and devcode uninstall.
     */
    suspend fun deployDevcodeCli(): Boolean = withContext(Dispatchers.IO) {
        try {
            val script = """
                mkdir -p /data/local/devcode/bin
                cat > /data/local/devcode/bin/devcode <<'DEVCODE_EOF'
#!/system/bin/sh
# DEVCODE Command Manager & Lifecycle Controller
BASE="/data/local/devcode"
UBUNTU="${'$'}BASE/ubuntu"
SESSIONS_DIR="${'$'}UBUNTU/run/devcode/sessions"

case "${'$'}1" in
  stop)
    echo ">> [DEVCODE] Stopping all DEVCODE sessions & mounts..."
    # 1. Kill session processes registered via PID files
    if [ -d "${'$'}SESSIONS_DIR" ]; then
      for pidfile in "${'$'}SESSIONS_DIR"/*.pid; do
        if [ -f "${'$'}pidfile" ]; then
          pid=$(cat "${'$'}pidfile" 2>/dev/null)
          if [ -n "${'$'}pid" ] && [ "${'$'}pid" -gt 0 ] 2>/dev/null; then
            pkill -9 -P "${'$'}pid" 2>/dev/null || true
            kill -9 "-${'$'}pid" 2>/dev/null || kill -9 "${'$'}pid" 2>/dev/null || true
          fi
          rm -f "${'$'}pidfile"
        fi
      done
    fi

    # 2. Terminate any processes whose root is inside DEVCODE (without touching other chroots)
    for p in /proc/[0-9]*; do
      pid=${'$'}{p#/proc/}
      rlink=$(readlink "/proc/${'$'}pid/root" 2>/dev/null || true)
      case "${'$'}rlink" in
        "${'$'}UBUNTU"*)
          kill -9 "${'$'}pid" 2>/dev/null || true
          ;;
      esac
    done

    # 3. Unmount only DEVCODE virtual mounts in reverse order
    for m in "${'$'}UBUNTU/sdcard" "${'$'}UBUNTU/dev/pts" "${'$'}UBUNTU/dev/null" "${'$'}UBUNTU/dev/zero" "${'$'}UBUNTU/dev/random" "${'$'}UBUNTU/dev/urandom" "${'$'}UBUNTU/dev/tty" "${'$'}UBUNTU/dev" "${'$'}UBUNTU/sys" "${'$'}UBUNTU/proc"; do
      if grep -q " ${'$'}m " /proc/mounts 2>/dev/null; then
        umount -l "${'$'}m" 2>/dev/null || true
      fi
    done

    # Catch any residual submounts strictly under /data/local/devcode/
    awk -v r="${'$'}BASE/" '${'$'}2 ~ "^"r {print ${'$'}2}' /proc/mounts 2>/dev/null | sort -r | while read -r sub; do
      [ -n "${'$'}sub" ] && umount -l "${'$'}sub" 2>/dev/null || true
    done
    echo ">> [DEVCODE] All DEVCODE sessions stopped and unmounted cleanly."
    ;;

  status)
    echo "=========================================="
    echo "        DEVCODE WORKSTATION STATUS        "
    echo "=========================================="
    if [ -x "${'$'}UBUNTU/bin/bash" ] || [ -x "${'$'}UBUNTU/usr/bin/bash" ]; then
      echo "Status:       INSTALLED (Ubuntu 24.04 ARM64)"
    else
      echo "Status:       NOT INSTALLED"
    fi

    active_mounts=$(awk -v r="${'$'}BASE/" '${'$'}2 ~ "^"r {print ${'$'}2}' /proc/mounts 2>/dev/null)
    if [ -n "${'$'}active_mounts" ]; then
      echo "Mounts:       ACTIVE (${'$'}(echo "${'$'}active_mounts" | wc -l) mounted)"
      echo "${'$'}active_mounts" | sed 's/^/  - /'
    else
      echo "Mounts:       NONE (Cleanly Unmounted)"
    fi

    session_pids=""
    if [ -d "${'$'}SESSIONS_DIR" ]; then
      for f in "${'$'}SESSIONS_DIR"/*.pid; do
        [ -f "${'$'}f" ] && session_pids="${'$'}session_pids ${'$'}(cat "${'$'}f" 2>/dev/null)"
      done
    fi
    if [ -n "${'$'}session_pids" ]; then
      echo "Active PIDs:  ${'$'}session_pids"
    else
      echo "Active PIDs:  NONE"
    fi

    if [ -d "${'$'}UBUNTU" ]; then
      used=${'$'}(du -sk "${'$'}UBUNTU" 2>/dev/null | awk '{print int(${'$'}1/1024)" MB"}' || echo "N/A")
      avail=${'$'}(df -k "${'$'}UBUNTU" 2>/dev/null | tail -1 | awk '{print int(${'$'}4/1024)" MB"}' || echo "N/A")
      echo "Rootfs:       Used: ${'$'}used | Available: ${'$'}avail"
    fi

    if [ -f "${'$'}UBUNTU/etc/passwd" ]; then
      users=${'$'}(grep -E 'coder|root' "${'$'}UBUNTU/etc/passwd" | awk -F: '{print ${'$'}1" (UID "${'$'}3")"}' | tr '\n' ', ' | sed 's/, ${'$'}//')
      echo "Users:        ${'$'}users"
    fi
    echo "=========================================="
    ;;

  uninstall)
    echo ">> [DEVCODE] Uninstalling DEVCODE environment..."
    sh "${'$'}0" stop
    sleep 1
    if awk -v r="${'$'}BASE/" '${'$'}2 ~ "^"r {print ${'$'}2}' /proc/mounts 2>/dev/null | grep -q .; then
      echo "ERROR: Some DEVCODE mounts are still active. Aborting."
      exit 1
    fi
    rm -rf "${'$'}BASE"
    echo ">> [DEVCODE] Complete uninstall successful. Rootfs and configs removed."
    ;;

  *)
    echo "Usage: devcode {stop|status|uninstall}"
    exit 1
    ;;
esac
DEVCODE_EOF
                chmod 755 /data/local/devcode/bin/devcode
                # Symlink to /data/local/bin if available
                mkdir -p /data/local/bin 2>/dev/null || true
                ln -sf /data/local/devcode/bin/devcode /data/local/bin/devcode 2>/dev/null || true
            """.trimIndent()
            val r = RootManager.runAsRoot(script, 15_000L)
            r.isSuccess
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Executes `devcode stop` to kill all sessions and clean up mounts.
     */
    suspend fun stopAllCleanly(): String = withContext(Dispatchers.IO) {
        try {
            stopAll()
            unmountAll(force = true)
            val r = RootManager.runAsRoot("/data/local/devcode/bin/devcode stop", 30_000L)
            if (r.stdout.isNotBlank()) r.stdout else "All sessions stopped and unmounted cleanly."
        } catch (e: Exception) {
            "Stop error: ${e.message}"
        }
    }

    /**
     * Executes `devcode status` to return comprehensive status report.
     */
    suspend fun getDevcodeStatus(): String = withContext(Dispatchers.IO) {
        try {
            deployDevcodeCli()
            val r = RootManager.runAsRoot("/data/local/devcode/bin/devcode status", 15_000L)
            if (r.stdout.isNotBlank()) r.stdout else "Unable to fetch status"
        } catch (e: Exception) {
            "Status error: ${e.message}"
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
