package com.devcode.terminal.core.ubuntu

import com.devcode.terminal.core.chroot.ChrootManager
import com.devcode.terminal.core.logging.AppLogger
import com.devcode.terminal.core.root.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bootstrap and development environment provisioning wizard for the Ubuntu rootfs.
 *
 * Provisioning Architecture:
 * - Bootstrap (DNS, user 'coder', Android network GIDs, workspace, shell config, sudo bridge)
 *   is performed directly from the Android host filesystem using root permissions without invoking chroot.
 * - This eliminates dependencies on chroot/bash/useradd binaries during initial installation.
 * - Chroot is only used for apt package installations and interactive terminal sessions.
 */
object SetupWizard {

    private const val ROOT_TIMEOUT_MS = 60_000L   // 1 min for host-side file operations
    private const val APT_TIMEOUT_MS = 1_800_000L // 30 min for apt

    /**
     * Essential dev packages installed into the Ubuntu rootfs.
     * Includes iputils-ping and net-tools by default.
     */
    val DEV_PACKAGES = listOf(
        "git",
        "curl",
        "wget",
        "python3",
        "python3-pip",
        "nodejs",
        "npm",
        "build-essential",
        "unzip",
        "nano",
        "vim",
        "sudo",
        "ca-certificates",
        "iputils-ping",
        "net-tools"
    )

    private suspend fun chrootCmd(script: String, root: String = UbuntuManager.INSTALL_DIR): String {
        val chrootBin = ChrootManager.getChrootExecutable()
        val fullScript = """
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export HOME=/root
            export USER=root
            export TERM=xterm-256color
            $script
        """.trimIndent()
        val escaped = fullScript.replace("'", "'\\''")
        return "if [ -x '$root/bin/bash' ] || [ -x '$root/usr/bin/bash' ]; then $chrootBin '$root' /bin/bash -c '$escaped'; else $chrootBin '$root' /bin/sh -c '$escaped'; fi"
    }

    /**
     * Direct host-side bootstrap: DNS + user + workspace + shell config + sudo bridge.
     * Operates directly on the host filesystem path [root] without invoking chroot.
     */
    suspend fun bootstrap(root: String = UbuntuManager.INSTALL_DIR): Boolean = withContext(Dispatchers.IO) {
        try {
            AppLogger.log("BOOTSTRAP", "Starting host-side provisioning for $root")
            if (!ensureDns(root)) {
                AppLogger.error("BOOTSTRAP", "ensureDns failed")
                return@withContext false
            }
            if (!ensureUser(root)) {
                AppLogger.error("BOOTSTRAP", "ensureUser failed")
                return@withContext false
            }
            if (!ensureWorkspace(root)) {
                AppLogger.error("BOOTSTRAP", "ensureWorkspace failed")
                return@withContext false
            }
            ensureShellConfig(root)
            AppLogger.log("BOOTSTRAP", "Host-side provisioning completed successfully")
            true
        } catch (e: Exception) {
            AppLogger.error("BOOTSTRAP", "Bootstrap exception: ${e.message}")
            false
        }
    }

    /**
     * Configures DNS directly in [root]/etc/resolv.conf from the host.
     * Replaces symlink safely with static DNS entries.
     */
    suspend fun ensureDns(root: String = UbuntuManager.INSTALL_DIR): Boolean = withContext(Dispatchers.IO) {
        try {
            val script = """
                R="$root"
                mkdir -p "${'$'}R/etc"
                rm -f "${'$'}R/etc/resolv.conf" 2>/dev/null || true
                printf "nameserver 8.8.8.8\nnameserver 1.1.1.1\n" > "${'$'}R/etc/resolv.conf"
                chmod 644 "${'$'}R/etc/resolv.conf" 2>/dev/null || true
                [ -f "${'$'}R/etc/resolv.conf" ]
            """.trimIndent()
            val result = RootManager.runAsRoot(script, ROOT_TIMEOUT_MS)
            result.isSuccess
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Provisions Android networking groups, the primary 'coder' user, PAM configurations,
     * and the Universal Sudo Bridge directly on the host filesystem plane.
     *
     * Invariants:
     * - Injects Android Network GIDs (3003 aid_inet, 3004 aid_net_raw) for Internet socket access.
     * - Prepares /etc/sudoers.d/90-coder (NOPASSWD: ALL).
     * - Configures PAM su, su-l, and sudo with pam_permit.so.
     * - Unlocks root password in /etc/shadow for passwordless elevation.
     * - Installs resilient /usr/local/bin/sudo with dual native-su + file-IPC fallback.
     */
    suspend fun ensureUser(root: String = UbuntuManager.INSTALL_DIR): Boolean = withContext(Dispatchers.IO) {
        try {
            val script = """
                R="$root"
                mkdir -p "${'$'}R/etc" "${'$'}R/etc/sudoers.d" "${'$'}R/etc/pam.d" "${'$'}R/usr/local/bin"

                # 1. Android network groups & coder group in /etc/group
                [ -f "${'$'}R/etc/group" ] || touch "${'$'}R/etc/group"
                grep -q '^aid_inet:' "${'$'}R/etc/group" 2>/dev/null || echo 'aid_inet:x:3003:coder' >> "${'$'}R/etc/group"
                grep -q '^aid_net_raw:' "${'$'}R/etc/group" 2>/dev/null || echo 'aid_net_raw:x:3004:coder' >> "${'$'}R/etc/group"
                grep -q '^coder:' "${'$'}R/etc/group" 2>/dev/null || echo 'coder:x:1000:' >> "${'$'}R/etc/group"

                # Add coder to sudo group
                if grep -q '^sudo:' "${'$'}R/etc/group" 2>/dev/null; then
                    if ! grep -E '^sudo:.*coder' "${'$'}R/etc/group" >/dev/null 2>&1; then
                        sed -i 's/^sudo:x:\([0-9]*\):.*/&,coder/;s/:,coder/:coder/' "${'$'}R/etc/group" 2>/dev/null || true
                    fi
                else
                    echo 'sudo:x:27:coder' >> "${'$'}R/etc/group"
                fi

                # 2. Sudoers passwordless configuration
                echo 'coder ALL=(ALL) NOPASSWD:ALL' > "${'$'}R/etc/sudoers.d/90-coder"
                chmod 440 "${'$'}R/etc/sudoers.d/90-coder" 2>/dev/null || true

                # 3. PAM su, su-l, and sudo permissive elevation (pam_permit.so)
                printf "auth       sufficient pam_rootok.so\nauth       sufficient pam_permit.so\naccount    sufficient pam_permit.so\nsession    sufficient pam_permit.so\n" > "${'$'}R/etc/pam.d/su"
                printf "auth       sufficient pam_rootok.so\nauth       sufficient pam_permit.so\naccount    sufficient pam_permit.so\nsession    sufficient pam_permit.so\n" > "${'$'}R/etc/pam.d/su-l"
                printf "auth       sufficient pam_rootok.so\nauth       sufficient pam_permit.so\naccount    sufficient pam_permit.so\nsession    sufficient pam_permit.so\n" > "${'$'}R/etc/pam.d/sudo"
                chmod 644 "${'$'}R/etc/pam.d/su" "${'$'}R/etc/pam.d/su-l" "${'$'}R/etc/pam.d/sudo" 2>/dev/null || true

                # 4. Universal Sudo Bridge with dual-path execution
                cat > "${'$'}R/usr/local/bin/sudo" <<'SUDO_EOF'
#!/bin/sh
# DEVCODE Universal Sudo Bridge
if [ "${'$'}(id -u)" = "0" ]; then
    exec "${'$'}@"
fi

# Path A: Direct native su execution (inherits terminal PTY cleanly)
if /bin/su - root -c "true" 2>/dev/null || su - root -c "true" 2>/dev/null; then
    exec /bin/su - root -c "${'$'}*"
fi

# Path B: Resilient file-based IPC bridge (non-blocking fallback)
SUDO_DIR="/run/devcode/sudo"
if [ -d "${'$'}SUDO_DIR" ]; then
    PID="${'$'}${'$'}"
    REQ="${'$'}SUDO_DIR/req.${'$'}PID"
    OUT="${'$'}SUDO_DIR/out.${'$'}PID"
    RET="${'$'}SUDO_DIR/ret.${'$'}PID"
    rm -f "${'$'}REQ" "${'$'}OUT" "${'$'}RET" 2>/dev/null
    printf "%s\n" "${'$'}PWD|${'$'}*" > "${'$'}REQ.tmp"
    mv -f "${'$'}REQ.tmp" "${'$'}REQ"
    WAIT_COUNT=0
    while [ ! -f "${'$'}RET" ] && [ "${'$'}WAIT_COUNT" -lt 1200 ]; do
        if [ -f "${'$'}OUT" ]; then
            cat "${'$'}OUT" 2>/dev/null
            > "${'$'}OUT"
        fi
        sleep 0.05 2>/dev/null || usleep 50000 2>/dev/null || sleep 1 2>/dev/null || true
        WAIT_COUNT=${'$'}((WAIT_COUNT + 1))
    done
    if [ -f "${'$'}OUT" ]; then
        cat "${'$'}OUT" 2>/dev/null
        rm -f "${'$'}OUT" 2>/dev/null
    fi
    CODE=${'$'}(cat "${'$'}RET" 2>/dev/null || echo 0)
    rm -f "${'$'}REQ" "${'$'}RET" 2>/dev/null
    exit ${'$'}{CODE:-0}
fi

# Fallback: exec su directly
exec /bin/su - root -c "${'$'}*"
SUDO_EOF
                chmod 755 "${'$'}R/usr/local/bin/sudo" 2>/dev/null || true

                # 5. Ensure essential apt and temporary directories exist with correct permissions
                mkdir -p "${'$'}R/var/lib/apt/lists/partial"
                mkdir -p "${'$'}R/var/cache/apt/archives/partial"
                mkdir -p "${'$'}R/var/log"
                mkdir -p "${'$'}R/tmp" "${'$'}R/var/tmp"
                chmod 1777 "${'$'}R/tmp" "${'$'}R/var/tmp" 2>/dev/null || true
                chmod 755 "${'$'}R/var/lib/apt/lists" "${'$'}R/var/lib/apt/lists/partial" 2>/dev/null || true
                chmod 755 "${'$'}R/var/cache/apt/archives/partial" 2>/dev/null || true

                # 6. Primary 'coder' user in /etc/passwd (UID 1000, GID 1000)
                [ -f "${'$'}R/etc/passwd" ] || touch "${'$'}R/etc/passwd"
                if ! grep -q '^coder:' "${'$'}R/etc/passwd" 2>/dev/null; then
                    echo 'coder:x:1000:1000:coder:/home/coder:/bin/bash' >> "${'$'}R/etc/passwd"
                fi

                # 7. Unlock passwords in /etc/shadow for seamless elevation
                if [ -f "${'$'}R/etc/shadow" ]; then
                    sed -i 's/^root:[^:]*:/root::/' "${'$'}R/etc/shadow" 2>/dev/null || true
                    if ! grep -q '^coder:' "${'$'}R/etc/shadow" 2>/dev/null; then
                        echo 'coder::19800:0:99999:7:::' >> "${'$'}R/etc/shadow"
                    else
                        sed -i 's/^coder:[^:]*:/coder::/' "${'$'}R/etc/shadow" 2>/dev/null || true
                    fi
                fi

                # 8. Ensure setuid bit on su binary inside rootfs
                chmod 4755 "${'$'}R/bin/su" "${'$'}R/usr/bin/su" 2>/dev/null || true

                # Verify coder is present in passwd
                grep -q '^coder:' "${'$'}R/etc/passwd"
            """.trimIndent()

            val result = RootManager.runAsRoot(script, ROOT_TIMEOUT_MS)
            result.isSuccess
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Prepares user workspace directory directly on the host filesystem
     * and assigns ownership to numeric UID/GID 1000:1000.
     */
    suspend fun ensureWorkspace(root: String = UbuntuManager.INSTALL_DIR): Boolean = withContext(Dispatchers.IO) {
        try {
            val script = """
                R="$root"
                mkdir -p "${'$'}R/home/coder/projects"
                mkdir -p "${'$'}R/home/coder/.npm-global"
                chown -R 1000:1000 "${'$'}R/home/coder" 2>/dev/null || true
                chmod 755 "${'$'}R/home/coder" "${'$'}R/home/coder/projects" 2>/dev/null || true
                [ -d "${'$'}R/home/coder/projects" ]
            """.trimIndent()
            val result = RootManager.runAsRoot(script, ROOT_TIMEOUT_MS)
            result.isSuccess
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Writes user shell environment directly to [root]/home/coder/.bashrc.
     */
    private suspend fun ensureShellConfig(root: String = UbuntuManager.INSTALL_DIR): Boolean = withContext(Dispatchers.IO) {
        try {
            val script = """
                R="$root"
                mkdir -p "${'$'}R/home/coder"
                BASHRC="${'$'}R/home/coder/.bashrc"
                touch "${'$'}BASHRC"
                if ! grep -q "DEVCODE_SHELL" "${'$'}BASHRC" 2>/dev/null; then
                    cat >> "${'$'}BASHRC" <<'EOF'
# DEVCODE_SHELL - managed by DEVCODE app
export NPM_CONFIG_PREFIX=/home/coder/.npm-global
export PATH=/home/coder/.npm-global/bin:${'$'}PATH
export HOME=/home/coder
export USER=coder
export TERM=xterm-256color
cd /home/coder/projects 2>/dev/null || true
EOF
                fi
                # Disable bracketed paste so readline does not print [?2004h / [?2004l
                echo 'set enable-bracketed-paste off' > "${'$'}R/home/coder/.inputrc" 2>/dev/null || true
                echo 'set enable-bracketed-paste off' > "${'$'}R/etc/inputrc" 2>/dev/null || true
                chown -R 1000:1000 "${'$'}R/home/coder" 2>/dev/null || true
                chmod 644 "${'$'}BASHRC" 2>/dev/null || true
            """.trimIndent()
            val result = RootManager.runAsRoot(script, ROOT_TIMEOUT_MS)
            result.isSuccess
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Installs dev packages with mounts held, policy-rc.d guard, and automatic dpkg recovery.
     */
    suspend fun installPackages(
        packages: List<String>,
        log: (String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (packages.isEmpty()) {
                log("No packages selected — skipping apt install.")
                return@withContext true
            }
            return@withContext ChrootManager.withEnvironmentLock {
                if (!ChrootManager.mountLocked()) {
                    log("Error: could not mount chroot filesystems")
                    AppLogger.error("APT", "Could not mount chroot filesystems")
                    return@withEnvironmentLock false
                }
                try {
                    AppLogger.log("APT", "Clearing stale package lock files...")
                    // 1. Clear any stale lock files from previous interrupted runs
                    RootManager.runAsRoot(
                        chrootCmd("rm -f /var/lib/dpkg/lock* /var/lib/apt/lists/lock* /var/cache/apt/archives/lock* 2>/dev/null || true"),
                        ROOT_TIMEOUT_MS
                    )

                    // 2. Auto-recovery: configure interrupted dpkg packages
                    log("Running dpkg auto-recovery (dpkg --configure -a)...")
                    AppLogger.log("APT", "Running dpkg --configure -a")
                    val dpkgRecCmd = chrootCmd("DEBIAN_FRONTEND=noninteractive dpkg --configure -a")
                    val dpkgRecRes = RootManager.runAsRoot(dpkgRecCmd, APT_TIMEOUT_MS)
                    log("dpkg recovery exit=${dpkgRecRes.exitCode}")

                    // 3. Fix broken package dependencies if any
                    val fixCmd = chrootCmd("DEBIAN_FRONTEND=noninteractive apt-get install -f -y")
                    RootManager.runAsRoot(fixCmd, APT_TIMEOUT_MS)

                    // 4. Update package lists
                    log("Running apt-get update...")
                    AppLogger.log("APT", "Running apt-get update")
                    val updateCmd = chrootCmd(
                        "printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d && chmod +x /usr/sbin/policy-rc.d; " +
                            "DEBIAN_FRONTEND=noninteractive apt-get update"
                    )
                    val updateRes = RootManager.runAsRoot(updateCmd, APT_TIMEOUT_MS)
                    log("apt-get update exit=${updateRes.exitCode}")
                    if (!updateRes.isSuccess) {
                        val errMsg = updateRes.stderr.ifBlank { updateRes.stdout }
                        log("Error updating package lists: $errMsg")
                        AppLogger.error("APT", "apt-get update failed: $errMsg")
                        return@withEnvironmentLock false
                    }

                    // 5. Install selected dev packages
                    val pkgList = packages.joinToString(" ")
                    log("Installing: $pkgList")
                    AppLogger.log("APT", "Installing packages: $pkgList")
                    val installCmd = chrootCmd(
                        "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $pkgList; rc=${'$'}?; rm -f /usr/sbin/policy-rc.d; exit ${'$'}rc"
                    )
                    val installRes = RootManager.runAsRoot(installCmd, APT_TIMEOUT_MS)
                    log("apt-get install exit=${installRes.exitCode}")
                    if (!installRes.isSuccess) {
                        val errMsg = installRes.stderr.ifBlank { installRes.stdout }
                        log("Error installing packages: $errMsg")
                        AppLogger.error("APT", "apt-get install failed: $errMsg")
                        return@withEnvironmentLock false
                    }
                    ensureWorkspace()
                    log("Package installation completed successfully.")
                    AppLogger.log("APT", "Package installation completed successfully")
                    true
                } finally {
                    ChrootManager.unmountLocked()
                }
            }
        } catch (e: Exception) {
            try {
                log("Install exception: ${e.message}")
                AppLogger.error("APT", "Install exception: ${e.message}")
            } catch (_: Exception) {}
            false
        }
    }

    /**
     * Executes the bootstrap setup sequence + optional dev packages.
     */
    suspend fun runDevSetup(
        selected: List<String> = DEV_PACKAGES,
        log: (String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            log("Configuring Ubuntu user environment...")
            if (!bootstrap()) {
                log("Error: bootstrap (DNS/user/workspace) failed")
                return@withContext false
            }
            log("Bootstrap complete: coder user, workspace, shell config ready.")
            installPackages(selected, log)
        } catch (e: Exception) {
            try {
                log("Dev setup exception: ${e.message}")
            } catch (_: Exception) {}
            false
        }
    }
}
