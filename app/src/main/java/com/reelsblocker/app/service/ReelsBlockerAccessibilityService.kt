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
 * ReelsBlocker v11 — "single-video" policy, grace-period state machine.
 *
 * v9/v10 drove screen classification off both `TYPE_WINDOW_STATE_CHANGED`
 * *and* `TYPE_WINDOW_CONTENT_CHANGED`. That was the actual defect: Instagram
 * fires `TYPE_WINDOW_CONTENT_CHANGED` constantly while a Reel is simply
 * playing — caption text updating, like-count animating, progress bar
 * ticking — and some of those updates were getting (mis)read as "the user
 * left the Clips player," resetting the allow/block state far more often
 * than intended.
 *
 * v11 fixes this at the root: screen classification now reacts ONLY to
 * `TYPE_WINDOW_STATE_CHANGED`, which Android fires on genuine screen/window
 * transitions, not on every minor content tick. `TYPE_WINDOW_CONTENT_CHANGED`
 * is no longer requested at all (see `res/xml/accessibility_service_config.xml`).
 *
 * The state machine:
 *
 * 1. **Enter the Clips player** (`TYPE_WINDOW_STATE_CHANGED` detects
 *    `ClipsViewerFragment` / `clips_viewer`): set `isInReelsPlayer = true`
 *    and stamp `reelOpenTimeMs = now`. Nothing else — the first video plays
 *    completely uninterrupted.
 * 2. **Any `TYPE_VIEW_SCROLLED` while `isInReelsPlayer` is true:**
 *    - If less than [Constants.SCROLL_IMMUNITY_WINDOW_MS] has passed since
 *      `reelOpenTimeMs`, this is almost certainly Instagram's own internal
 *      layout/scroll settling for the first video — ignore it completely.
 *    - Otherwise, this can only be a real swipe (or auto-advance) to a
 *      second video: fire `GLOBAL_ACTION_BACK` immediately.
 * 3. **Leaving the Clips player** (`TYPE_WINDOW_STATE_CHANGED` detects a
 *    screen that is neither the Clips player nor a Story): reset
 *    `isInReelsPlayer = false` and `reelOpenTimeMs = 0`. The next Reel
 *    opened gets its own fresh immunity window and its own free video.
 * 4. **Stories are ignored outright** — detecting one returns immediately
 *    without touching `isInReelsPlayer`/`reelOpenTimeMs` at all. This part
 *    was never broken and is deliberately left as-is.
 *
 * Every step is wrapped in try/catch. A single malformed or stale node must
 * never be allowed to crash this service, because a crashed accessibility
 * service is a *disabled* accessibility service until the user manually
 * re-enables it from Settings — silently defeating the entire point of the
 * app.
 */
class ReelsBlockerAccessibilityService : AccessibilityService() {

    /** True from the moment the Clips player is detected until the user leaves it. */
    @Volatile
    private var isInReelsPlayer: Boolean = false

    /**
     * [SystemClock.elapsedRealtime] timestamp of the moment the Clips
     * player was entered. 0 whenever [isInReelsPlayer] is false. Used to
     * compute the scroll-immunity grace period.
     */
    @Volatile
    private var reelOpenTimeMs: Long = 0L

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
            // Deliberately NOT requesting TYPE_WINDOW_CONTENT_CHANGED — see
            // the class-level doc comment for why that was the actual bug.
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
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
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
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
     * Run only on genuine screen/window transitions inside Instagram
     * (`TYPE_WINDOW_STATE_CHANGED`) — never on content-changed ticks, which
     * is exactly the distinction that was missing before v11.
     */
    private fun classifyScreen() {
        val root = rootInActiveWindow ?: return

        // --- Stories: ignored outright. Never touch isInReelsPlayer. ---
        if (NodeUtils.findIdentifierInTree(root, Constants.STORY_IDENTIFIERS)) {
            Log.d(Constants.LOG_TAG, "STORY_BYPASS")
            return
        }

        val isClipsScreen = NodeUtils.findIdentifierInTree(root, Constants.CLIPS_IDENTIFIERS)

        if (isClipsScreen) {
            if (!isInReelsPlayer) {
                // Fresh entry into the Clips player: arm the state machine
                // and stamp the immunity window. Do nothing else — the
                // first video plays with zero interference.
                isInReelsPlayer = true
                reelOpenTimeMs = SystemClock.elapsedRealtime()
                Log.d(Constants.LOG_TAG, "REEL_PLAYER_ENTERED")
            }
            // Already inside the Clips player: a window-state-changed event
            // firing again here (rare, but possible on some OEM skins) must
            // NOT re-arm the timer or touch state — only a genuine first
            // entry does that.
            return
        }

        // Neither Stories nor the Clips player: the user has explicitly
        // left the Reels experience (back to feed, a DM thread, a profile,
        // Explore, wherever). Reset so the next Reel opened gets a fresh
        // immunity window and its own free video.
        if (isInReelsPlayer) {
            Log.d(Constants.LOG_TAG, "REEL_PLAYER_EXITED")
        }
        isInReelsPlayer = false
        reelOpenTimeMs = 0L
    }

    /**
     * Fired on every `TYPE_VIEW_SCROLLED` event inside Instagram. Only
     * matters while [isInReelsPlayer] is true; otherwise the scroll came
     * from something else entirely (a list in the feed, Explore's grid)
     * and is none of our concern.
     */
    private fun handleScroll() {
        if (!isInReelsPlayer) {
            return
        }

        val elapsedSinceOpen = SystemClock.elapsedRealtime() - reelOpenTimeMs
        if (elapsedSinceOpen < Constants.SCROLL_IMMUNITY_WINDOW_MS) {
            // Within the immunity window: this is Instagram's own internal
            // scroll/layout settling for the first video, not the user
            // swiping. Ignore it completely — let the first video load.
            Log.d(Constants.LOG_TAG, "SCROLL_IGNORED_WITHIN_IMMUNITY_WINDOW")
            return
        }

        // Outside the immunity window, still inside the Clips player: this
        // can only be a real swipe (or Instagram auto-advancing) to a
        // second video. Block it.
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
