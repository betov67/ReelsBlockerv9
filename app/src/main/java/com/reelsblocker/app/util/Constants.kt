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
     * How long a tap inside a DM thread or a Profile grid keeps the
     * "allow the next Reel player that opens" window armed, in
     * milliseconds. Required by spec: 3 seconds.
     */
    const val BYPASS_WINDOW_MS = 3_000L

    /**
     * Minimum gap between two consecutive GLOBAL_ACTION_BACK calls. The
     * Clips player fires several TYPE_WINDOW_CONTENT_CHANGED events per
     * second while a video is playing, so without this debounce we would
     * re-press back on every single one of them while the previous back
     * navigation is still animating.
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

    /** A Reel opened from inside a Direct Message thread. */
    val DM_IDENTIFIERS = arrayOf(
        "direct_thread",
        "com.instagram.direct",
        "message_content"
    )

    /** A Reel opened from a user's Profile grid. */
    val PROFILE_IDENTIFIERS = arrayOf(
        "profile_tab",
        "user_profile",
        "profile_header"
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
