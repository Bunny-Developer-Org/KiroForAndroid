package dev.kiro.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class KiroApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.install(this)
        createNotificationChannels()
    }

    /**
     * Two channels, not one, and this is a requirement rather than a nicety: a
     * user who silences "your agent finished" must not thereby silence "your agent
     * is blocked waiting for you" (F-16). Approvals are the highest-stakes
     * notification the app sends.
     */
    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_APPROVALS,
                "Approval requests",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "The agent is blocked and needs your decision."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TURNS,
                "Turn completion",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "A session finished working."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "Active session",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while a session is streaming."
            },
        )
    }

    companion object {
        const val CHANNEL_APPROVALS = "approvals"
        const val CHANNEL_TURNS = "turns"
        const val CHANNEL_SERVICE = "session_service"
    }
}
