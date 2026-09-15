package com.devcode.terminal.core.ubuntu

import android.content.Context
import com.devcode.terminal.core.chroot.ChrootManager
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
        val busy: Boolean = false
    )

    private val _state = MutableStateFlow(UbuntuState())
    val state: StateFlow<UbuntuState> = _state.asStateFlow()

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
        val (code, _) = su("test -x $INSTALL_DIR/bin/bash")
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
     * Creates the base only when absent; never adopts unknown content silently.
     */
    private fun ensureOwnedBase() {
        val (mCode, mOut) = su(
            "if [ -L /data/local/devcode ]; then echo SYMLINK; exit 3; fi; " +
                "if [ ! -e /data/local/devcode ]; then mkdir -p /data/local/devcode && echo '$OWNER_VALUE' > $OWNER_MARKER && echo CREATED; exit 0; fi; " +
                "cat $OWNER_MARKER 2>/dev/null"
        )
        if (mCode != 0) throw IllegalStateException("cannot verify base ownership: $mOut")
        val marker = mOut.trim().lines().lastOrNull().orEmpty()
        if (marker != OWNER_VALUE && marker != "CREATED") {
            throw IllegalStateException("base dir not owned by DEVCODE; refusing to modify")
        }
    }

    private fun readUsageBytes(): Long {
        return try {
            val (code, out) = su("du -sb $INSTALL_DIR 2>/dev/null")
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

    // ---------- status ----------

    suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        try {
            val installed = rootfsExists()
            val used = readUsageBytes()
            val avail = readAvailBytes()
            _state.update {
                val transitional = it.status == InstallStatus.DOWNLOADING ||
                    it.status == InstallStatus.VERIFYING ||
                    it.status == InstallStatus.EXTRACTING
                it.copy(
                    status = if (transitional) it.status
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
            _state.update { it.copy(status = InstallStatus.EXTRACTING, busy = true, message = "Extracting rootfs...") }

            val src = privateTarball()
            if (!src.exists() || src.length() == 0L) {
                _state.update { it.copy(status = InstallStatus.CORRUPT, busy = false, message = "No archive to extract") }
                return@withContext false
            }

            ensureOwnedBase()

            val (cpCode, cpOut) = su(
                "mkdir -p $STAGING_DIR $CACHE_DIR && cp '${src.absolutePath}' $TARBALL && chmod 644 $TARBALL",
                300_000L
            )
            if (cpCode != 0) {
                _state.update { it.copy(status = InstallStatus.CORRUPT, busy = false, message = "Staging copy failed") }
                return@withContext false
            }

            val tmpDir = "${INSTALL_DIR}.install"
            val (rmCode, _) = su("rm -rf $tmpDir && mkdir -p $tmpDir", 120_000L)
            if (rmCode != 0) {
                _state.update { it.copy(status = InstallStatus.CORRUPT, busy = false, message = "Cannot prepare install dir") }
                return@withContext false
            }

            val (exCode, _) = su("tar -xzf $TARBALL -C $tmpDir", 1_800_000L)
            if (exCode != 0) {
                su("rm -rf $tmpDir")
                _state.update { it.copy(status = InstallStatus.CORRUPT, busy = false, message = "Extraction failed") }
                return@withContext false
            }

            val (valCode, _) = su("test -x $tmpDir/bin/bash")
            if (valCode != 0) {
                su("rm -rf $tmpDir")
                _state.update { it.copy(status = InstallStatus.CORRUPT, busy = false, message = "Invalid rootfs: /bin/bash missing") }
                return@withContext false
            }

            // Mandatory bootstrap: coder user, DNS, workspace, shell config
            if (!SetupWizard.bootstrap(tmpDir)) {
                su("rm -rf $tmpDir")
                _state.update { it.copy(status = InstallStatus.CORRUPT, busy = false, message = "Bootstrap failed") }
                return@withContext false
            }

            val (mvCode, _) = su("mv $tmpDir $INSTALL_DIR", 120_000L)
            if (mvCode != 0) {
                su("rm -rf $tmpDir")
                _state.update { it.copy(status = InstallStatus.CORRUPT, busy = false, message = "Activation failed") }
                return@withContext false
            }

            _state.update { it.copy(busy = false, message = "Extraction complete") }
            true
        } catch (e: Exception) {
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

                refreshStatus()
                val ok = _state.value.status == InstallStatus.INSTALLED
                _state.update { it.copy(busy = false, message = if (ok) "Ubuntu 24.04 ARM64 installed" else "Install finished but validation failed") }
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
                refreshStatus()
                _state.update { it.copy(busy = false, message = "Repair complete: user/config restored, data preserved") }
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
        return@withContext ChrootManager.withEnvironmentLock {
            try {
                _state.update { it.copy(busy = true) }
                requireIdle()
                ensureOwnedBase()

                val (code, _) = su(
                    "if [ -L $INSTALL_DIR ]; then echo SYMLINK; exit 3; fi; " +
                        "rm -rf $INSTALL_DIR ${INSTALL_DIR}.install $TARBALL",
                    300_000L
                )
                if (code != 0) {
                    _state.update { it.copy(busy = false, message = "Remove failed: safety check") }
                    return@withEnvironmentLock false
                }
                try { privateTarball().delete() } catch (_: Exception) {}
                try { privateTarballPart().delete() } catch (_: Exception) {}

                _state.update { it.copy(busy = false, downloadProgress = 0f, message = "Ubuntu rootfs removed") }
                refreshStatus()
                true
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "Remove error: ${e.message}") }
                false
            }
        }
    }
}
