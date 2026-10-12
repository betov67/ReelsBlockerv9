# ReelsBlocker v9

A lightweight Android AccessibilityService that blocks Instagram's main
Reels/Explore feed while leaving Stories, DM-shared Reels, and Profile Reels
fully usable.

## Build

Requires **JDK 17** and **Android SDK Build-Tools 36 / compileSdk 37**
(installed automatically by Android Studio's SDK Manager on first sync).

```bash
./gradlew assembleDebug
```

The first run downloads Gradle 9.6.0 itself via the wrapper, then Android
Gradle Plugin 9.4.0 and Kotlin 2.4.0 — all from Google's and Maven Central's
repositories, as configured in `settings.gradle.kts`. The app module has
**zero third-party dependencies**: it only uses Android framework APIs, so
there's nothing else to resolve.

> This project was scaffolded in a sandboxed environment without access to
> `dl.google.com` / `services.gradle.org`, so the Gradle build itself could
> not be executed end-to-end here. Every file was hand-reviewed (XML
> well-formedness, resource cross-references, brace/paren balance), but
> please run a Gradle sync in Android Studio as your first step.

## Install & enable

1. Build and install the debug APK (`applicationId`
   `com.reelsblocker.app.debug`) on a device with Instagram installed.
2. Open **ReelsBlocker**, tap **Open Accessibility Settings**, and turn the
   service on. Android requires this manual step for every accessibility
   service — no app can enable it for itself.
3. Optionally tap **Exempt From Battery Optimization**. On MIUI, OneUI,
   ColorOS, EMUI and similar OEM skins this is usually the difference
   between the service surviving overnight and getting silently killed.

## How the blocking logic works

See `ReelsBlockerAccessibilityService.kt` for the fully-commented
implementation. In short, on every Instagram window-state / content-changed
event:

1. **Story bypass** — if `reel_viewer`, `reel_viewer_image_view`,
   `story_feed_holder`, or `ReelViewerFragment` is found anywhere in the
   active screen's node tree, the service does nothing at all. Not even a
   back press. Stories are never touched.
2. **DM / Profile bypass window** — tapping anywhere inside a screen that
   contains `direct_thread` / `com.instagram.direct` / `message_content`
   (a DM thread) or `profile_tab` / `user_profile` / `profile_header` (a
   profile) arms a 3-second window. If the Clips player
   (`clips_viewer` / `clips_video_container` / `ClipsViewerFragment`) opens
   inside that window, the whole viewing session is allowed — not just the
   first 3 seconds of it — until the user leaves the player screen.
3. **Feed/Explore blocking** — if the Clips player opens with no bypass
   window active, `performGlobalAction(GLOBAL_ACTION_BACK)` is fired
   immediately (debounced to one call per 800ms).

All Instagram view-id/class-name identifiers live in one place,
`util/Constants.kt`, matched as case-insensitive substrings specifically so
a minor Instagram internal rename doesn't silently break a whole category.

**Known limitation:** if Instagram's own Clips player lets a user swipe from
an allowed Reel straight into its "up next" suggestions without the screen
ever technically leaving `ClipsViewerFragment`, those suggestions stay
viewable too — the accessibility tree alone can't reliably distinguish "same
screen, different underlying video" from "still watching the one Reel you
opened." This is called out in the service's code comments as well.

## Stability (part 4 of the spec)

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
`STORY_BYPASS`, `DM_REEL_ALLOWED`, `PROFILE_REEL_ALLOWED`,
`FEED_REEL_BLOCKED`, plus diagnostic lines for service lifecycle and the
watchdog. Tail it with:

```bash
adb logcat -s ReelsBlockerA11y
```

## Project layout

```
ReelsBlockerV9/
├── app/
│   └── src/main/
│       ├── java/com/reelsblocker/app/
│       │   ├── ReelsBlockerApplication.kt
│       │   ├── model/BypassSource.kt
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
