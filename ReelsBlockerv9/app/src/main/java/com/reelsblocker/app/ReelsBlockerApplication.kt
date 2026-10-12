package com.reelsblocker.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.reelsblocker.app.util.Constants

/**
 * Creates the notification channel the single instant the process starts,
 * so it is guaranteed to exist before [com.reelsblocker.app.service.MonitorForegroundService]
 * or the accessibility service ever tries to post to it. minSdk is 26, so
 * NotificationChannel is always available here — no SDK_INT guard needed.
 */
class ReelsBlockerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
    }

    private fun createNotificationChannelIfNeeded() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(Constants.NOTIFICATION_CHANNEL_ID) != null) {
            return
        }

        val channel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }
}
