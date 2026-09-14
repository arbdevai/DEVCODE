package com.devcode.terminal.core.ubuntu

import com.devcode.terminal.core.chroot.ChrootManager
import com.devcode.terminal.core.root.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bootstrap and development environment provisioning wizard for the Ubuntu rootfs.
 *
 * Configures:
 *   - DNS resolution (/etc/resolv.conf, replaces symlink safely)
 *   - Android networking GIDs (aid_inet: 3003, aid_net_raw: 3004)
 *     which Android requires for AF_INET sockets under non-root UIDs
 *   - Standard 'coder' user with Android network groups (NO passwordless sudo)
 *   - Workspace directory at /home/coder/projects
 *   - Essential development toolchain packages (optional selection)
 */
object SetupWizard {

    private const val ROOT_TIMEOUT_MS = 300_000L // 5 min for user/group ops
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

    private fun chrootCmd(script: String, root: String = UbuntuManager.INSTALL_DIR): String {
        val escaped = script.replace("'", "'\\''")
        return "chroot $root /bin/bash -c '$escaped'"
    }

    /**
     * Mandatory bootstrap: DNS + user + workspace + shell config.
     * @param root chroot path (live INSTALL_DIR for repair, temp dir during install).
     * Runs without mounting (assumes caller holds environment lock).
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
     * Configures DNS inside the chroot environment so apt and network utilities work.
     * Replaces symlink safely (removes link itself, writes regular file).
     */
    suspend fun ensureDns(root: String = UbuntuManager.INSTALL_DIR): Boolean = withContext(Dispatchers.IO) {
        try {
            val script = """
                if [ -L /etc/resolv.conf ]; then rm -f /etc/resolv.conf; fi
                mkdir -p /etc
                printf "nameserver 8.8.8.8\nnameserver 1.1.1.1\n" > /etc/resolv.conf
            """.trimIndent()
            val result = RootManager.runAsRoot(chrootCmd(script, root), ROOT_TIMEOUT_MS)
            result.isSuccess
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Creates Android networking groups and the primary 'coder' user.
     * No passwordless sudo — root actions go through the app.
     */
    suspend fun ensureUser(root: String = UbuntuManager.INSTALL_DIR): Boolean = withContext(Dispatchers.IO) {
        try {
            val script = """
                groupadd -g 3003 aid_inet 2>/dev/null || true
                groupadd -g 3004 aid_net_raw 2>/dev/null || true

                if ! id coder >/dev/null 2>&1; then
                    useradd -m -s /bin/bash coder 2>/dev/null || useradd -m coder
                fi

                usermod -aG aid_inet,aid_net_raw coder 2>/dev/null || true
                # coder stays OUT of sudo group by default; app performs root ops
            """.trimIndent()

            val result = RootManager.runAsRoot(chrootCmd(script, root), ROOT_TIMEOUT_MS)
            result.isSuccess
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Prepares the user's workspace directory and sets ownership.
     */
    suspend fun ensureWorkspace(root: String = UbuntuManager.INSTALL_DIR): Boolean = withContext(Dispatchers.IO) {
        try {
            val script = """
                mkdir -p /home/coder/projects
                chown coder:coder /home/coder /home/coder/projects
            """.trimIndent()
            val result = RootManager.runAsRoot(chrootCmd(script, root), ROOT_TIMEOUT_MS)
            result.isSuccess
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Idempotent shell configuration: npm user prefix, PATH, helpful aliases.
     */
    private suspend fun ensureShellConfig(root: String = UbuntuManager.INSTALL_DIR): Boolean = withContext(Dispatchers.IO) {
        try {
            val script = """
                mkdir -p /home/coder/.npm-global
                chown coder:coder /home/coder/.npm-global
                touch /home/coder/.bashrc
                grep -q "DEVCODE_SHELL" /home/coder/.bashrc 2>/dev/null || cat >> /home/coder/.bashrc <<'EOF'
                # DEVCODE_SHELL - managed by DEVCODE app
                export NPM_CONFIG_PREFIX=/home/coder/.npm-global
                export PATH=/home/coder/.npm-global/bin:${'$'}PATH
                export HOME=/home/coder
                export USER=coder
                cd /home/coder/projects 2>/dev/null || true
                EOF
                chown coder:coder /home/coder/.bashrc
            """.trimIndent()
            val result = RootManager.runAsRoot(chrootCmd(script, root), ROOT_TIMEOUT_MS)
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
                    val updateRes = RootManager.runAsRoot(
                        chrootCmd(
                            "printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d && chmod +x /usr/sbin/policy-rc.d; " +
                                "DEBIAN_FRONTEND=noninteractive apt-get update"
                        ),
                        APT_TIMEOUT_MS
                    )
                    log("apt-get update exit=${updateRes.exitCode}")
                    if (!updateRes.isSuccess) {
                        log("Error updating package lists: ${updateRes.stderr.ifBlank { updateRes.stdout }}")
                        return@withEnvironmentLock false
                    }

                    val pkgList = packages.joinToString(" ")
                    log("Installing: $pkgList")
                    val installRes = RootManager.runAsRoot(
                        chrootCmd("DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $pkgList; rc=${'$'}?; rm -f /usr/sbin/policy-rc.d; exit ${'$'}rc"),
                        APT_TIMEOUT_MS
                    )
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
     * Preserves trailing-lambda call style: runDevSetup { log -> }.
     */
    suspend fun runDevSetup(
        selected: List<String> = DEV_PACKAGES,
        log: (String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            log("Bootstrapping Ubuntu user environment...")
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
