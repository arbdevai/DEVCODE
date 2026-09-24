package com.devcode.terminal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.devcode.terminal.core.chroot.ChrootManager
import androidx.core.app.NotificationCompat
import com.devcode.terminal.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps terminal sessions and Ubuntu chroot processes
 * alive when the user switches apps or turns off the screen.
 */
class WorkspaceService : Service() {

    companion object {
        private const val CHANNEL_ID = "devcode_workspace"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.devcode.terminal.START_SERVICE"
        const val ACTION_STOP = "com.devcode.terminal.STOP_SERVICE"
        const val ACTION_KILL_ALL = "com.devcode.terminal.KILL_ALL"

        fun killAllSessionsAndUnmount(context: Context) {
            stop(context)
        }

        fun start(context: Context) {
            val intent = Intent(context, WorkspaceService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, WorkspaceService::class.java))
            } catch (_: Throwable) {}
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent?.action == ACTION_KILL_ALL) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification("DEVCODE Linux session active")
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // When app task is swiped away from recent apps, KEEP the background service alive
        // as requested by user ("kalau cuma close apk tetap berjalan normal").
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            // Trim dead sessions under memory pressure
            serviceScope.launch {
                try {
                    ChrootManager.listSessions() // internally prunes dead processes
                } catch (_: Throwable) {}
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DEVCODE Workspace",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Linux workspace session notifications"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DEVCODE Workstation")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
