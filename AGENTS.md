# Working on the app

Not started (2026-08-30). Read the umbrella's
[AGENTS.md](https://github.com/plantbutler/plantbutler/blob/main/AGENTS.md) and
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

## When you start

Write the toolchain versions (Android Studio, JDK, compileSdk, Compose BOM) into this file the
day you pick them, so the next session does not rediscover them.
