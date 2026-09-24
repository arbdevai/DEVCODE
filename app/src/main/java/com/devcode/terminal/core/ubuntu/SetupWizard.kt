package com.devcode.terminal.core.ubuntu

import com.devcode.terminal.core.chroot.ChrootManager
import com.devcode.terminal.core.root.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bootstrap and development environment provisioning wizard for the Ubuntu rootfs.
 *
 * Provisioning Architecture:
 * - Bootstrap (DNS, user 'coder', Android network GIDs, workspace, shell config) is performed
 *   directly from the Android host filesystem using root permissions without invoking chroot.
 * - This eliminates dependencies on chroot/bash/useradd binaries during the initial installation.
 * - Chroot is only used for apt package installations and interactive terminal sessions.
 */
object SetupWizard {

    private const val ROOT_TIMEOUT_MS = 60_000L   // 1 min for host-side file operations
    private const val APT_TIMEOUT_MS = 1_800_000L // 30 min for apt

    /**
     * Essential dev packages installed into the Ubuntu rootfs.
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
        "ca-certificates"
    )

    private suspend fun chrootCmd(script: String, root: String = UbuntuManager.INSTALL_DIR): String {
        val chrootBin = ChrootManager.getChrootExecutable()
        val escaped = script.replace("'", "'\\''")
        return "if [ -x '$root/bin/bash' ] || [ -x '$root/usr/bin/bash' ]; then $chrootBin '$root' /bin/bash -c '$escaped'; else $chrootBin '$root' /bin/sh -c '$escaped'; fi"
    }

    /**
     * Direct host-side bootstrap: DNS + user + workspace + shell config.
     * Operates directly on the host filesystem path [root] without invoking chroot.
     */
    suspend fun bootstrap(root: String = UbuntuManager.INSTALL_DIR): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!ensureDns(root)) return@withContext false
            if (!ensureUser(root)) return@withContext false
            if (!ensureWorkspace(root)) return@withContext false
            ensureShellConfig(root)
            true
        } catch (_: Exception) {
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
     * Provisions Android networking groups and the primary 'coder' user
     * directly into [root]/etc/passwd, [root]/etc/group, and [root]/etc/shadow.
     *
     * Required Android Network GIDs:
     * - 3003 (aid_inet): required by Android kernel to open AF_INET sockets.
     * - 3004 (aid_net_raw): required for raw socket / ping operations.
     */
    suspend fun ensureUser(root: String = UbuntuManager.INSTALL_DIR): Boolean = withContext(Dispatchers.IO) {
        try {
            val script = """
                R="$root"
                mkdir -p "${'$'}R/etc"

                # 1. Android network groups & coder group in /etc/group
                [ -f "${'$'}R/etc/group" ] || touch "${'$'}R/etc/group"
                grep -q '^aid_inet:' "${'$'}R/etc/group" 2>/dev/null || echo 'aid_inet:x:3003:coder' >> "${'$'}R/etc/group"
                grep -q '^aid_net_raw:' "${'$'}R/etc/group" 2>/dev/null || echo 'aid_net_raw:x:3004:coder' >> "${'$'}R/etc/group"
                grep -q '^coder:' "${'$'}R/etc/group" 2>/dev/null || echo 'coder:x:1000:' >> "${'$'}R/etc/group"

                # Add coder to sudo group if sudo exists
                if grep -q '^sudo:' "${'$'}R/etc/group" 2>/dev/null; then
                    if ! grep -E '^sudo:.*coder' "${'$'}R/etc/group" >/dev/null 2>&1; then
                        sed -i 's/^sudo:x:\([0-9]*\):.*/&,coder/;s/:,coder/:coder/' "${'$'}R/etc/group" 2>/dev/null || true
                    fi
                fi

                # 2. Primary 'coder' user in /etc/passwd (UID 1000, GID 1000)
                [ -f "${'$'}R/etc/passwd" ] || touch "${'$'}R/etc/passwd"
                if ! grep -q '^coder:' "${'$'}R/etc/passwd" 2>/dev/null; then
                    echo 'coder:x:1000:1000:coder:/home/coder:/bin/bash' >> "${'$'}R/etc/passwd"
                fi

                # 3. Shadow record in /etc/shadow
                if [ -f "${'$'}R/etc/shadow" ]; then
                    if ! grep -q '^coder:' "${'$'}R/etc/shadow" 2>/dev/null; then
                        echo 'coder:*:19800:0:99999:7:::' >> "${'$'}R/etc/shadow"
                    fi
                fi

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
     * Installs optional dev packages with mounts held and policy-rc.d guard.
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
                    return@withEnvironmentLock false
                }
                try {
                    log("Running apt-get update...")
                    val updateCmd = chrootCmd(
                        "printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d && chmod +x /usr/sbin/policy-rc.d; " +
                            "DEBIAN_FRONTEND=noninteractive apt-get update"
                    )
                    val updateRes = RootManager.runAsRoot(updateCmd, APT_TIMEOUT_MS)
                    log("apt-get update exit=${updateRes.exitCode}")
                    if (!updateRes.isSuccess) {
                        log("Error updating package lists: ${updateRes.stderr.ifBlank { updateRes.stdout }}")
                        return@withEnvironmentLock false
                    }

                    val pkgList = packages.joinToString(" ")
                    log("Installing: $pkgList")
                    val installCmd = chrootCmd(
                        "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $pkgList; rc=${'$'}?; rm -f /usr/sbin/policy-rc.d; exit ${'$'}rc"
                    )
                    val installRes = RootManager.runAsRoot(installCmd, APT_TIMEOUT_MS)
                    log("apt-get install exit=${installRes.exitCode}")
                    if (!installRes.isSuccess) {
                        log("Error installing packages: ${installRes.stderr.ifBlank { installRes.stdout }}")
                        return@withEnvironmentLock false
                    }
                    ensureWorkspace()
                    log("Package installation completed.")
                    true
                } finally {
                    ChrootManager.unmountLocked()
                }
            }
        } catch (e: Exception) {
            try {
                log("Install exception: ${e.message}")
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
