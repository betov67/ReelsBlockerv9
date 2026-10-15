package com.reelsblocker.app.util

/**
 * Every tunable value and every Instagram UI identifier the blocker looks
 * for lives here, in one place, so the matching logic in
 * [com.reelsblocker.app.service.ReelsBlockerAccessibilityService] never has
 * a magic string embedded in it.
 *
 * A note on the identifiers below: Instagram ships one Android app but
 * reshuffles internal view IDs across releases. Every identifier is matched
 * with a case-insensitive *substring* check (see
 * [NodeUtils.findIdentifierInTree]) specifically so a minor internal rename
 * (an "_v2" suffix Instagram tacks onto a view id, for example) doesn't
 * silently break a whole category. If Instagram renames these more
 * drastically in a future release, update the arrays below — nothing else
 * in the service needs to change.
 */
object Constants {

    /** The only package this service ever acts on. */
    const val INSTAGRAM_PACKAGE = "com.instagram.android"

    /**
     * Grace period, in milliseconds, after the Clips player is first
     * detected during which TYPE_VIEW_SCROLLED events are ignored
     * completely. Required because Instagram fires its own internal
     * scroll/layout events while positioning the first video (RecyclerView
     * settling into place, etc.) — without this window those
     * system-generated events get mistaken for the user swiping to a
     * second Reel, and the first video never gets to play at all.
     */
    const val SCROLL_IMMUNITY_WINDOW_MS = 2_000L

    /**
     * Minimum gap between two consecutive GLOBAL_ACTION_BACK calls. A single
     * swipe gesture against the Clips pager fires several TYPE_VIEW_SCROLLED
     * events in a row as the drag progresses (not just one at the end), so
     * without this debounce we would re-press back on every single one of
     * them while the previous back navigation is still animating.
     */
    const val BACK_ACTION_DEBOUNCE_MS = 800L

    /**
     * Stories. Modern Instagram keeps the *singular* "reel" word for this
     * screen — it predates the Reels feature and was never renamed. Must
     * NEVER be blocked.
     */
    val STORY_IDENTIFIERS = arrayOf(
        "reel_viewer_image_view",
        "reel_viewer",
        "story_feed_holder",
        "ReelViewerFragment"
    )

    /**
     * The Reels/Clips player itself. Internally Instagram calls the Reels
     * feature "Clips" — the *plural* counterpart to the Stories identifiers
     * above. The two sets are intentionally disjoint.
     */
    val CLIPS_IDENTIFIERS = arrayOf(
        "clips_viewer",
        "clips_video_container",
        "ClipsViewerFragment"
    )

    /**
     * v12 addition: candidate identifiers for the dedicated bottom-nav
     * Reels tab (the endless-scroll Reels home, as opposed to a Reel opened
     * contextually from a DM or a profile).
     *
     * HONESTY NOTE: unlike [STORY_IDENTIFIERS] and [CLIPS_IDENTIFIERS] —
     * which were effectively confirmed by v9-v11 actually blocking/allowing
     * the right screens in practice — these are a best-effort guess. There
     * is no public documentation of Instagram's internal view IDs for this
     * specific tab, and this service has no way to inspect a real device to
     * confirm them. If v12 still fails to catch the main Reels tab, the
     * fastest path to a real fix is pulling one `adb shell uiautomator
     * dump` (or an Accessibility Inspector dump) while that tab is open, so
     * the actual IDs can replace these guesses.
     */
    val MAIN_REELS_TAB_IDENTIFIERS = arrayOf(
        "reels_tab",
        "clips_tab",
        "reels_tab_fragment",
        "clips_tab_fragment",
        "tab_reels",
        "reel_feed_recycler",
        "clips_fragment_container"
    )

    /**
     * Minimum gap, in milliseconds, between two full accessibility-tree
     * walks triggered by `TYPE_WINDOW_CONTENT_CHANGED` (see
     * `classifyMainReelsTab()`). That event type fires very frequently —
     * it's exactly why v9/v10 got flaky when they used it to drive state
     * resets. v12 reintroduces it only for *detecting the main Reels tab*
     * (a Fragment swap inside the same window never fires
     * `TYPE_WINDOW_STATE_CHANGED`, so that event alone can't see this tab),
     * and bounds the cost/noise of doing so with this rate limit — a tree
     * walk this path runs at most a few times a second, never on every
     * single content tick.
     */
    const val MAIN_TAB_CONTENT_CHECK_INTERVAL_MS = 300L

    /**
     * Upper bound on nodes visited per tree walk. Keeps every single call
     * fast and bounded even on Instagram's deepest view hierarchies, so a
     * pathological screen can never turn into jank or an ANR.
     */
    const val MAX_NODES_PER_TRAVERSAL = 400

    const val NOTIFICATION_CHANNEL_ID = "reels_blocker_monitor"
    const val NOTIFICATION_ID = 9001

    /** How often the watchdog re-checks that the monitor service is alive. */
    const val WATCHDOG_INTERVAL_MS = 20 * 60 * 1000L // 20 minutes

    const val LOG_TAG = "ReelsBlockerA11y"
}
