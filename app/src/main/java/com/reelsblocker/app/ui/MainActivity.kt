package com.reelsblocker.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.reelsblocker.app.R
import com.reelsblocker.app.service.MonitorForegroundService
import com.reelsblocker.app.util.AccessibilityUtils

/**
 * Status / setup screen. ReelsBlocker has no other UI: everything that
 * matters happens inside [com.reelsblocker.app.service.ReelsBlockerAccessibilityService]
 * in the background. This screen exists only to show whether that service
 * is currently turned on, deep-link into system Accessibility settings so
 * the user can turn it on, and offer a battery-optimization exemption,
 * which on many OEM phones is the difference between the service surviving
 * overnight or not.
 *
 * Deliberately built on the plain framework [Activity]/[android.widget]
 * views rather than AppCompat or Material components, to keep this project
 * free of any third-party Gradle dependency (see app/build.gradle.kts).
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var enableButton: Button
    private lateinit var batteryButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        enableButton = findViewById(R.id.enableButton)
        batteryButton = findViewById(R.id.batteryButton)

        enableButton.setOnClickListener { openAccessibilitySettings() }
        batteryButton.setOnClickListener { requestBatteryOptimizationExemption() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = AccessibilityUtils.isAccessibilityServiceEnabled(this)
        if (enabled) {
            statusText.text = getString(R.string.status_protected)
            // Give the monitor service a kick too, in case it was ever
            // swept away; harmless and idempotent if it's already running.
            try {
                startForegroundService(Intent(this, MonitorForegroundService::class.java))
            } catch (e: Exception) {
                // Non-fatal: the watchdog will retry on its own schedule.
            }
        } else {
            statusText.text = getString(R.string.status_unprotected)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.could_not_open_settings, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, R.string.battery_already_exempt, Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.could_not_open_settings, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 42
    }
}
