package com.devcode.terminal.core.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.devcode.terminal.core.logging.AppLogger
import com.devcode.terminal.core.root.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val tagName: String,
    val releaseName: String,
    val releaseBody: String,
    val buildNumber: Int,
    val assetUrl: String,
    val browserDownloadUrl: String,
    val assetSize: Long,
)

enum class UpdateState {
    IDLE,
    CHECKING,
    AVAILABLE,
    UP_TO_DATE,
    DOWNLOADING,
    READY_TO_INSTALL,
    INSTALLING,
    ERROR
}

data class UpdaterStatus(
    val state: UpdateState = UpdateState.IDLE,
    val updateInfo: UpdateInfo? = null,
    val progress: Float = 0f,
    val message: String = ""
)

/**
 * Manages GitHub Releases update checking, APK downloading, and
 * seamless 1-click root install (`pm install -r`) or PackageInstaller intent.
 */
object UpdateManager {

    private const val GITHUB_API_LATEST = "https://api.github.com/repos/arbdevai/DEVCODE/releases/latest"

    private val _status = MutableStateFlow(UpdaterStatus())
    val status = _status.asStateFlow()

    fun getAppVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: Exception) {
            1
        }
    }

    suspend fun checkForUpdates(context: Context, customToken: String? = null): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            _status.value = UpdaterStatus(state = UpdateState.CHECKING, message = "Checking for latest release...")
            val currentCode = getAppVersionCode(context)

            val conn = (URL(GITHUB_API_LATEST).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DEVCODE-Updater/1.0")
                if (!customToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer ${customToken.trim()}")
                }
            }

            val respCode = conn.responseCode
            if (respCode != 200) {
                _status.value = UpdaterStatus(
                    state = UpdateState.ERROR,
                    message = "GitHub API response: HTTP $respCode"
                )
                return@withContext null
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "")
            val name = json.optString("name", tagName)
            val releaseNotes = json.optString("body", "").ifBlank { "Regular bug fixes and performance improvements." }

            // Parse build number from tag (e.g. "build-20" -> 20)
            val buildNum = Regex("""\d+""").find(tagName)?.value?.toIntOrNull() ?: 0

            val assets = json.optJSONArray("assets")
            var assetUrl = ""
            var browserDownloadUrl = ""
            var assetSize = 0L

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val aName = a.optString("name", "")
                    if (aName.endsWith(".apk", ignoreCase = true)) {
                        assetUrl = a.optString("url", "")
                        browserDownloadUrl = a.optString("browser_download_url", "")
                        assetSize = a.optLong("size", 0L)
                        break
                    }
                }
            }

            val info = UpdateInfo(
                tagName = tagName,
                releaseName = name,
                releaseBody = releaseNotes,
                buildNumber = buildNum,
                assetUrl = assetUrl,
                browserDownloadUrl = browserDownloadUrl,
                assetSize = assetSize
            )

            if (buildNum > currentCode) {
                AppLogger.log("UPDATE", "Update available: $name (Build $buildNum)")
                _status.value = UpdaterStatus(
                    state = UpdateState.AVAILABLE,
                    updateInfo = info,
                    message = "Update available: $name (Build $buildNum)"
                )
                info
            } else {
                AppLogger.log("UPDATE", "DEVCODE is up to date (Build $currentCode)")
                _status.value = UpdaterStatus(
                    state = UpdateState.UP_TO_DATE,
                    updateInfo = info,
                    message = "DEVCODE is up to date (Build $currentCode)."
                )
                null
            }
        } catch (e: Exception) {
            AppLogger.error("UPDATE", "Update check failed: ${e.message}")
            _status.value = UpdaterStatus(
                state = UpdateState.ERROR,
                message = "Update check failed: ${e.message}"
            )
            null
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        customToken: String? = null,
        preferRootInstall: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            _status.value = UpdaterStatus(
                state = UpdateState.DOWNLOADING,
                updateInfo = info,
                progress = 0f,
                message = "Connecting to download server..."
            )

            // For public repos, browserDownloadUrl is directly accessible
            val targetUrl = if (info.browserDownloadUrl.isNotBlank()) info.browserDownloadUrl else info.assetUrl

            var conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DEVCODE-Updater/1.0")
                if (!customToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer ${customToken.trim()}")
                }
            }

            var respCode = conn.responseCode
            var redirects = 0
            while ((respCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    respCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    respCode == 307 || respCode == 308) && redirects < 5) {
                val redirectUrl = conn.getHeaderField("Location")
                conn.disconnect()
                conn = (URL(redirectUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "DEVCODE-Updater/1.0")
                }
                respCode = conn.responseCode
                redirects++
            }

            if (respCode !in 200..299) {
                _status.value = UpdaterStatus(
                    state = UpdateState.ERROR,
                    message = "Download failed: HTTP $respCode"
                )
                return@withContext false
            }

            val totalBytes = conn.contentLengthLong.let { if (it > 0) it else info.assetSize }

            val updateFile = File(context.cacheDir, "devcode-update.apk")
            if (updateFile.exists()) updateFile.delete()

            conn.inputStream.use { input ->
                FileOutputStream(updateFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var readBytes = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        readBytes += n
                        if (totalBytes > 0) {
                            val p = (readBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            _status.value = _status.value.copy(
                                progress = p,
                                message = "Downloading: ${(p * 100).toInt()}% (${readBytes / (1024 * 1024)}MB / ${totalBytes / (1024 * 1024)}MB)"
                            )
                        }
                    }
                    output.flush()
                }
            }
            conn.disconnect()

            _status.value = _status.value.copy(
                state = UpdateState.INSTALLING,
                progress = 1f,
                message = "Installing update with root package manager..."
            )

            // Method 1: Seamless Root Install (1-Click Silent Install on Rooted Android)
            if (preferRootInstall) {
                AppLogger.log("UPDATE", "Attempting 1-click silent install via pm install -r -d...")
                val copyCmd = "cp '${updateFile.absolutePath}' /data/local/tmp/devcode-update.apk && chmod 644 /data/local/tmp/devcode-update.apk"
                val cpResult = RootManager.runAsRoot(copyCmd, 15_000L)
                if (cpResult.isSuccess) {
                    val pmCmd = "pm install -r -d /data/local/tmp/devcode-update.apk"
                    val pmResult = RootManager.runAsRoot(pmCmd, 60_000L)
                    if (pmResult.isSuccess && pmResult.stdout.contains("Success", ignoreCase = true)) {
                        AppLogger.log("UPDATE", "Silent root install succeeded! App updated to Build ${info.buildNumber}")
                        _status.value = UpdaterStatus(
                            state = UpdateState.UP_TO_DATE,
                            message = "Update installed successfully via root!"
                        )
                        RootManager.runAsRoot("rm -f /data/local/tmp/devcode-update.apk", 5_000L)
                        return@withContext true
                    } else {
                        AppLogger.error("UPDATE", "pm install returned: ${pmResult.stderr.ifBlank { pmResult.stdout }}")
                    }
                }
            }

            // Method 2: Standard Android PackageInstaller Intent via FileProvider
            AppLogger.log("UPDATE", "Launching PackageInstaller Intent via FileProvider...")
            _status.value = _status.value.copy(message = "Launching package installer...")
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                updateFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)

            _status.value = UpdaterStatus(
                state = UpdateState.READY_TO_INSTALL,
                message = "Package installer opened. Please confirm install on screen."
            )
            true
        } catch (e: Exception) {
            _status.value = UpdaterStatus(
                state = UpdateState.ERROR,
                message = "Install error: ${e.message}"
            )
            false
        }
    }
}
