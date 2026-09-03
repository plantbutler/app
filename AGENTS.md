# Working on the app

Three pitches in (2026-09-02): "Hello, pots", "Manage the garden" and "Water now and a chart" — the v1 app. Read the umbrella's
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
2. **Water now and a chart** — done (app#3, built after 3). The pot's last day as a Canvas
   polyline above the form, and a water-now button through the backend's hand-off with a
   status line worded to it. See "What is here".
3. **Manage the garden** — done (app#2). Names, thresholds, channel/valve/plant mapping,
   recalibration capture, controller health, approve/verdict. See "What is here".

Not in v1: login, offline mode, Play Store, push via FCM, widgets, photos, theming.

## What is here (2026-09-02)

`app/src/main/java/garden/butler/app/`:

- `Backend.kt` — the wire models (`Pot` with its `pot-xxxxxx` id, its nickname, `species` and
  every other pots column, `Proposal`, `LastDose`,
  `Health` with `next_default` and per-controller `command`), `Json { ignoreUnknownKeys }`,
  and the one class that touches the network: GETs, and `post()` for `/pot`, `/approve`,
  `/verdict`, `/interval` with the `X-Token` header. Any non-200 throws `Refused(code, text)`
  carrying the backend's text verbatim ("refused: …", "busy: …", "try again: …"); the app
  shows it as is. The backend's merged-row checks are the validation.
- `Garden.kt` — pure lines and splits: `splitGarden` (pots / env / disabled + the health it
  came with), `problems` (raised alerts + app-side silence with `next_default`), the
  controller line, proposal and dose lines, `needsVerdict` (a dose acked between 30 min and
  48 h ago with no verdict), `learningGaps` (what the rules need, including the board's
  `float=1 pos=ok`), `potById` (the key everything navigates by; an empty id is never a key)
  and `potNamed` (only the two places that have a name and not an id).
- `PotForm.kt` — the form is one `Map<String,String>` draft diffed against the stored pot:
  `wireFields`, `tokenize` (Unicode whitespace → `_`, values are single words on the wire),
  `changedFields` (only what changed is posted — a partial upsert), `emptiedFields` (the wire
  cannot clear a field; the form says so and refuses to save), `potBody(id, name, changed)` (an
  id makes it an edit and the name rides along, which is how a rename travels; no id makes it a
  create and the backend mints one), `renamed`, `nameTaken(garden, name, selfId)` (a pot keeping
  its own name is not a clash).
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
- `Chart.kt` — the moisture chart as data: `chartSeries` (segments; the pen lifts across a
  gap longer than max(2 buckets, the silence threshold)), `moisturePct` (the backend's
  formula operation for operation, `Math.rint` for Python's banker's rounding, so the curve
  agrees with the title line), `chartRange`, `yTicks`/`timeTicks` (wall-clock hours in the
  phone's zone), `chartCaption` (window, bucket, count, and whose calibration reads it — the
  one accessible copy of what the canvas shows). Raw comes over the wire (`GET /history`,
  epoch-aligned buckets, the server's `since`/`to` as the axis); % is derived here from the
  pot's current calibration, so a recalibration re-reads the whole curve. Calibrated pots
  plot 0–100 % with a translucent band between the targets; uncalibrated ones plot raw.
- `Water.kt` — the water-now button as pure decisions: `cannotWater` (disabled pot, no
  mapping, no dose, an unsaved controller/outlet/dose edit, a silent board, a busy slot, a
  proposal waiting — in that order), `waterStatus` from `/pots` `last_dose` and `/health`'s
  slot for the one id this form issued (acked → done with the meter, expired → "maybe
  nothing poured, maybe the ack was lost", past four minutes → "no news — check the
  controllers card"), `stillFollowing` (a 15 s refresh only while the fate is open, only
  while RESUMED, measured on the phone clock), `waterDialogText` (the one confirmation:
  counts as today's watering for the rules; NAS or board down means no water).
- `Main.kt` — `App()`: one `when` over `Screen`, the 60 s refresh loop (paused while the
  wizard polls every 2 s), the BackHandler for the wizard. `GardenScreen.kt`, `PotScreen.kt`
  (hero line, the chart, the water row, proposal card, dose card with verdict chips, discard
  dialog; every refresh reloads the open pot's curve too), `CalibrateScreen.kt` (one card per
  state; leaving the foreground cancels the wizard so the restore runs).

`app/src/test/java/garden/butler/app/` — JVM tests only: reducers and lines
(`CalibrationTest`, `ChartTest`, `WaterTest`, `PotFormTest`, `GardenTest`), wire parsing
(`BackendTest`), the HTTP layer against `MockWebServer` (`BackendWireTest`), and the
ViewModel driver against `MockWebServer` with `Dispatchers.setMain` and a settable clock
(`GardenViewModelTest`). The Canvas itself is the one thing without a test: everything it
draws comes from a pure function that has one. No emulator anywhere.

Known limits, on purpose: no clearing a field (`enabled=0` and `mode=manual` cover the real cases), single-sample capture
in the wizard (revisit with the real probe — a median of the last three is a five-line change
in `calStep`), the backend keeps an interval override forever (a process death between
arming and restoring leaves the board at 5 s until the reset chip is tapped). Calibration
readings land in the pot's history like any other, so the wizard shows as a spike on the
chart. A manual dose bypasses cooldown, daily cap, quiet hours and the float/pos gates
(decision #5: the firmware protects) and then counts toward the rules' cooldown and cap;
it lands in the dose card and asks for a verdict like any other. The real board does not
execute commands until "Pump on command": the water row is verified against the fake board.
No watering history and no touch on the chart, per the pitch.

**2026-09-03.** A pot is a `pot-xxxxxx` id, not a name (backend 0.8.0, decision #16). Every
screen keys on it: `Screen.Pot.id`, the list's `key`, the wizard's poll. The nickname is
editable — the name field is on every form, not only a new pot's — and a rename keeps the form,
the curve and an open dose on the same pot. Pots also carry `species`, the field the care
lookup will fill. The two remaining name-keyed checks are deliberate: the wizard asks the
backend whether a pot by that name is still there, and a nickname clash is looked up by name
because that is what would collide.

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
