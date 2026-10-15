package com.reelsblocker.app.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import com.reelsblocker.app.service.ReelsBlockerAccessibilityService

object AccessibilityUtils {

    /**
     * True if the user currently has ReelsBlockerAccessibilityService turned
     * on in system Settings. This can only ever be *read* — Android gives
     * no API for an app to enable its own accessibility service, by design
     * — so this purely drives the status UI in [com.reelsblocker.app.ui.MainActivity]
     * and the watchdog's diagnostic logging.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val serviceClassName = ReelsBlockerAccessibilityService::class.java.name

        // Fast path: ask the AccessibilityManager for the list of
        // currently-running accessibility services.
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val enabledServices = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            if (enabledServices != null) {
                for (serviceInfo in enabledServices) {
                    val id = serviceInfo.id
                    if (id != null && id.contains(serviceClassName)) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            // Fall through to the Settings.Secure check below.
        }

        // Fallback: parse the raw Settings.Secure string directly. More
        // brittle, but it still works in the rare OEM state where the
        // AccessibilityManager list above comes back empty even though the
        // service is genuinely enabled.
        return try {
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val expectedComponent = "${context.packageName}/$serviceClassName"
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                if (colonSplitter.next().equals(expectedComponent, ignoreCase = true)) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}
