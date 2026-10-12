package com.reelsblocker.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.reelsblocker.app.model.BypassSource
import com.reelsblocker.app.util.Constants
import com.reelsblocker.app.util.NodeUtils

/**
 * ReelsBlocker v9 — core accessibility logic.
 *
 * On every relevant event this walks the active window's node tree, in
 * strict priority order:
 *
 * 1. **Story bypass (highest priority, never overridden).** If the active
 *    screen is a Story viewer, do nothing at all — not even
 *    [performGlobalAction], just a log line — and return immediately.
 * 2. **Arm the DM/Profile bypass window.** A click inside a DM thread or a
 *    Profile grid starts a 3-second window during which the *next* Reel
 *    player that opens is treated as deliberately, legitimately opened.
 * 3. **Classify the Reels/Clips player.** If it's on screen and a bypass
 *    window is currently armed, the viewing session is marked allowed for
 *    as long as the player stays open (so it is never yanked shut mid-video
 *    just because the 3-second arming window has since elapsed). Otherwise
 *    [android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK]
 *    is pressed immediately to close it.
 *
 * Every step is wrapped in try/catch. A single malformed or stale node must
 * never be allowed to crash this service, because a crashed accessibility
 * service is a *disabled* accessibility service until the user manually
 * re-enables it from Settings — silently defeating the entire point of the
 * app.
 */
class ReelsBlockerAccessibilityService : AccessibilityService() {

    /** SystemClock.elapsedRealtime() of the moment the DM/Profile bypass window was last armed. */
    @Volatile
    private var bypassArmedAt: Long = 0L

    @Volatile
    private var bypassSource: BypassSource = BypassSource.NONE

    /**
     * True once the *currently open* Clips player has been granted a
     * viewing pass. Stays true for the lifetime of that single screen —
     * i.e. until the user leaves the Clips player entirely — regardless of
     * whether the original 3-second arming window has since expired. This
     * is what stops a long Reel from being force-closed partway through
     * just because it took the viewer more than 3 seconds to watch.
     *
     * Known, accepted trade-off: if Instagram's own player lets the user
     * swipe from an allowed Reel straight into its "up next" suggestions
     * without ever leaving the ClipsViewerFragment screen, those
     * suggestions remain visible too, since from the accessibility tree's
     * point of view it is still the same screen. Detecting a same-screen
     * swipe to different underlying media is not reliably possible from
     * view-id/class-name signals alone.
     */
    @Volatile
    private var clipsSessionAllowed: Boolean = false

    /** Debounces GLOBAL_ACTION_BACK so it is never spammed on rapid-fire content-changed events. */
    @Volatile
    private var lastBackActionAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            // Belt-and-suspenders: the same configuration already lives in
            // res/xml/accessibility_service_config.xml, but setting it again
            // here means the service degrades gracefully even on an OEM ROM
            // that mishandles the XML config for some reason.
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            info.packageNames = arrayOf(Constants.INSTAGRAM_PACKAGE)
            info.notificationTimeout = 100
            serviceInfo = info
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "SERVICE_CONNECTED_CONFIG_FAILED", e)
        }

        isServiceRunning = true
        Log.i(Constants.LOG_TAG, "SERVICE_CONNECTED")

        try {
            startForegroundService(Intent(this, MonitorForegroundService::class.java))
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "SERVICE_CONNECTED_MONITOR_START_FAILED", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            val eventPackage = event.packageName?.toString()
            if (eventPackage != Constants.INSTAGRAM_PACKAGE) {
                return
            }

            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED ->
                    handleClick()

                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                    classifyAndAct()
            }
        } catch (e: Exception) {
            // The single most important catch block in the whole service:
            // an uncaught exception here crashes the accessibility service
            // process, which the OS then reports as the service having
            // stopped — silently disabling all blocking until the user
            // notices and manually re-enables it. Never let that happen.
            Log.e(Constants.LOG_TAG, "ON_ACCESSIBILITY_EVENT_FAILED", e)
        }
    }

    /**
     * A tap happened somewhere in Instagram. If the user is currently
     * inside a DM thread or a Profile grid, arm the bypass window — this is
     * almost certainly a tap on a Reel thumbnail that is about to open the
     * ClipsViewerFragment.
     *
     * This arms on *any* click while one of those containers is on screen,
     * rather than trying to match the exact thumbnail element, because
     * Instagram does not expose stable, documented view ids for individual
     * media thumbnails. The screen-level container ids are far more
     * reliable, and the short 3-second window keeps an unrelated tap (e.g.
     * sending a text message in the same thread) from granting a
     * meaningfully long-lived bypass: it only ever matters if a Clips
     * player genuinely opens within the next 3 seconds.
     */
    private fun handleClick() {
        val root = rootInActiveWindow ?: return

        if (NodeUtils.findIdentifierInTree(root, Constants.DM_IDENTIFIERS)) {
            armBypass(BypassSource.DIRECT_MESSAGE)
        } else if (NodeUtils.findIdentifierInTree(root, Constants.PROFILE_IDENTIFIERS)) {
            armBypass(BypassSource.PROFILE)
        }
    }

    private fun armBypass(source: BypassSource) {
        bypassArmedAt = SystemClock.elapsedRealtime()
        bypassSource = source
        Log.d(Constants.LOG_TAG, "BYPASS_WINDOW_ARMED source=$source")
    }

    private fun isBypassWindowActive(): Boolean {
        if (bypassSource == BypassSource.NONE) return false
        val elapsed = SystemClock.elapsedRealtime() - bypassArmedAt
        return elapsed in 0..Constants.BYPASS_WINDOW_MS
    }

    /**
     * The main classification pass, run on every window-state and
     * window-content change inside Instagram.
     */
    private fun classifyAndAct() {
        val root = rootInActiveWindow ?: return

        // --- 1. Stories: absolute priority, never blocked, zero action. ---
        if (NodeUtils.findIdentifierInTree(root, Constants.STORY_IDENTIFIERS)) {
            Log.d(Constants.LOG_TAG, "STORY_BYPASS")
            clipsSessionAllowed = false // left any Clips session we might have been in
            return
        }

        val isClipsScreen = NodeUtils.findIdentifierInTree(root, Constants.CLIPS_IDENTIFIERS)

        if (!isClipsScreen) {
            // Main feed, Explore grid, a DM thread list, a profile, the
            // Clips player having just closed, etc. Whatever viewing
            // session may have been granted is now over; it must be
            // re-earned the next time a Clips player opens.
            clipsSessionAllowed = false
            return
        }

        if (clipsSessionAllowed) {
            // Already granted for this viewing session. Nothing to do on
            // every content-changed tick while the video simply plays.
            return
        }

        if (isBypassWindowActive()) {
            clipsSessionAllowed = true
            when (bypassSource) {
                BypassSource.DIRECT_MESSAGE -> Log.d(Constants.LOG_TAG, "DM_REEL_ALLOWED")
                BypassSource.PROFILE -> Log.d(Constants.LOG_TAG, "PROFILE_REEL_ALLOWED")
                BypassSource.NONE -> Unit // unreachable: isBypassWindowActive() already excludes this
            }
            return
        }

        // Opened from the main Reels tab, the feed, or Explore, with no
        // active DM/Profile bypass window: block it.
        Log.d(Constants.LOG_TAG, "FEED_REEL_BLOCKED")
        fireBack()
    }

    /** [GLOBAL_ACTION_BACK], debounced and crash-proof. */
    private fun fireBack() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackActionAt < Constants.BACK_ACTION_DEBOUNCE_MS) {
            return
        }
        lastBackActionAt = now
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "FIRE_BACK_FAILED", e)
        }
    }

    override fun onInterrupt() {
        Log.w(Constants.LOG_TAG, "SERVICE_INTERRUPTED")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceRunning = false
        Log.w(Constants.LOG_TAG, "SERVICE_UNBOUND")
        return super.onUnbind(intent)
    }

    companion object {
        /** Read by the watchdog/UI to show live status; true only while the system has this service bound. */
        @Volatile
        var isServiceRunning: Boolean = false
            private set
    }
}
