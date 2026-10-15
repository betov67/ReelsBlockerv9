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
 * Periodic watchdog tick (see [WatchdogScheduler]).
 *
 * Deliberately does very little work and returns fast:
 * `BroadcastReceiver#onReceive` runs on the main thread and the system
 * expects it back within a few seconds, so anything slow here would risk
 * the exact ANR the spec asks us to avoid.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(Constants.LOG_TAG, "WATCHDOG_TICK monitor_running=${MonitorForegroundService.isRunning}")

        try {
            if (!MonitorForegroundService.isRunning) {
                Log.w(Constants.LOG_TAG, "WATCHDOG_RESTARTING_MONITOR_SERVICE")
                val serviceIntent = Intent(context, MonitorForegroundService::class.java)
                context.startForegroundService(serviceIntent)
            }

            if (!AccessibilityUtils.isAccessibilityServiceEnabled(context)) {
                // We can't re-enable it for the user — Android forbids an
                // app from toggling its own accessibility permission — but
                // logging it clearly means it shows up immediately if
                // anyone is tailing logcat while diagnosing why blocking
                // stopped.
                Log.w(Constants.LOG_TAG, "WATCHDOG_ACCESSIBILITY_SERVICE_DISABLED")
            }
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "WATCHDOG_TICK_FAILED", e)
        } finally {
            // Always reschedule, even if something above failed, so a
            // single bad tick can never permanently stop the watchdog.
            WatchdogScheduler.scheduleNext(context)
        }
    }
}
