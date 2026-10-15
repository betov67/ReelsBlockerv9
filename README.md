# ReelsBlocker v11

A lightweight Android AccessibilityService that enforces a "single-video"
policy on Instagram's Reels player: the first Reel you open (from anywhere
— feed, Explore, a DM, a profile) always plays freely, but swiping to a
second one (or Instagram auto-advancing to one) immediately closes the
player. Stories are a hard exception and are never touched.

## What changed in v11

v9/v10 classified screens on **both** `TYPE_WINDOW_STATE_CHANGED` and
`TYPE_WINDOW_CONTENT_CHANGED`. That was the real defect: Instagram fires
`TYPE_WINDOW_CONTENT_CHANGED` constantly while a Reel simply plays (caption
text, like-count, progress bar), and some of those ticks were getting
misread as "the user left the Clips player" — resetting the block/allow
state far more often than intended.

v11 fixes this at the root:

- Screen classification now reacts **only** to `TYPE_WINDOW_STATE_CHANGED`,
  which fires on genuine screen/window transitions, not on every minor
  content update. `TYPE_WINDOW_CONTENT_CHANGED` isn't requested at all
  anymore (see `res/xml/accessibility_service_config.xml`).
- A 2-second **scroll-immunity window** starts the moment the Clips player
  is entered. Instagram fires its own internal scroll/layout events while
  positioning the first video — without this window, that system-generated
  scroll gets mistaken for the user swiping, and the first video never
  gets a chance to play at all.
- State (`isInReelsPlayer` / `reelOpenTimeMs`) only ever resets on an
  explicit, genuine exit from the Clips player — never on a stray content
  tick.

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

1. **Story bypass (absolute, ignored outright)** — if `reel_viewer`,
   `reel_viewer_image_view`, `story_feed_holder`, or `ReelViewerFragment` is
   found anywhere in the active screen's node tree, the service does
   nothing at all — not even a back press, and it doesn't touch any of the
   Reels-player state either.
2. **Enter the Clips player** (`clips_viewer` / `clips_video_container` /
   `ClipsViewerFragment` detected on `TYPE_WINDOW_STATE_CHANGED`) —
   `isInReelsPlayer` goes true and `reelOpenTimeMs` is stamped. Nothing
   else happens; the first video plays completely uninterrupted.
3. **Scroll-immunity window** — any `TYPE_VIEW_SCROLLED` event arriving
   less than 2 seconds after `reelOpenTimeMs` is ignored outright (see
   "What changed in v11" above for why).
4. **No second Reel** — a `TYPE_VIEW_SCROLLED` event arriving 2+ seconds
   after entry means the pager actually moved to another video (manual
   swipe, or Instagram's own auto-advance — both move the same underlying
   scrollable view, so one signal covers both cases).
   `performGlobalAction(GLOBAL_ACTION_BACK)` fires immediately (debounced
   to one call per 800ms, since a single swipe gesture fires several
   scroll events in a row as the drag progresses).
5. **Exit reset** — the moment `TYPE_WINDOW_STATE_CHANGED` detects a screen
   that is neither the Clips player nor a Story, `isInReelsPlayer` and
   `reelOpenTimeMs` reset. The next Reel opened gets its own fresh immunity
   window and its own free video.

All Instagram view-id/class-name identifiers live in one place,
`util/Constants.kt`, matched as case-insensitive substrings specifically so
a minor Instagram internal rename doesn't silently break detection.

**Known limitation:** the immunity window is a fixed 2 seconds. On a very
slow device/network where the first video genuinely takes longer than that
to finish its initial layout pass, a late-arriving internal scroll event
could in theory be misread as a user swipe. This hasn't been observed, but
is an inherent trade-off of using a fixed timeout rather than detecting
"layout has settled" directly (which Instagram's closed internals don't
expose a reliable signal for).

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
`STORY_BYPASS`, `REEL_PLAYER_ENTERED`, `SCROLL_IGNORED_WITHIN_IMMUNITY_WINDOW`,
`SECOND_REEL_SWIPE_BLOCKED`, `REEL_PLAYER_EXITED`, plus diagnostic lines for
service lifecycle and the watchdog. Tail it with:

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
ReelsBlockerV11/
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
