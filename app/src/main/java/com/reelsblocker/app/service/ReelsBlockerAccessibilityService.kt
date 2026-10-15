package com.reelsblocker.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.reelsblocker.app.util.Constants
import com.reelsblocker.app.util.NodeUtils

/**
 * ReelsBlocker v10 — "single-video" policy.
 *
 * Unlike earlier versions, v10 does not care *where* a Reel was opened from
 * (DM, Profile, main feed, Search, Explore — doesn't matter). The rule is
 * much simpler and source-agnostic:
 *
 * 1. **First Reel is always free.** The moment the Clips player
 *    (`ClipsViewerFragment` / `clips_viewer`) opens, the user gets to watch
 *    that one video with zero interference, no matter how they got there.
 * 2. **No second Reel.** The instant the player tries to move past that
 *    first video — a manual swipe, or Instagram auto-advancing on its own
 *    — [performGlobalAction][AccessibilityService.performGlobalAction]
 *    with [AccessibilityService.GLOBAL_ACTION_BACK] fires immediately,
 *    closing the player back to wherever the user came from.
 * 3. **The counter resets** the moment the user leaves the Clips player
 *    screen entirely (back to feed, to a profile, wherever), so the next
 *    time they open a Reel, they again get exactly one free video.
 * 4. **Stories are a hard, absolute exception** — never touched, not even
 *    counted. See [Constants.STORY_IDENTIFIERS].
 *
 * The mechanism for step 2 is [AccessibilityEvent.TYPE_VIEW_SCROLLED]. The
 * Clips player is built on a vertically-paging view (functionally a
 * ViewPager2/RecyclerView), and that view fires a scroll-position-changed
 * accessibility event whenever its current page changes — whether that
 * change came from the user's finger or from Instagram's own auto-advance
 * timer calling the same "move to next page" method internally. Because
 * both paths move the *same* underlying scrollable view, one signal
 * reasonably covers both cases without needing to separately guess at
 * gesture vs. auto-advance.
 *
 * Every step is wrapped in try/catch. A single malformed or stale node must
 * never be allowed to crash this service, because a crashed accessibility
 * service is a *disabled* accessibility service until the user manually
 * re-enables it from Settings — silently defeating the entire point of the
 * app.
 */
class ReelsBlockerAccessibilityService : AccessibilityService() {

    /**
     * How many Reels have been granted in the *current* visit to the Clips
     * player. 0 = haven't shown one yet (next classification pass may grant
     * one). 1 = the one free video has already been granted; any further
     * page-scroll event from here gets blocked. Reset to 0 as soon as the
     * active screen is no longer the Clips player at all.
     */
    @Volatile
    private var reelsGrantedInSession: Int = 0

    /** Debounces GLOBAL_ACTION_BACK so a single swipe's burst of scroll events can't spam it. */
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
                AccessibilityEvent.TYPE_VIEW_SCROLLED
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
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                    classifyScreen()

                AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                    handleScroll()
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
     * Run on every window-state and window-content change inside Instagram.
     * Decides what kind of screen is now active and updates
     * [reelsGrantedInSession] accordingly. This never calls [fireBack] —
     * that only ever happens from [handleScroll], in response to an actual
     * scroll/page-change event.
     */
    private fun classifyScreen() {
        val root = rootInActiveWindow ?: return

        // --- Stories: absolute priority, never blocked, never counted. ---
        if (NodeUtils.findIdentifierInTree(root, Constants.STORY_IDENTIFIERS)) {
            Log.d(Constants.LOG_TAG, "STORY_BYPASS")
            reelsGrantedInSession = 0 // left any Clips session we might have been in
            return
        }

        val isClipsScreen = NodeUtils.findIdentifierInTree(root, Constants.CLIPS_IDENTIFIERS)

        if (!isClipsScreen) {
            // Main feed, Explore grid, a DM thread, a profile, Search, the
            // Clips player having just closed, etc. — the user has left
            // the Reel player entirely. The next time they open one, they
            // get their one free video again.
            if (reelsGrantedInSession != 0) {
                Log.d(Constants.LOG_TAG, "REEL_SESSION_RESET")
            }
            reelsGrantedInSession = 0
            return
        }

        if (reelsGrantedInSession == 0) {
            // First time we've seen the Clips player since the counter was
            // last reset: grant this one video, no matter where it was
            // opened from.
            reelsGrantedInSession = 1
            Log.d(Constants.LOG_TAG, "REEL_INITIAL_ALLOWED")
        }
        // reelsGrantedInSession == 1 already: nothing to do here. Repeated
        // content-changed ticks while the single granted video simply
        // plays (captions updating, like-count animating, etc.) must NOT
        // be treated as a page change — only a real TYPE_VIEW_SCROLLED
        // event (handleScroll, below) means the pager actually moved.
    }

    /**
     * Fired on [AccessibilityEvent.TYPE_VIEW_SCROLLED] inside Instagram.
     * Only acts if we're currently inside the Clips player AND a Reel has
     * already been granted this session — i.e. this scroll can only be an
     * attempt to move past the one free video. If we're not in the Clips
     * player at all (scroll came from some unrelated list elsewhere in the
     * app), or no Reel has been granted yet (shouldn't normally happen,
     * since classifyScreen grants on the very first content-changed event
     * for the screen, but checked defensively), it's ignored.
     */
    private fun handleScroll() {
        if (reelsGrantedInSession == 0) {
            // Nothing granted yet in this screen visit — not a Clips swipe
            // we care about (either we're not in the player, or this
            // scroll event raced ahead of the classification pass that
            // would have granted the first video).
            return
        }

        val root = rootInActiveWindow ?: return
        if (!NodeUtils.findIdentifierInTree(root, Constants.CLIPS_IDENTIFIERS)) {
            // Scroll happened somewhere else in Instagram (a list in the
            // feed, a grid in Explore, etc.) — not our concern.
            return
        }

        // Still in the Clips player, and the one free video was already
        // granted: this scroll can only mean the pager moved to a second
        // (or later) video, by swipe or by auto-advance. Block it.
        Log.d(Constants.LOG_TAG, "SECOND_REEL_SWIPE_BLOCKED")
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
