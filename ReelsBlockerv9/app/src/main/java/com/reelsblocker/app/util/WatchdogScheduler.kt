package com.reelsblocker.app.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.reelsblocker.app.receiver.WatchdogReceiver

/**
 * Schedules and cancels the periodic watchdog alarm.
 *
 * This deliberately uses [AlarmManager.setAndAllowWhileIdle] — an *inexact*
 * alarm that is still allowed to fire during Doze maintenance windows —
 * rather than an exact alarm. The watchdog does not need to-the-second
 * precision, and avoiding exact alarms means ReelsBlocker never has to ask
 * for the special "Alarms & reminders" permission on Android 12+. That
 * keeps setup down to a single step (turn on the accessibility service) and
 * keeps the watchdog itself firmly out of ANR territory, per spec.
 */
object WatchdogScheduler {

    private const val REQUEST_CODE = 9100

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WatchdogReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /** Schedules the next watchdog tick [Constants.WATCHDOG_INTERVAL_MS] from now. */
    fun scheduleNext(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + Constants.WATCHDOG_INTERVAL_MS
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent(context)
            )
            Log.d(Constants.LOG_TAG, "WATCHDOG_SCHEDULED next_tick_in_ms=${Constants.WATCHDOG_INTERVAL_MS}")
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "WATCHDOG_SCHEDULE_FAILED", e)
        }
    }

    fun cancel(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent(context))
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "WATCHDOG_CANCEL_FAILED", e)
        }
    }
}
