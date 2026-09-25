package com.devcode.terminal.core.logging

import android.content.Context
import android.os.Build
import com.devcode.terminal.core.chroot.ChrootManager
import com.devcode.terminal.core.root.RootManager
import com.devcode.terminal.core.ubuntu.UbuntuManager
import com.devcode.terminal.core.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: String,
    val tag: String,
    val message: String,
    val isError: Boolean = false
)

/**
 * Centralized diagnostic logger for DEVCODE.
 * Records all system events, mount operations, terminal launches, sudo executions,
 * and updater progress into a structured ring-buffer that users can inspect and
 * copy to clipboard with a single tap for seamless troubleshooting.
 */
object AppLogger {

    private const val MAX_LOG_ENTRIES = 200
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries = _entries.asStateFlow()

    fun log(tag: String, message: String, isError: Boolean = false) {
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            tag = tag.uppercase(Locale.US),
            message = message,
            isError = isError
        )
        _entries.update { list ->
            (list + entry).takeLast(MAX_LOG_ENTRIES)
        }
    }

    fun error(tag: String, message: String) = log(tag, message, isError = true)

    fun clear() {
        _entries.value = emptyList()
    }

    /**
     * Compiles an exhaustive, professional system diagnostic report
     * ready to be copied to clipboard and shared for immediate debugging.
     */
    suspend fun generateDiagnosticReport(context: Context): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val dateStr = dateFormat.format(Date())
        val versionCode = UpdateManager.getAppVersionCode(context)

        sb.appendLine("==================================================")
        sb.appendLine("           DEVCODE SYSTEM DIAGNOSTIC REPORT       ")
        sb.appendLine("==================================================")
        sb.appendLine("Generated At   : $dateStr")
        sb.appendLine("App Package    : ${context.packageName}")
        sb.appendLine("App Version    : 1.0.$versionCode (Build $versionCode)")
        sb.appendLine("Android OS     : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Device Model   : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Architecture   : ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine("Linux Kernel   : ${System.getProperty("os.version") ?: "unknown"}")

        sb.appendLine("\n--- PRIVILEGES & HOST RUNTIME ---")
        val isRooted = RootManager.isRooted()
        sb.appendLine("Superuser (su) : ${if (isRooted) "GRANTED (uid=0)" else "DENIED / UNAVAILABLE"}")

        val chrootExe = ChrootManager.getChrootExecutable()
        sb.appendLine("Chroot Binary  : $chrootExe")

        sb.appendLine("\n--- UBUNTU CHROOT & STORAGE ---")
        val uState = UbuntuManager.state.value
        sb.appendLine("Status         : ${uState.status.name}")
        val usedMb = uState.storageUsedBytes / (1024L * 1024L)
        val availMb = uState.storageAvailBytes / (1024L * 1024L)
        sb.appendLine("Rootfs Size    : $usedMb MB")
        sb.appendLine("Available Disk : $availMb MB")

        sb.appendLine("\n--- ACTIVE DEVCODE VFS MOUNTS ---")
        val mountsResult = RootManager.runAsRoot("grep -F '/data/local/devcode' /proc/mounts 2>/dev/null", 5_000L)
        val mounts = mountsResult.stdout.trim()
        if (mounts.isNotBlank()) {
            mounts.lines().forEach { sb.appendLine("  $it") }
        } else {
            sb.appendLine("  None (Filesystems cleanly unmounted)")
        }

        sb.appendLine("\n--- ACTIVE SESSIONS & PIDs ---")
        val pidsResult = RootManager.runAsRoot("cat /data/local/devcode/ubuntu/run/devcode/sessions/*.pid 2>/dev/null", 5_000L)
        val pids = pidsResult.stdout.trim()
        sb.appendLine("Session PIDs   : ${if (pids.isNotBlank()) pids else "None"}")

        sb.appendLine("\n--- CHRONOLOGICAL SYSTEM LOGS ---")
        val currentLogs = _entries.value
        if (currentLogs.isNotEmpty()) {
            currentLogs.forEach { entry ->
                val prefix = if (entry.isError) "[ERROR]" else "[INFO] "
                sb.appendLine("${entry.timestamp} $prefix [${entry.tag}] ${entry.message}")
            }
        } else {
            sb.appendLine("No logged events captured yet.")
        }
        sb.appendLine("==================================================")
        sb.appendLine("               END OF DIAGNOSTIC REPORT           ")
        sb.appendLine("==================================================")

        sb.toString()
    }
}
