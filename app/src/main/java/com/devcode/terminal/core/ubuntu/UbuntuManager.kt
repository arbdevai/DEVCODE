package com.devcode.terminal.core.ubuntu

import android.content.Context
import com.devcode.terminal.core.chroot.ChrootManager
import com.devcode.terminal.core.logging.AppLogger
import com.devcode.terminal.core.root.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

/**
 * Ubuntu 24.04 ARM64 rootfs lifecycle manager.
 *
 * Safety model:
 * - App UID cannot write /data/local/devcode; downloads stage in app-private files.
 * - SHA256SUMS manifest exact-filename match required before extraction.
 * - install() refuses when a rootfs already exists; repair() never deletes user data;
 *   reinstall is remove()+install() with explicit confirmation in UI.
 * - All mutations run inside ChrootManager.withEnvironmentLock.
 */
object UbuntuManager {

    const val ROOTFS_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
    const val SHA256SUMS_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS"
    const val INSTALL_DIR = "/data/local/devcode/ubuntu"
    const val STAGING_DIR = "/data/local/devcode/staging"
    const val CACHE_DIR = "/data/local/devcode/cache"
    const val TARBALL = "$STAGING_DIR/ubuntu-base.tar.gz"

    /** Exact filename expected inside SHA256SUMS. */
    const val TARBALL_FILENAME = "ubuntu-base-24.04.5-base-arm64.tar.gz"

    /** Ownership marker proving DEVCODE created /data/local/devcode. */
    const val OWNER_MARKER = "/data/local/devcode/.devcode-owner"
    const val OWNER_VALUE = "com.devcode.terminal"

    enum class InstallStatus {
        NOT_INSTALLED,
        DOWNLOADING,
        VERIFYING,
        EXTRACTING,
        INSTALLED,
        CORRUPT,
        UNKNOWN
    }

    data class UbuntuState(
        val status: InstallStatus = InstallStatus.UNKNOWN,
        val downloadProgress: Float = 0f,
        val message: String = "",
        val storageUsedBytes: Long = 0L,
        val storageAvailBytes: Long = 0L,
        val busy: Boolean = false,
        val stepLogs: List<String> = emptyList()
    )

    private val _state = MutableStateFlow(UbuntuState())
    val state: StateFlow<UbuntuState> = _state.asStateFlow()

    fun logStep(entry: String) {
        AppLogger.log("UBUNTU", entry, isError = entry.contains("FAILED", ignoreCase = true) || entry.contains("error", ignoreCase = true))
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _state.update {
            it.copy(
                message = entry,
                stepLogs = (it.stepLogs + "[$time] $entry").takeLast(50)
            )
        }
    }

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ---------- staging ----------

    private fun privateStagingDir(): File {
        val ctx = appContext
        val dir = if (ctx != null) File(ctx.filesDir, "staging")
        else File(System.getProperty("java.io.tmpdir") ?: "/tmp", "devcode-staging")
        dir.mkdirs()
        return dir
    }

    private fun privateTarball(): File = File(privateStagingDir(), "ubuntu-base.tar.gz")
    private fun privateTarballPart(): File = File(privateStagingDir(), "ubuntu-base.tar.gz.part")

    // ---------- root helpers ----------

    private fun su(cmd: String, timeoutMs: Long = 30_000L): Pair<Int, String> {
        return try {
            val r = RootManager.runAsRoot(cmd, timeoutMs)
            Pair(r.exitCode, if (r.stdout.isNotBlank()) r.stdout else r.stderr)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "su failed")
        }
    }

    private fun isMounted(): Boolean {
        return try {
            val (code, out) = su("grep -F '$INSTALL_DIR' /proc/mounts 2>/dev/null")
            code == 0 && out.isNotBlank()
        } catch (_: Throwable) {
            true
        }
    }

    private fun rootfsExists(): Boolean {
        val (code, _) = su("test -x $INSTALL_DIR/bin/bash || test -x $INSTALL_DIR/usr/bin/bash")
        return code == 0
    }

    /** Fail closed: throws when mounts/sessions/root are uncertain or busy. */
    private fun requireIdle() {
        if (isMounted() || ChrootManager.hasActiveSessions()) {
            throw IllegalStateException("rootfs is mounted or has active sessions")
        }
        val (code, _) = su("id -u 2>/dev/null")
        if (code != 0) throw IllegalStateException("root unavailable")
    }

    /**
     * Ensures /data/local/devcode exists and is owned by DEVCODE.
     *
     * Security model:
     * - Exact marker ownership is preferred (strict owner check).
     * - If marker is missing or has a different owner (e.g., after APK reinstall or keystore update),
     *   we attempt safe reclaim:
     *   1. If a valid, functional Ubuntu rootfs exists (executable /bin/bash or /usr/bin/bash inside),
     *      we reclaim ownership by updating the marker without touching user data.
     *   2. If no valid rootfs exists, we allow claiming an empty base or an interrupted DEVCODE
     *      skeleton (staging, cache, ubuntu.install, etc.).
     *   3. Any foreign/unknown files outside DEVCODE's structure fail closed.
     */
    private fun ensureOwnedBase() {
        val command = """
            set -eu
            BASE=/data/local/devcode
            MARKER=$OWNER_MARKER
            OWNER=$OWNER_VALUE
            ROOTFS=$INSTALL_DIR
            TMP_INSTALL="${INSTALL_DIR}.install"

            if [ -L "${'$'}BASE" ]; then echo "base directory is a symlink"; exit 3; fi
            if [ ! -e "${'$'}BASE" ]; then
                mkdir -p "${'$'}BASE"
            elif [ ! -d "${'$'}BASE" ]; then
                echo "base path is not a directory"; exit 3
            fi
            if [ -L "${'$'}MARKER" ]; then echo "ownership marker is a symlink"; exit 3; fi

            # 1. Direct valid marker match
            if [ -f "${'$'}MARKER" ]; then
                if [ "${'$'}(cat "${'$'}MARKER" 2>/dev/null)" = "${'$'}OWNER" ]; then
                    echo OK; exit 0
                fi
            fi

            # 2. Safe reclaim: if a valid rootfs is present (reinstall over existing data)
            if [ -x "${'$'}ROOTFS/bin/bash" ] || [ -x "${'$'}ROOTFS/usr/bin/bash" ]; then
                tmp="${'$'}MARKER.tmp.${'$'}${'$'}"
                printf '%s\n' "${'$'}OWNER" > "${'$'}tmp"
                chmod 600 "${'$'}tmp"
                mv -f "${'$'}tmp" "${'$'}MARKER"
                echo "OK:reclaimed_existing_rootfs"; exit 0
            fi

            # 3. Clean up any stale interrupted .install directory
            if [ -d "${'$'}TMP_INSTALL" ] && [ ! -L "${'$'}TMP_INSTALL" ]; then
                rm -rf "${'$'}TMP_INSTALL" 2>/dev/null || true
            fi

            # 4. Check top-level files (allow only DEVCODE staging tarballs, marker, cache)
            for f in ${'$'}(find "${'$'}BASE" -mindepth 1 -maxdepth 2 \( -type f -o -type l \) -print 2>/dev/null || true); do
                rel=${'$'}{f#"${'$'}BASE"/}
                case "${'$'}rel" in
                    .devcode-owner*|staging/*|cache/*|staging|cache) ;;
                    ubuntu/*|ubuntu.install/*) ;;
                    *) echo "unmarked base contains foreign file: ${'$'}rel"; exit 3 ;;
                esac
            done

            # 5. Check top-level directories (allow only DEVCODE structure)
            for d in ${'$'}(find "${'$'}BASE" -mindepth 1 -maxdepth 1 -type d -print 2>/dev/null || true); do
                rel=${'$'}{d#"${'$'}BASE"/}
                case "${'$'}rel" in
                    ubuntu|ubuntu.install|staging|cache) ;;
                    *) echo "unmarked base has unknown directory: ${'$'}rel"; exit 3 ;;
                esac
            done

            # 6. Write ownership marker
            tmp="${'$'}MARKER.tmp.${'$'}${'$'}"
            printf '%s\n' "${'$'}OWNER" > "${'$'}tmp"
            chmod 600 "${'$'}tmp"
            mv -f "${'$'}tmp" "${'$'}MARKER"
            echo OK
        """.trimIndent()
        val (code, output) = su(command, 30_000L)
        if (code != 0) {
            throw IllegalStateException(
                "cannot verify /data/local/devcode ownership: ${output.ifBlank { "unmarked directory contains foreign data" }}"
            )
        }
    }

    // ---------- root caching ----------

    @Volatile private var isRootCached: Boolean? = null
    @Volatile private var rootCacheTime: Long = 0L
    private const val ROOT_CACHE_TTL_MS = 60_000L

    /**
     * Checks whether root is available, using a 60-second in-memory cache to prevent
     * repeating root authorization prompts during frequent navigation or status checks.
     */
    suspend fun checkRoot(forceRefresh: Boolean = false, timeoutMs: Long = 10_000L): Boolean {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = isRootCached
            if (cached != null && (now - rootCacheTime) < ROOT_CACHE_TTL_MS) {
                return cached
            }
        }
        val granted = RootManager.requestRoot(timeoutMs)
        isRootCached = granted
        rootCacheTime = now
        return granted
    }

    // ---------- status ----------

    private fun readUsageBytes(): Long {
        return try {
            val (code, out) = su("du -sk $INSTALL_DIR 2>/dev/null | awk '{print ${'$'}1 * 1024}'")
            if (code != 0) return 0L
            out.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun readAvailBytes(): Long {
        return try {
            val (code, out) = su("df -k $INSTALL_DIR 2>/dev/null || df -k /data 2>/dev/null")
            if (code != 0) return 0L
            val lines = out.trim().lines()
            if (lines.size < 2) return 0L
            lines.last().trim().split(Regex("\\s+")).getOrNull(3)?.toLongOrNull()?.times(1024L) ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Batched status check running a single `su` command to inspect installation,
     * mounts, and storage space in one go. Avoids multiple su process spawns.
     */
    suspend fun refreshStatus(forceTransition: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            val script = """
                R="$INSTALL_DIR"
                if [ -x "${'$'}R/bin/bash" ] || [ -x "${'$'}R/usr/bin/bash" ]; then inst=1; else inst=0; fi
                if grep -Fq "${'$'}R" /proc/mounts 2>/dev/null; then mnt=1; else mnt=0; fi
                used=${'$'}(du -sk "${'$'}R" 2>/dev/null | awk '{print ${'$'}1 * 1024}' || echo 0)
                [ -n "${'$'}used" ] || used=0
                avail=${'$'}((df -k "${'$'}R" 2>/dev/null || df -k /data 2>/dev/null) | tail -1 | awk '{print ${'$'}4 * 1024}' || echo 0)
                [ -n "${'$'}avail" ] || avail=0
                echo "${'$'}inst|${'$'}mnt|${'$'}used|${'$'}avail"
            """.trimIndent()

            val (code, out) = su(script, 10_000L)
            if (code == 0 && out.contains("|")) {
                val parts = out.trim().lines().last().split("|")
                if (parts.size >= 4) {
                    val installed = parts[0] == "1"
                    val used = parts[2].toLongOrNull() ?: 0L
                    val avail = parts[3].toLongOrNull() ?: 0L

                    isRootCached = true
                    rootCacheTime = System.currentTimeMillis()

                    _state.update {
                        val transitional = !forceTransition && (
                            it.status == InstallStatus.DOWNLOADING ||
                            it.status == InstallStatus.VERIFYING ||
                            it.status == InstallStatus.EXTRACTING
                        )
                        it.copy(
                            status = if (transitional && !installed) it.status
                            else if (installed) InstallStatus.INSTALLED
                            else InstallStatus.NOT_INSTALLED,
                            storageUsedBytes = used,
                            storageAvailBytes = avail
                        )
                    }
                    return@withContext
                }
            }

            // Fallback if batch format failed
            val installed = rootfsExists()
            val used = readUsageBytes()
            val avail = readAvailBytes()
            _state.update {
                val transitional = !forceTransition && (
                    it.status == InstallStatus.DOWNLOADING ||
                    it.status == InstallStatus.VERIFYING ||
                    it.status == InstallStatus.EXTRACTING
                )
                it.copy(
                    status = if (transitional && !installed) it.status
                    else if (installed) InstallStatus.INSTALLED
                    else InstallStatus.NOT_INSTALLED,
                    storageUsedBytes = used,
                    storageAvailBytes = avail
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(status = InstallStatus.UNKNOWN, message = "Status check failed: ${e.message}")
            }
        }
    }

    // ---------- download ----------

    suspend fun download(onProgress: (Float) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        try {
            _state.update {
                it.copy(status = InstallStatus.DOWNLOADING, busy = true, downloadProgress = 0f, message = "Downloading Ubuntu base...")
            }

            val partFile = privateTarballPart()
            var existingBytes = if (partFile.exists()) partFile.length() else 0L

            val conn = (URL(ROOTFS_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DevCode/1.0")
                if (existingBytes > 0) setRequestProperty("Range", "bytes=$existingBytes-")
            }

            try {
                conn.connect()
            } catch (e: UnknownHostException) {
                _state.update { it.copy(status = InstallStatus.NOT_INSTALLED, busy = false, message = "No network: ${e.message}") }
                return@withContext false
            } catch (e: Exception) {
                _state.update { it.copy(status = InstallStatus.NOT_INSTALLED, busy = false, message = "Network error: ${e.message}") }
                return@withContext false
            }

            val respCode = try { conn.responseCode } catch (e: Exception) {
                _state.update { it.copy(status = InstallStatus.NOT_INSTALLED, busy = false, message = "Connection error: ${e.message}") }
                return@withContext false
            }

            var append: Boolean
            var totalBytes: Long
            when (respCode) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    val contentRange = conn.getHeaderField("Content-Range")
                    if (contentRange != null && contentRange.startsWith("bytes $existingBytes-", ignoreCase = true)) {
                        append = true
                        totalBytes = contentRange.substringAfterLast("/", "").toLongOrNull() ?: -1L
                    } else {
                        append = false
                        existingBytes = 0L
                        totalBytes = conn.getHeaderFieldLong("Content-Length", -1L)
                    }
                }
                HttpURLConnection.HTTP_OK -> {
                    append = false
                    existingBytes = 0L
                    totalBytes = conn.getHeaderFieldLong("Content-Length", -1L)
                }
                else -> {
                    _state.update { it.copy(status = InstallStatus.NOT_INSTALLED, busy = false, message = "Download failed: HTTP $respCode") }
                    return@withContext false
                }
            }

            if (totalBytes > 0) {
                val avail = readAvailBytes()
                if (avail > 0 && totalBytes > avail) {
                    _state.update {
                        it.copy(status = InstallStatus.NOT_INSTALLED, busy = false, message = "Insufficient storage: need ~$totalBytes bytes, available $avail")
                    }
                    return@withContext false
                }
            }

            try {
                conn.inputStream.use { input ->
                    FileOutputStream(partFile, append).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = existingBytes
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            if (totalBytes > 0) {
                                val p = (downloaded.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
                                _state.update { it.copy(downloadProgress = p) }
                                try { onProgress(p) } catch (_: Exception) {}
                            }
                        }
                        output.flush()
                    }
                }
            } catch (e: Exception) {
                val noSpace = e.message.orEmpty().let {
                    it.contains("ENOSPC", ignoreCase = true) || it.contains("No space", ignoreCase = true)
                }
                _state.update {
                    it.copy(
                        status = InstallStatus.NOT_INSTALLED, busy = false,
                        message = if (noSpace) "Insufficient storage during download" else "Download I/O error: ${e.message}"
                    )
                }
                return@withContext false
            } finally {
                conn.disconnect()
            }

            val finalFile = privateTarball()
            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                _state.update { it.copy(status = InstallStatus.NOT_INSTALLED, busy = false, message = "Failed to finalize archive") }
                return@withContext false
            }

            _state.update { it.copy(downloadProgress = 1f, busy = false, message = "Download complete") }
            try { onProgress(1f) } catch (_: Exception) {}
            true
        } catch (e: Exception) {
            _state.update { it.copy(status = InstallStatus.NOT_INSTALLED, busy = false, message = "Download error: ${e.message}") }
            false
        }
    }

    // ---------- checksum ----------

    /**
     * Parses a SHA256SUMS manifest body and returns the hash for [fileName],
     * or null when no exact match exists.
     */
    fun parseSha256Sums(body: String, fileName: String): String? {
        return body.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val hash = parts[0].trim().lowercase()
            if (!hash.matches(Regex("[0-9a-f]{64}"))) return@mapNotNull null
            val name = parts[1].trim().removePrefix("*")
            if (name == fileName) hash else null
        }.firstOrNull()
    }

    suspend fun verifySha256(): Boolean = withContext(Dispatchers.IO) {
        try {
            _state.update { it.copy(status = InstallStatus.VERIFYING, busy = true, message = "Verifying SHA-256...") }

            val tarball = privateTarball()
            if (!tarball.exists() || tarball.length() == 0L) {
                _state.update { it.copy(status = InstallStatus.NOT_INSTALLED, busy = false, message = "No archive to verify") }
                return@withContext false
            }

            val expectedHash: String? = try {
                val conn = (URL(SHA256SUMS_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "DevCode/1.0")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseSha256Sums(body, TARBALL_FILENAME)
            } catch (e: UnknownHostException) {
                _state.update { it.copy(status = InstallStatus.UNKNOWN, busy = false, message = "Network error: ${e.message}") }
                return@withContext false
            } catch (e: Exception) {
                _state.update { it.copy(status = InstallStatus.UNKNOWN, busy = false, message = "SHA256SUMS fetch failed: ${e.message}") }
                return@withContext false
            }

            if (expectedHash.isNullOrBlank()) {
                _state.update { it.copy(status = InstallStatus.UNKNOWN, busy = false, message = "Checksum for $TARBALL_FILENAME not in manifest") }
                return@withContext false
            }

            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(tarball).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }

            if (actualHash.equals(expectedHash, ignoreCase = true)) {
                _state.update { it.copy(busy = false, message = "SHA-256 verified") }
                true
            } else {
                _state.update {
                    it.copy(status = InstallStatus.CORRUPT, busy = false, message = "Checksum mismatch")
                }
                false
            }
        } catch (e: Exception) {
            _state.update { it.copy(status = InstallStatus.UNKNOWN, busy = false, message = "Verify error: ${e.message}") }
            false
        }
    }

    // ---------- extract ----------

    /**
     * Copies the verified archive to root staging, extracts to a temp dir,
     * bootstraps the coder user, then atomically renames into [INSTALL_DIR].
     */
    suspend fun extract(): Boolean = withContext(Dispatchers.IO) {
        try {
            _state.update { it.copy(status = InstallStatus.EXTRACTING, busy = true) }
            logStep("Step 1/5: Preparing staging and directory safety...")

            val src = privateTarball()
            if (!src.exists() || src.length() == 0L) {
                logStep("FAILED: No verified archive to extract")
                _state.update { it.copy(status = InstallStatus.CORRUPT, busy = false, message = "No archive to extract") }
                return@withContext false
            }

            ensureOwnedBase()

            val (cpCode, cpOut) = su(
                "mkdir -p $STAGING_DIR $CACHE_DIR && cp '${src.absolutePath}' $TARBALL && chmod 644 $TARBALL",
                300_000L
            )
            if (cpCode != 0) {
                logStep("FAILED: Staging copy error: $cpOut")
                _state.update { it.copy(status = InstallStatus.CORRUPT, busy = false, message = "Staging copy failed: $cpOut") }
                return@withContext false
            }

            val tmpDir = "${INSTALL_DIR}.install"
            ChrootManager.unmountTargetLocked(tmpDir, force = true)
            val (rmCode, rmOut) = su("rm -rf $tmpDir && mkdir -p $tmpDir", 120_000L)
            if (rmCode != 0) {
                logStep("FAILED: Cannot prepare install directory: $rmOut")
                _state.update { it.copy(status = InstallStatus.CORRUPT, busy = false, message = "Cannot prepare install dir: $rmOut") }
                return@withContext false
            }

            logStep("Step 2/5: Unpacking Ubuntu 24.04 ARM64 rootfs...")

            var (exCode, exOut) = su("tar -xzf $TARBALL -C $tmpDir", 1_800_000L)
            if (exCode != 0) {
                // Fallback: pipe through gzip for toybox compatibility
                val fb = su("gzip -dc $TARBALL | tar -xf - -C $tmpDir", 1_800_000L)
                exCode = fb.first
                exOut = fb.second
            }
            if (exCode != 0) {
                ChrootManager.unmountTargetLocked(tmpDir, force = true)
                su("rm -rf $tmpDir")
                logStep("FAILED: Extraction failed: ${exOut.ifBlank { "code $exCode" }}")
                _state.update { it.copy(status = InstallStatus.CORRUPT, busy = false, message = "Extraction failed: ${exOut.ifBlank { "code $exCode" }}") }
                return@withContext false
            }

            val (valCode, _) = su("test -x $tmpDir/bin/bash || test -x $tmpDir/usr/bin/bash")
            if (valCode != 0) {
                ChrootManager.unmountTargetLocked(tmpDir, force = true)
                su("rm -rf $tmpDir")
                logStep("FAILED: Invalid rootfs: bash binary missing")
                _state.update { it.copy(status = InstallStatus.CORRUPT, busy = false, message = "Invalid rootfs: bash binary missing") }
                return@withContext false
            }

            logStep("Step 3/5: Provisioning DNS, user coder & passwordless sudo...")

            // Direct host-side provisioning: DNS, user coder (UID 1000), GIDs (3003, 3004), workspace & shell config
            val bootOk = try {
                SetupWizard.bootstrap(tmpDir)
            } catch (e: Exception) {
                logStep("FAILED: Bootstrap exception: ${e.message}")
                _state.update { it.copy(message = "Bootstrap exception: ${e.message}") }
                false
            }

            if (!bootOk) {
                su("rm -rf $tmpDir")
                logStep("FAILED: Unable to configure user, DNS or sudo")
                _state.update { it.copy(status = InstallStatus.CORRUPT, busy = false, message = "Bootstrap failed: unable to write user or DNS configuration") }
                return@withContext false
            }

            logStep("Step 4/5: Deploying devcode CLI management tool...")
            ChrootManager.deployDevcodeCli()

            logStep("Step 5/5: Activating Ubuntu rootfs to $INSTALL_DIR...")
            ChrootManager.unmountTargetLocked(INSTALL_DIR, force = true)
            val (mvCode, mvOut) = su("rm -rf $INSTALL_DIR && mv $tmpDir $INSTALL_DIR", 120_000L)
            if (mvCode != 0) {
                su("rm -rf $tmpDir")
                logStep("FAILED: Activation failed: $mvOut")
                _state.update { it.copy(status = InstallStatus.CORRUPT, busy = false, message = "Activation failed: $mvOut") }
                return@withContext false
            }

            // Clean staging tarball
            su("rm -f $TARBALL")

            logStep("SUCCESS: Ubuntu 24.04 ARM64 installed and configured successfully!")
            _state.update { it.copy(busy = false, message = "Installation complete and verified") }
            true
        } catch (e: Exception) {
            logStep("FAILED: Extract error: ${e.message}")
            _state.update { it.copy(status = InstallStatus.CORRUPT, busy = false, message = "Extract error: ${e.message}") }
            false
        }
    }

    // ---------- install / repair / remove ----------

    suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        ChrootManager.withEnvironmentLock {
            try {
                _state.update { it.copy(busy = true) }
                requireIdle()
                if (rootfsExists()) {
                    _state.update { it.copy(busy = false, message = "Ubuntu already installed. Use Repair or Remove first.") }
                    return@withEnvironmentLock false
                }
                if (!download()) return@withEnvironmentLock false
                if (!verifySha256()) return@withEnvironmentLock false
                if (!extract()) return@withEnvironmentLock false

                try { privateTarball().delete() } catch (_: Exception) {}
                try { privateTarballPart().delete() } catch (_: Exception) {}

                refreshStatus(forceTransition = true)
                val ok = rootfsExists() || _state.value.status == InstallStatus.INSTALLED
                _state.update {
                    it.copy(
                        status = if (ok) InstallStatus.INSTALLED else InstallStatus.NOT_INSTALLED,
                        busy = false,
                        downloadProgress = 1f,
                        message = if (ok) "Ubuntu 24.04 ARM64 installed and ready!" else "Installation validation failed"
                    )
                }
                ok
            } catch (e: Exception) {
                _state.update { it.copy(status = InstallStatus.NOT_INSTALLED, busy = false, message = "Install error: ${e.message}") }
                false
            }
        }
    }

    /**
     * Non-destructive repair: re-runs bootstrap (user/DNS/workspace/shell)
     * on the EXISTING rootfs. Never deletes or replaces user data.
     */
    suspend fun repair(): Boolean = withContext(Dispatchers.IO) {
        return@withContext ChrootManager.withEnvironmentLock {
            try {
                _state.update { it.copy(busy = true, message = "Repairing Ubuntu configuration...") }
                requireIdle()
                if (!rootfsExists()) {
                    _state.update { it.copy(busy = false, message = "Nothing to repair: Ubuntu not installed") }
                    return@withEnvironmentLock false
                }
                if (!SetupWizard.bootstrap()) {
                    _state.update { it.copy(busy = false, message = "Repair failed: bootstrap error") }
                    return@withEnvironmentLock false
                }
                refreshStatus(forceTransition = true)
                _state.update { it.copy(status = InstallStatus.INSTALLED, busy = false, message = "Repair complete: user/config restored, data preserved") }
                true
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "Repair error: ${e.message}") }
                false
            }
        }
    }

    /**
     * Removes exactly the owned rootfs after idle + ownership checks.
     */
    suspend fun remove(): Boolean = withContext(Dispatchers.IO) {
        return@withContext uninstallCleanly()
    }

    /**
     * Executes clean uninstall via devcode CLI: stops all sessions, safely unmounts all
     * virtual filesystems, and completely removes /data/local/devcode without leaving zombies.
     */
    suspend fun uninstallCleanly(): Boolean = withContext(Dispatchers.IO) {
        return@withContext ChrootManager.withEnvironmentLock {
            try {
                _state.update { it.copy(busy = true, message = "Uninstalling DEVCODE cleanly...") }
                ChrootManager.deployDevcodeCli()
                val (code, out) = su("/data/local/devcode/bin/devcode uninstall", 120_000L)
                try { privateTarball().delete() } catch (_: Exception) {}
                try { privateTarballPart().delete() } catch (_: Exception) {}

                _state.update {
                    it.copy(
                        status = InstallStatus.NOT_INSTALLED,
                        busy = false,
                        downloadProgress = 0f,
                        storageUsedBytes = 0L,
                        message = if (code == 0) "DEVCODE uninstalled cleanly" else "Uninstall warning: $out"
                    )
                }
                refreshStatus(forceTransition = true)
                code == 0
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "Uninstall error: ${e.message}") }
                false
            }
        }
    }

    /**
     * Emergency fallback removal: deletes the rootfs and staging files if extraction
     * was corrupted and ownership check fails, provided no chroot session or mount is active.
     */
    suspend fun forceRemove(): Boolean = withContext(Dispatchers.IO) {
        return@withContext ChrootManager.withEnvironmentLock {
            try {
                _state.update { it.copy(busy = true, message = "Force cleaning corrupted rootfs...") }
                requireIdle()
                ChrootManager.unmountTargetLocked(INSTALL_DIR, force = true)
                ChrootManager.unmountTargetLocked("${INSTALL_DIR}.install", force = true)

                val (code, _) = su(
                    "rm -rf $INSTALL_DIR ${INSTALL_DIR}.install $TARBALL $STAGING_DIR $CACHE_DIR",
                    300_000L
                )
                try { privateTarball().delete() } catch (_: Exception) {}
                try { privateTarballPart().delete() } catch (_: Exception) {}

                _state.update { it.copy(busy = false, downloadProgress = 0f, message = "Cleaned corrupted rootfs") }
                refreshStatus()
                code == 0
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "Force remove failed: ${e.message}") }
                false
            }
        }
    }
}
