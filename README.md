# ReelsBlocker v10

A lightweight Android AccessibilityService that enforces a "single-video"
policy on Instagram's Reels player: the first Reel you open (from anywhere
— feed, Explore, a DM, a profile) always plays freely, but swiping to a
second one (or Instagram auto-advancing to one) immediately closes the
player. Stories are a hard exception and are never touched.

## Build

Requires **JDK 17** and **Android SDK Build-Tools 36 / compileSdk 36**
(installed automatically by Android Studio's SDK Manager on first sync).

```bash
./gradlew assembleDebug
```

The first run downloads Gradle 9.6.0 itself via the wrapper, then Android
Gradle Plugin 9.4.0 — all from Google's and Maven Central's repositories, as
configured in `settings.gradle.kts`. No separate Kotlin Gradle plugin is
declared: AGP 9.0+ bundles Kotlin support directly (see
[migrate-to-built-in-kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)),
pulling in a compatible Kotlin version on its own. The app module has
**zero third-party dependencies** beyond that — it only uses Android
framework APIs, so there's nothing else to resolve.

This exact configuration (compileSdk 36, no separate Kotlin plugin,
Gradle 9.6.0, AGP 9.4.0) was verified by an actual CI build, not just
reviewed by eye — see "Verified" below.

## Install & enable

1. Install the debug APK (`applicationId` `com.reelsblocker.app.debug`) on a
   device with Instagram installed.
2. Open **ReelsBlocker**, tap **Open Accessibility Settings**, and turn the
   service on. Android requires this manual step for every accessibility
   service — no app can enable it for itself.
3. Optionally tap **Exempt From Battery Optimization**. On MIUI, OneUI,
   ColorOS, EMUI and similar OEM skins this is usually the difference
   between the service surviving overnight and getting silently killed.

## How the blocking logic works

See `ReelsBlockerAccessibilityService.kt` for the fully-commented
implementation. In short:

1. **Story bypass (absolute, never counted)** — if `reel_viewer`,
   `reel_viewer_image_view`, `story_feed_holder`, or `ReelViewerFragment` is
   found anywhere in the active screen's node tree, the service does
   nothing at all. Not even a back press.
2. **First Reel always free** — the moment the Clips player
   (`clips_viewer` / `clips_video_container` / `ClipsViewerFragment`) is
   detected on a `TYPE_WINDOW_STATE_CHANGED`/`TYPE_WINDOW_CONTENT_CHANGED`
   event, an internal per-visit counter goes from 0 to 1 and that one video
   plays with zero interference — regardless of where it was opened from.
3. **No second Reel** — a `TYPE_VIEW_SCROLLED` event while still inside the
   Clips player with the counter already at 1 means the pager moved to
   another video (manual swipe, or Instagram's own auto-advance — both move
   the same underlying scrollable view, so one signal reasonably covers
   both). `performGlobalAction(GLOBAL_ACTION_BACK)` fires immediately
   (debounced to one call per 800ms, since a single swipe gesture fires
   several scroll events in a row as the drag progresses).
4. **Counter reset** — as soon as the active screen is no longer the Clips
   player at all (back to feed, a profile, Explore, wherever), the counter
   drops back to 0. The next Reel opened gets its one free video again.

All Instagram view-id/class-name identifiers live in one place,
`util/Constants.kt`, matched as case-insensitive substrings specifically so
a minor Instagram internal rename doesn't silently break detection.

**Known limitation:** the "one free video" is granted per *visit* to the
Clips player screen, not per unique video. If Instagram ever lets a swipe
momentarily re-render the same screen without a scroll event firing (not
observed, but accessibility trees are inherently a best-effort signal, not
a guarantee), that edge case wouldn't be caught. This is called out in the
service's code comments as well.

## Stability

- `MonitorForegroundService` holds an ongoing low-priority notification so
  Android treats the ReelsBlocker process as high-priority in the
  background.
- Every accessibility-tree walk (`util/NodeUtils.kt`) wraps each individual
  node access in its own try/catch, and the top-level
  `onAccessibilityEvent` has one more catch-all around everything — a single
  stale/recycled node can never crash, let alone disable, the service.
- `WatchdogReceiver` + `WatchdogScheduler` re-check every 20 minutes (via an
  inexact, Doze-friendly `AlarmManager.setAndAllowWhileIdle` alarm — no
  special "exact alarm" permission needed) that the monitor service is still
  running, and restart it if not. `BootCompletedReceiver` re-arms everything
  after a reboot or app update.

## Logging

Everything logs under tag **`ReelsBlockerA11y`**:
`STORY_BYPASS`, `REEL_INITIAL_ALLOWED`, `SECOND_REEL_SWIPE_BLOCKED`,
`REEL_SESSION_RESET`, plus diagnostic lines for service lifecycle and the
watchdog. Tail it with:

```bash
adb logcat -s ReelsBlockerA11y
```

## Verified

This project's exact Gradle/AGP/SDK configuration was validated by a real
GitHub Actions build (not just reviewed by eye), after working through:
`platforms;android-37` not existing as a plain SDK package (API 37 only
ships minor-versioned — `37.0`/`37.1`/`37.2` — so this project targets the
plain `36` package instead), and AGP 9's built-in Kotlin support rejecting
the separate `org.jetbrains.kotlin.android` plugin as a fatal error.

## Project layout

```
ReelsBlockerV10/
├── app/
│   └── src/main/
│       ├── java/com/reelsblocker/app/
│       │   ├── ReelsBlockerApplication.kt
│       │   ├── receiver/BootCompletedReceiver.kt
│       │   ├── receiver/WatchdogReceiver.kt
│       │   ├── service/ReelsBlockerAccessibilityService.kt
│       │   ├── service/MonitorForegroundService.kt
│       │   ├── ui/MainActivity.kt
│       │   └── util/{Constants,NodeUtils,AccessibilityUtils,WatchdogScheduler}.kt
│       ├── res/  (layout, values, drawable, xml, mipmap)
│       └── AndroidManifest.xml
├── gradle/wrapper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew / gradlew.bat
└── gradle.properties
```
