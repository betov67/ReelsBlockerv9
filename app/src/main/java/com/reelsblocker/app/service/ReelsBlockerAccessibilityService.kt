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
 * ReelsBlocker v12 — "single-video" policy, grace-period state machine,
 * plus main-Reels-tab detection.
 *
 * v9/v10 drove screen classification off both `TYPE_WINDOW_STATE_CHANGED`
 * *and* `TYPE_WINDOW_CONTENT_CHANGED`. That was the actual defect: Instagram
 * fires `TYPE_WINDOW_CONTENT_CHANGED` constantly while a Reel is simply
 * playing — caption text updating, like-count animating, progress bar
 * ticking — and some of those updates were getting (mis)read as "the user
 * left the Clips player," resetting the allow/block state far more often
 * than intended.
 *
 * v11 fixed that at the root: screen classification reacts ONLY to
 * `TYPE_WINDOW_STATE_CHANGED`, which Android fires on genuine screen/window
 * transitions, not on every minor content tick. This is Track A below, and
 * v12 leaves it completely untouched — it's what makes Stories and DM/profile
 * Reels work, and the user confirmed that behavior is good as-is.
 *
 * TRACK A — `TYPE_WINDOW_STATE_CHANGED` → [classifyScreen] (unchanged since v11):
 * 1. **Enter the Clips player** (detects `ClipsViewerFragment` / `clips_viewer`):
 *    set `isInReelsPlayer = true`, stamp `reelOpenTimeMs = now`. The first
 *    video plays completely uninterrupted.
 * 2. **Leaving the Clips player** (detects a screen that is neither the
 *    Clips player nor a Story): reset `isInReelsPlayer = false` /
 *    `reelOpenTimeMs = 0`.
 * 3. **Stories are ignored outright** — never touches the state at all.
 *
 * TRACK B — v12's new fix for the main Reels tab:
 *
 * The dedicated bottom-nav Reels tab is reached by switching tabs inside
 * Instagram's single main Activity — a Fragment swap, not a new
 * window/Activity. `TYPE_WINDOW_STATE_CHANGED` only fires on an actual
 * window transition, so Track A never sees this tab at all: that's the real
 * reason v11 blocked DM/profile Reels (which *do* open as their own
 * window/Activity) but let the main tab scroll forever.
 *
 * Track B adds [classifyMainReelsTab], driven by `TYPE_WINDOW_CONTENT_CHANGED`
 * — the only event type Android fires for an in-place Fragment swap. To
 * avoid reintroducing the v9/v10 noise bug, Track B is deliberately narrow:
 * - It rate-limits its own tree walk to at most once every
 *   [Constants.MAIN_TAB_CONTENT_CHECK_INTERVAL_MS] (content-changed fires far
 *   too often to walk the tree on every single event).
 * - It only ever *arms* state that Track A hasn't already armed (so it can
 *   never interfere with an in-progress DM/profile Reel).
 * - It tracks whether IT was the one that armed the current session
 *   ([enteredViaMainTab]) and only ever resets state under that flag — so it
 *   can never prematurely reset a DM/profile session that Track A owns.
 *
 * Both tracks funnel into the exact same [isInReelsPlayer] /
 * [reelOpenTimeMs] state and the exact same [handleScroll] /
 * [fireBack] — the scroll-immunity window and blocking logic the user
 * confirmed works well are shared, unmodified, by both entry paths.
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

    /**
     * v12: true only when the CURRENT `isInReelsPlayer` session was armed by
     * Track B ([classifyMainReelsTab]) rather than Track A ([classifyScreen]).
     * Lets Track B reset only sessions it itself started, so it can never
     * touch a DM/profile session that Track A is responsible for.
     */
    @Volatile
    private var enteredViaMainTab: Boolean = false

    /** v12: throttles Track B's tree walks — see [Constants.MAIN_TAB_CONTENT_CHECK_INTERVAL_MS]. */
    @Volatile
    private var lastMainTabCheckAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            // Belt-and-suspenders: the same configuration already lives in
            // res/xml/accessibility_service_config.xml, but setting it again
            // here means the service degrades gracefully even on an OEM ROM
            // that mishandles the XML config for some reason.
            val info = serviceInfo ?: AccessibilityServiceInfo()
            // v12 re-adds TYPE_WINDOW_CONTENT_CHANGED, but ONLY to drive
            // Track B (classifyMainReelsTab) — see the class-level doc
            // comment. It is rate-limited and narrowly scoped specifically
            // so it can't reintroduce the v9/v10 noise bug that v11 fixed.
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
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                    classifyScreen()

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                    classifyMainReelsTab()

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
                enteredViaMainTab = false // this is Track A's own entry, not Track B's
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
        enteredViaMainTab = false
    }

    /**
     * v12, Track B: catches the one case Track A structurally cannot see —
     * the dedicated bottom-nav Reels tab, which is an in-place Fragment
     * swap inside Instagram's single Activity and therefore never fires
     * `TYPE_WINDOW_STATE_CHANGED`. Driven by `TYPE_WINDOW_CONTENT_CHANGED`
     * instead, which Android *does* fire for that kind of in-place change —
     * but that event type is extremely chatty, so this function is built to
     * never repeat v9/v10's mistake of over-reacting to it.
     *
     * Safety rules that make this safe to run on a noisy event:
     * 1. Rate-limited to at most one tree walk per
     *    [Constants.MAIN_TAB_CONTENT_CHECK_INTERVAL_MS] — most calls return
     *    before touching `rootInActiveWindow` at all.
     * 2. Will only ever *arm* a session when none is active
     *    (`!isInReelsPlayer`) — it can never interrupt or re-arm a session
     *    Track A is already running (a DM/profile Reel).
     * 3. Will only ever *reset* a session it itself armed
     *    ([enteredViaMainTab]) — a DM/profile session stays exclusively
     *    Track A's responsibility, untouched by this function.
     */
    private fun classifyMainReelsTab() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMainTabCheckAt < Constants.MAIN_TAB_CONTENT_CHECK_INTERVAL_MS) {
            return
        }
        lastMainTabCheckAt = now

        val root = rootInActiveWindow ?: return

        // Same absolute Stories exception as Track A — never interfere.
        if (NodeUtils.findIdentifierInTree(root, Constants.STORY_IDENTIFIERS)) {
            return
        }

        val isReelsScreen = NodeUtils.findIdentifierInTree(root, Constants.CLIPS_IDENTIFIERS) ||
            NodeUtils.findIdentifierInTree(root, Constants.MAIN_REELS_TAB_IDENTIFIERS)

        if (isReelsScreen) {
            if (!isInReelsPlayer) {
                isInReelsPlayer = true
                reelOpenTimeMs = now
                enteredViaMainTab = true
                Log.d(Constants.LOG_TAG, "MAIN_TAB_REEL_PLAYER_ENTERED")
            }
            return
        }

        // Only clear a session THIS track armed. A DM/profile session
        // (enteredViaMainTab == false) is never touched here.
        if (isInReelsPlayer && enteredViaMainTab) {
            Log.d(Constants.LOG_TAG, "MAIN_TAB_REEL_PLAYER_EXITED")
            isInReelsPlayer = false
            reelOpenTimeMs = 0L
            enteredViaMainTab = false
        }
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
