package dev.kiro.android.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.kiro.android.KiroApp
import dev.kiro.android.R
import dev.kiro.android.ServiceLocator

/**
 * Keeps a session's socket alive while a turn is running.
 *
 * The item that decides whether the app is trustworthy: a session that dies when
 * the phone locks is a broken client regardless of how good the transcript looks.
 *
 * **Android 15 caps `dataSync` at 6 hours per 24**, and `onTimeout` is not
 * optional — a service that ignores it is killed with an ANR-style crash. Long
 * autonomous runs will hit this, so the ceiling is handled as a normal state:
 * stop foregrounding, tell the user plainly, and fall back to push (F-16) for
 * anything that still needs their attention.
 */
class SessionConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        startForegroundCompat(buildNotification(sessionId))
        // START_REDELIVER_INTENT: if the process is killed and restarted we want
        // the session id back, not a service with nothing to reconnect to.
        return START_REDELIVER_INTENT
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * The 6h/24h ceiling, reached.
     *
     * Must call `stopSelf` promptly. Silently disappearing would leave the user
     * believing a long run is still being watched when it is not, so the
     * notification is replaced with one that says what happened.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        notifyTimeoutReached()
        stopSelf()
    }

    private fun buildNotification(sessionId: String?): Notification =
        NotificationCompat.Builder(this, KiroApp.CHANNEL_SERVICE)
            .setContentTitle("Session running")
            .setContentText(
                sessionId?.let { "Streaming updates for ${it.take(SHORT_ID_LENGTH)}…" }
                    ?: "Streaming updates",
            )
            .setSmallIcon(R.drawable.ic_stat_kiro)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun notifyTimeoutReached() {
        val notification = NotificationCompat.Builder(this, KiroApp.CHANNEL_TURNS)
            .setContentTitle("Stopped watching this session")
            .setContentText(
                "Android limits background connections to 6 hours a day. The session " +
                    "is still running in Kiro's sandbox — open the app to catch up.",
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Android limits background connections to 6 hours a day, and this " +
                        "one reached it. The session keeps running in Kiro's sandbox; " +
                        "open the app to reconnect and see what happened.",
                ),
            )
            .setSmallIcon(R.drawable.ic_stat_kiro)
            .build()
        androidx.core.app.NotificationManagerCompat.from(this)
            .takeIf {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            ?.notify(TIMEOUT_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        ServiceLocator.scope.let { /* the gateway outlives the service by design */ }
    }

    companion object {
        const val EXTRA_SESSION_ID = "sessionId"
        private const val NOTIFICATION_ID = 1
        private const val TIMEOUT_NOTIFICATION_ID = 2
        private const val SHORT_ID_LENGTH = 8

        fun start(context: Context, sessionId: String) {
            context.startForegroundService(
                Intent(context, SessionConnectionService::class.java)
                    .putExtra(EXTRA_SESSION_ID, sessionId),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionConnectionService::class.java))
        }
    }
}
