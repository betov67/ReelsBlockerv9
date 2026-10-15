package com.reelsblocker.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.reelsblocker.app.R
import com.reelsblocker.app.ui.MainActivity
import com.reelsblocker.app.util.Constants
import com.reelsblocker.app.util.WatchdogScheduler

/**
 * A minimal, always-on foreground service.
 *
 * It does none of the blocking logic itself — [ReelsBlockerAccessibilityService]
 * is what actually inspects screens and presses back. This service exists
 * purely so the OS sees an ongoing, user-visible notification tied to our
 * process, which substantially raises our priority in the eyes of the
 * low-memory killer and OEM battery managers (part 4 of the spec: service
 * stability). It also re-arms the AlarmManager watchdog every time it
 * (re)starts.
 */
class MonitorForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(Constants.LOG_TAG, "MONITOR_SERVICE_CREATED")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(Constants.NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // On some OEM builds, calling startForeground() from a
            // background state can throw even when we believe we're in an
            // allowed state (e.g. ForegroundServiceStartNotAllowedException
            // on API 31+). Never let that crash the process — the watchdog
            // will simply try again on its next tick.
            Log.e(Constants.LOG_TAG, "MONITOR_SERVICE_START_FOREGROUND_FAILED", e)
        }

        WatchdogScheduler.scheduleNext(applicationContext)
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        Log.w(Constants.LOG_TAG, "MONITOR_SERVICE_DESTROYED")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The user swiped ReelsBlocker away from Recents. This is NOT the
        // accessibility service (that keeps running independently, bound
        // by the system), but we make sure the ongoing notification — and
        // the watchdog alarm it re-arms — comes straight back anyway.
        Log.w(Constants.LOG_TAG, "MONITOR_SERVICE_TASK_REMOVED_RESTARTING")
        try {
            val restartIntent = Intent(applicationContext, MonitorForegroundService::class.java)
            applicationContext.startForegroundService(restartIntent)
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "MONITOR_SERVICE_RESTART_FAILED", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(Notification.PRIORITY_MIN)
            .build()
    }

    companion object {
        /** Read by [com.reelsblocker.app.receiver.WatchdogReceiver] to decide whether a restart is needed. */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
