# Working on the app

Not started (2026-08-30). Read the umbrella's
[AGENTS.md](https://github.com/plantbutler/plantbutler/blob/main/AGENTS.md) (on this machine: `~/projects/plant-butler/AGENTS.md`) and
[DECISIONS.md](https://github.com/plantbutler/plantbutler/blob/main/DECISIONS.md) first.

## What it is going to be

Native Android, Kotlin + Jetpack Compose. It talks only to the backend, over the LAN, with
cleartext explicitly allowed for the LAN address; it never speaks to the board. The backend URL
and token come from an untracked local file. Alerts do not go through the app in v1 — they arrive
by ntfy.

## Pitches, in order (titles in the plan)

1. **Hello, pots** — toolchain first (Android Studio and an SDK are not installed on the Mac;
   `adb` is), then Hello World on the phone, then one list of pots with % and last-seen. If it
   runs long, the fallback scope is a list of the last N readings. No DI framework, no MVI, no
   offline cache.
2. **Water now and a chart** — one detail screen: the pot's moisture history and a water-now
   button that queues a command through the backend's hand-off, status "queued/done", honestly
   labelled "up to about three minutes". Pick a chart library or draw a Canvas polyline on the
   first evening and live with it.
3. **Manage the garden** — names, thresholds, channel/valve/plant mapping, recalibration capture
   (sensor in air, tap; in water, tap), controller health. One list, one detail, one wizard.

Not in v1: login, offline mode, Play Store, push via FCM, widgets, photos, theming.

## Toolchain (picked 2026-09-01, CLI-only)

No Android Studio: `brew install openjdk@17` and `brew install --cask
android-commandlinetools` (SDK root `/opt/homebrew/share/android-commandlinetools`,
`sdk.dir` in the untracked `local.properties`), platforms;android-35 +
build-tools via sdkmanager. Gradle 8.11.1 by wrapper, AGP 8.7.3, Kotlin 2.1.0,
Compose BOM 2024.12.01, minSdk 31, target/compileSdk 35, JDK 17
(`JAVA_HOME=/opt/homebrew/opt/openjdk@17`). Build: `./gradlew test assembleDebug`;
the APK lands in `app/build/outputs/apk/debug/`. The backend URL and token bake
into BuildConfig from the untracked `butler.properties` (see the .sample).
Install Android Studio later if the IDE is ever wanted; nothing here needs it.
