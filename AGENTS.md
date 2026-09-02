# Working on the app

Two pitches in (2026-09-02): "Hello, pots" and "Manage the garden". Read the umbrella's
[AGENTS.md](https://github.com/plantbutler/plantbutler/blob/main/AGENTS.md) (on this machine: `~/projects/plant-butler/AGENTS.md`) and
[DECISIONS.md](https://github.com/plantbutler/plantbutler/blob/main/DECISIONS.md) first.

## What it is

Native Android, Kotlin + Jetpack Compose. It talks only to the backend (over the tailnet, see
below), with cleartext explicitly allowed; it never speaks to the board. The backend URL and
token come from an untracked local file. Alerts do not go through the app in v1 — they arrive
by ntfy. No DI framework, no MVI, no navigation library, no offline cache: one ViewModel, two
state flows, pure functions for every decision, JVM tests only.

## Pitches, in order (titles in the plan)

1. **Hello, pots** — done (app#1). The list: pots with % and last-seen, `env:*` pots as an
   environment card, the problem strip, 60 s auto-refresh, Ready surviving a failed refresh.
2. **Water now and a chart** — one detail screen: the pot's moisture history and a water-now
   button that queues a command through the backend's hand-off, status "queued/done", honestly
   labelled "up to about three minutes". The pot screen and the `Screen` flow already exist
   (pitch 3 built them); this pitch slots a chart and a button above the form. Pick a chart
   library or draw a Canvas polyline on the first evening and live with it.
3. **Manage the garden** — done (app#2). Names, thresholds, channel/valve/plant mapping,
   recalibration capture, controller health, approve/verdict. See "What is here".

Not in v1: login, offline mode, Play Store, push via FCM, widgets, photos, theming.

## What is here (2026-09-02)

`app/src/main/java/garden/butler/app/`:

- `Backend.kt` — the wire models (`Pot` with every pots column, `Proposal`, `LastDose`,
  `Health` with `next_default` and per-controller `command`), `Json { ignoreUnknownKeys }`,
  and the one class that touches the network: GETs, and `post()` for `/pot`, `/approve`,
  `/verdict`, `/interval` with the `X-Token` header. Any non-200 throws `Refused(code, text)`
  carrying the backend's text verbatim ("refused: …", "busy: …", "try again: …"); the app
  shows it as is. The backend's merged-row checks are the validation.
- `Garden.kt` — pure lines and splits: `splitGarden` (pots / env / disabled + the health it
  came with), `problems` (raised alerts + app-side silence with `next_default`), the
  controller line, proposal and dose lines, `needsVerdict` (a dose acked between 30 min and
  48 h ago with no verdict), `learningGaps` (what the rules need, including the board's
  `float=1 pos=ok`), `potNamed`.
- `PotForm.kt` — the form is one `Map<String,String>` draft diffed against the stored pot:
  `wireFields`, `tokenize` (Unicode whitespace → `_`, values are single words on the wire),
  `changedFields` (only what changed is posted — a partial upsert), `emptiedFields` (the wire
  cannot clear a field; the form says so and refuses to save), `potBody`, `nameTaken`.
- `Calibration.kt` — the recalibration wizard as a pure reducer `calStep(state, event, nowS)`:
  SpeedingUp → Air → Water → Review → Saving → Finished, with Stalled (Retry / Continue
  slowly / Cancel) when the board never speeds up. Tap captures the newest reading only when
  it is fresh (phone-clock freshness, `seenS`) and newer than the step began, so one report
  never serves both endpoints. `canCalibrate` refuses without a mapping, unless the pot is in
  manual (the rules would water a sensor held in the air), and on a silent board.
- `GardenViewModel.kt` — `state` (Loading / Trouble / Ready) and `screen` (List / Pot /
  Calibrate). `refresh()` is single-flight and coalesces a request made mid-flight. Every
  action refreshes afterwards, success or failure. Async outcomes land only on the form they
  were issued from. The wizard driver arms the board with `next=5`, decides on a fresh fetch,
  never takes a standing 5 s as the pace to restore, and restores `prevNextS ?: 0` on every
  exit — the controllers card's reset chip is the recovery path when that fails or the
  process died.
- `Main.kt` — `App()`: one `when` over `Screen`, the 60 s refresh loop (paused while the
  wizard polls every 2 s), the BackHandler for the wizard. `GardenScreen.kt`, `PotScreen.kt`
  (form, proposal card, dose card with verdict chips, discard dialog), `CalibrateScreen.kt`
  (one card per state; leaving the foreground cancels the wizard so the restore runs).

`app/src/test/java/garden/butler/app/` — 93 JVM tests: reducers and lines (`CalibrationTest`,
`PotFormTest`, `GardenTest`), wire parsing (`BackendTest`), the HTTP layer against
`MockWebServer` (`BackendWireTest`), and the ViewModel driver against `MockWebServer` with
`Dispatchers.setMain` (`GardenViewModelTest`). No emulator anywhere.

Known limits, on purpose: no rename (name is the key; disable and create instead), no
clearing a field (`enabled=0` and `mode=manual` cover the real cases), single-sample capture
in the wizard (revisit with the real probe — a median of the last three is a five-line change
in `calStep`), the backend keeps an interval override forever (a process death between
arming and restoring leaves the board at 5 s until the reset chip is tapped). Calibration
readings land in the pot's history like any other.

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

Deployed reality (2026-09-01): the backend is reached over Tailscale — the NAS
is device `ciccia` on the tailnet (Tailscale runs as a docker container on the
NAS, state in /volume1/docker/tailscale). butler.properties points at the
tailnet IP, so the app works from any network as long as the phone runs the
Tailscale app. The phone is on the tailnet too: `adb connect <phone tailnet
ip>:<wireless debugging port>` works from anywhere after the one-time pairing.

## Trying it without the board

Run the backend on the laptop and a fake board against it, point a debug build at the
laptop's LAN address, and every screen including the wizard works for real — the fake board
obeys `next=`, executes commands and acks them:

```bash
cd ../backend
BUTLER_TOKEN=dev BUTLER_DB=/tmp/demo.db BUTLER_QUIET=0-0 \
  uv run uvicorn butler:create_app --factory --host 0.0.0.0 --port 8000 &
python3 fake_device.py --token dev --url http://<laptop lan ip>:8000 --float 1 --pos ok &
# butler.properties: url=http://<laptop lan ip>:8000 token=dev; then assembleDebug + adb install
```

Mind that `openproj serve` sits on 127.0.0.1:8000 on this laptop: reach the demo backend by
its LAN address, not localhost. A screen can be driven from the shell with `adb shell input
tap/text` and `uiautomator dump` for the node bounds; Back on the list exits the app.
