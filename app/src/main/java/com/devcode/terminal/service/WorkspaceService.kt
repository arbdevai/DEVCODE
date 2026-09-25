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
import androidx.core.app.NotificationCompat
import com.devcode.terminal.MainActivity
import com.devcode.terminal.core.chroot.ChrootManager
import com.devcode.terminal.core.terminal.TerminalManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps terminal sessions and Ubuntu chroot processes
 * alive when the user switches apps or turns off the screen.
 *
 * Provides a dynamic statusbar notification showing running sessions and a 1-tap
 * "EXIT & KILL ALL" action directly from the Android notification drawer.
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

        // Dynamically update notification text based on active terminal sessions
        serviceScope.launch {
            TerminalManager.sessions.collect { sessions ->
                val text = if (sessions.isNotEmpty()) {
                    val titles = sessions.take(2).joinToString(", ") { it.title }
                    val suffix = if (sessions.size > 2) " (+${sessions.size - 2})" else ""
                    "${sessions.size} session(s) active: $titles$suffix"
                } else {
                    "Ubuntu 24.04 ARM64 Idle • Ready"
                }
                val notification = buildNotification(text)
                val manager = getSystemService(NotificationManager::class.java)
                manager?.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_KILL_ALL -> {
                serviceScope.launch {
                    try {
                        TerminalManager.sessions.value.toList().forEach { session ->
                            try {
                                session.stop()
                                TerminalManager.closeSession(session.id)
                            } catch (_: Throwable) {}
                        }
                        ChrootManager.stopAllCleanly()
                    } catch (_: Throwable) {}
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val initialNotif = buildNotification("DEVCODE Workstation active")
        startForeground(NOTIFICATION_ID, initialNotif)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            serviceScope.launch {
                try {
                    ChrootManager.listSessions()
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
                description = "Linux workspace session notifications and controls"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val killIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WorkspaceService::class.java).apply {
                action = ACTION_KILL_ALL
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DEVCODE Workstation")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "EXIT & KILL ALL",
                killIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
