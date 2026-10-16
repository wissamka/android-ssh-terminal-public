package dev.wissam.sshterm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.wissam.sshterm.R
import dev.wissam.sshterm.ui.MainActivity

/**
 * Raises process priority while user-initiated terminal sessions are open; survival is not guaranteed.
 * Android kills background
 * network I/O aggressively — more so on OEM battery managers — so every active session runs under
 * this foreground service with a persistent low-priority notification. Started/stopped by
 * SshSessionManager as the session count moves between 0 and 1.
 */
@dagger.hilt.android.AndroidEntryPoint
class SshForegroundService : Service() {

    @javax.inject.Inject lateinit var sessions: dev.wissam.sshterm.core.ssh.SshSessionManager

    private var generation: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedGeneration = intent?.getLongExtra(EXTRA_GENERATION, 0L) ?: 0L
        if (sessions.ownsServiceGeneration(requestedGeneration)) generation = requestedGeneration
        val count = sessions.liveSessionCount
        createChannel()
        val notification = buildNotification(count)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (count == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        // If the system kills us anyway, don't auto-restart with no sessions to show; the UI
        // surfaces a "session ended, tap to reconnect" state instead of a zombie notification.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) = shutdown()

    override fun onTimeout(startId: Int, fgsType: Int) = shutdown()

    private fun shutdown() {
        // Meet the platform deadline before scheduling transport teardown or journal work.
        shutdownSessionService(
            removeNotification = { stopForeground(STOP_FOREGROUND_REMOVE) },
            stopService = { stopSelf() },
            endSessions = { generation?.let(sessions::serviceStopped) },
        )
    }

    override fun onDestroy() {
        generation?.let(sessions::serviceStopped)
        super.onDestroy()
    }

    private fun buildNotification(sessionCount: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (sessionCount == 1) "1 active session" else "$sessionCount active sessions"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_terminal)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_sessions),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    companion object {
        const val EXTRA_SESSION_COUNT = "session_count"
        const val EXTRA_GENERATION = "service_generation"
        private const val CHANNEL_ID = "ssh_sessions"
        private const val NOTIFICATION_ID = 1
    }
}
