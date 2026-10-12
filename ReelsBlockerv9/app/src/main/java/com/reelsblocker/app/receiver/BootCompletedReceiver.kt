package com.reelsblocker.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.reelsblocker.app.service.MonitorForegroundService
import com.reelsblocker.app.util.AccessibilityUtils
import com.reelsblocker.app.util.Constants
import com.reelsblocker.app.util.WatchdogScheduler

/**
 * Fires on device boot and after an app update. AlarmManager alarms do not
 * survive a reboot, so this is also where the watchdog's repeating alarm
 * gets re-armed for the new boot session.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(Constants.LOG_TAG, "BOOT_RECEIVER_TRIGGERED action=${intent?.action}")

        try {
            if (!AccessibilityUtils.isAccessibilityServiceEnabled(context)) {
                // Nothing to protect yet: the user hasn't turned the
                // service on. Don't start a foreground service (and show
                // its notification) for no reason.
                Log.d(Constants.LOG_TAG, "BOOT_RECEIVER_SERVICE_NOT_ENABLED_SKIPPING")
                return
            }

            val serviceIntent = Intent(context, MonitorForegroundService::class.java)
            context.startForegroundService(serviceIntent)
            WatchdogScheduler.scheduleNext(context)
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "BOOT_RECEIVER_FAILED", e)
        }
    }
}
