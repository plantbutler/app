# Working on the app

Three pitches in (2026-09-02): "Hello, pots", "Manage the garden" and "Water now and a chart" — the v1 app. Read the umbrella's
[AGENTS.md](https://github.com/plantbutler/plantbutler/blob/main/AGENTS.md) (on this machine: `~/projects/plant-butler/AGENTS.md`) and
[DECISIONS.md](https://github.com/plantbutler/plantbutler/blob/main/DECISIONS.md) first.

## What it is

Native Android, Kotlin + Jetpack Compose. It talks only to the backend (over the tailnet, see
below), with cleartext explicitly allowed; it never speaks to the board. The backend URL and token
are asked for on first start and kept in the phone's encrypted store (2026-09-04); an untracked
`butler.properties` still prefills that screen for a development build, but is optional and no
longer the only source. Alerts do not go through the app in v1 — they arrive
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
4. **I refilled the tank** — done (app#17, this PR): a refilled chip per board, and a stopped
   board's card with why and a Resume behind a confirmation.

Since 2026-09-04 the form explains itself: an ⓘ beside every field opens a one-sentence dialog,
and the two sizes are measurements — `plant height (cm)` and `pot diameter (cm)` — which the
backend reads as the demand on a water buffer and the buffer itself. `kind of plant` and `soil`
are dropdowns over closed sets (`PLANT_KINDS`, `SOIL_KINDS` in `PotForm.kt`), because both feed
the target band and free text in either used to match nothing and cost points silently. A picked
field renders `labelFor()`, which falls back to the raw wire word: a value written before these
were sets, or by a backend newer than this build, shows as itself rather than crashing the form.

The controller is an **integer** (2026-09-05), 0..255, matching the backend and the firmware's
`PB_CONTROLLER`. Board 0 is a real board and is what a new pot's form fills in
(`DEFAULT_CONTROLLER`), so nothing anywhere may test a controller for truthiness — `boardName()`
is how it reaches a person, because a bare "0 has gone silent" reads like a truncated sentence.
Each garden row also shows the pot's newest photograph, from `Pot.photo` (an id; the bytes come
from the same cached `GET /photo/<id>` the strip uses). A pot with no picture gets no placeholder.

A plant can die (2026-09-05). `Pot.enabled` is `Pot.status`, and the app tests `== ALIVE` and
never `!= GRAVEYARD`, so a status word a newer backend invents lands in the Graveyard section
rather than in the watering list. Burying and reviving are a long press on a garden row;
deleting is a button at the foot of the pot form behind a dialog that names the plant and lists
what goes, and it is refused while the garden is a cached memory. `deletePot()` pops the form
whatever happens, because `PotScreen` keeps rendering from its own snapshot when a pot vanishes
and would otherwise leave a working form whose Save posts an id that is gone. The chart asks
`GET /history?pot=`, so an unwired pot — one just back from the graveyard — still has its curve.

Not in v1: login, Play Store, push via FCM, widgets, theming. Photographs of your own plants
arrived on 2026-09-04: a pot keeps its own strip, oldest first, and the care source's picture of
the species sits beside them as the reference. Offline is a read-only
cache since 2026-09-03 — the last answer, stamped with its age, and every write refused while it
is on screen. Nothing is queued to send later.

## What is here (2026-09-02)

`app/src/main/java/garden/butler/app/`:

- `Backend.kt` — the wire models (`Pot` with its `pot-xxxxxx` id, its nickname, `species` and
  every other pots column, `Proposal`, `LastDose`,
  `Health` with `next_default` and per-controller `command`, `Latch` and the tank fields on
  `ControllerHealth` (`latched`, `last_refill`, `err`, `err_ts`, `retired`, `pos_ok_seen`)),
  `Json { ignoreUnknownKeys }`, and the one class that touches the network: GETs, `post()` for
  `/pot`, `/approve`, `/verdict`, `/interval` with the `X-Token` header, and `refill()` and
  `resume()` (`POST /refill` and `POST /resume`, body `c=<n>`). Any non-200 throws
  `Refused(code, text)` carrying the backend's text verbatim ("refused: …", "busy: …", "try
  again: …"); the app shows it as is. The backend's merged-row checks are the validation.
- `Garden.kt` — pure lines and splits: `splitGarden` (pots / env / disabled + the health it
  came with), `problems` (raised alerts + app-side silence with `next_default`, a stopped
  board, and the pos line gated on `pos_ok_seen`), the latch and stale alert descriptions, the
  controller line with the STOPPED and retired marks on it, `latchLine`/`latchReason` (the
  board's reason in a person's words), proposal and dose lines, `needsVerdict` (a dose acked
  between 30 min and 48 h ago with no verdict), `learningGaps` (what the rules need, including
  the board's `float=1 pos=ok`), `potById` (the key everything navigates by; an empty id is
  never a key) and `potNamed` (only the two places that have a name and not an id).
- `PotForm.kt` — the form is one `Map<String,String>` draft diffed against the stored pot:
  `POT_FIELDS` (key, label, keyboard, and the sentence behind the ⓘ — every field has one, since
  seventeen boxes labelled in wire names is a form only its author can fill in), `PLANT_KINDS`
  (the six words the backend accepts, and separately what to call them on screen: a label change
  must never become a wire change), `withKind`/`suggestedKind` (a looked-up kind fills the field
  only while it is empty; a kind already there is a human's answer and is offered a tap instead),
  `cmText` (14.0 shows as 14, so opening a form does not make it dirty),
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
  never serves both endpoints. Each endpoint is the median of the reports that step has seen, at
  most three (`tapSamples`, `medianRaw`): one noisy sample used to set a pot's whole scale until
  somebody recalibrated. Tapping with fewer than three is allowed and the wizard says what it
  would take (`settleLine`). `canCalibrate` refuses without a mapping, unless the pot is in
  manual (the rules would water a sensor held in the air), and on a silent board.
- `GardenViewModel.kt` — `state` (Loading / Trouble / Ready) and `screen` (List / Pot /
  Calibrate). `refresh()` is single-flight and coalesces a request made mid-flight. Every
  action refreshes afterwards, success or failure. Async outcomes land only on the form they
  were issued from. The wizard driver arms the board with `next=5`, decides on a fresh fetch,
  never takes a standing 5 s as the pace to restore, and restores `prevNextS ?: 0` on every
  exit — the controllers card's reset chip is the recovery path when that fails or the
  process died. `refill`/`resume` are two more actions, in `resetInterval`'s shape.
- `Chart.kt` — the moisture chart as data: `ChartWindow` (day / week / month, each with the
  bucket that keeps the point count near a day's 288 — a month at five-minute buckets would be
  8640 points and the backend refuses over 2016), `windowTicks` (hours across a day, dates across
  a week or a month), `scrubbed`/`scrubLabel` (the sample nearest the finger and its own time —
  never an interpolation, which would be a reading that never happened), `chartSeries` (segments; the pen lifts across a
  gap longer than max(2 buckets, the silence threshold)), `moisturePct` (the backend's
  formula operation for operation, `Math.rint` for Python's banker's rounding, so the curve
  agrees with the title line), `chartRange`, `yTicks`/`timeTicks` (wall-clock hours in the
  phone's zone), `chartCaption` (window, bucket, count, and whose calibration reads it — the
  one accessible copy of what the canvas shows). Raw comes over the wire (`GET /history`,
  epoch-aligned buckets, the server's `since`/`to` as the axis); % is derived here from the
  pot's current calibration, so a recalibration re-reads the whole curve. Calibrated pots
  plot 0–100 % with a translucent band between the targets; uncalibrated ones plot raw.
- `Doses.kt` — the watering history as words (in a person's words, not the wire's: the board
  sends `ack=`, the screen says "confirmed" — nobody should have to know the protocol to read
  their own garden): `doseHistoryLine`, `doseTrouble` (the row worth a
  second look — expired, failed, or the meter counting less than half, which is the backend's own
  `2 * flow_ml < ml` rather than a second threshold that could disagree with it in public),
  `doseWho` (an unattributable dose says so rather than borrowing the name of whoever hangs on
  that hose now), `doseSource`, `DOSES_LIMIT`.
- `Cache.kt` — the last good `/pots` and `/health` as one JSON file in the app's own storage, so
  there is something to look at off the tailnet. Whole `Pot`s, never anything derived: a cached
  percentage would be re-read through whatever calibration the pot has when the cache is opened,
  and after a recalibration the curve would lie. Every failure is a miss — a cache that throws on
  a half-written file would take the app down for a convenience — and a write goes through a temp
  file, so a kill mid-write leaves the previous good cache. The cache fills a Trouble screen as
  well as an empty one: off the tailnet the network fails fast and usually beats the disk, and
  refusing to load then would blank the app in the one case it exists for. Only a Ready is left
  alone. `pct` is stripped before writing and `potLine` derives it back from the cached raw, so
  nothing derived is ever read through a calibration it was not taken with.
- `Care.kt` — what the lookup found, as words: `careLine` (the source's own 0-10 light and
  humidity scales, said to be its own — ours is a line between dry air and tap water and a 7 here
  is not a 7% there), `commonName` (dropped when it only repeats the binomial), `adviceLine` (the
  offered band, numbers first), `betterName` (the accepted name when it is not what was typed — a
  synonym redirected or a spelling corrected, the one case worth a button) and
  `normaliseSpecies`, the same folding the backend's cache key uses.
- `CareScreen.kt` — `SpeciesPanel` under the species field: Look up, then either the plant with
  its photograph, common name and numbers, or a shortlist of photographs to tap when no name
  could be placed (a common name, another language, a typo past GBIF). Tapping one fills the
  field and asks again, which resolves exactly. `AdviceCard` is the offered band with Apply and
  Not now: Apply is an ordinary `POST /pot` with the two numbers, so nothing writes a watering
  number without a person tapping it, and Not now is remembered against those numbers so a repot
  or a change of season asks again. The photographs are the app's only images (Coil).
- `Water.kt` — the water-now button as pure decisions: `cannotWater` (disabled pot, no
  mapping, no dose, an unsaved controller/outlet/dose edit, a silent board, a stopped board,
  a busy slot, a proposal waiting — in that order), `waterStatus` from `/pots` `last_dose` and
  `/health`'s slot for the one id this form issued (acked → done with the meter, expired → "maybe
  nothing poured, maybe the ack was lost", past four minutes → "no news — check the
  controllers card"), `stillFollowing` (a 15 s refresh only while the fate is open, only
  while RESUMED, measured on the phone clock), `waterDialogText` (the one confirmation:
  counts as today's watering — a consequence of the tap, not a warning about failures that have
  not happened; the status line under the button says so if one does).
- `DosesScreen.kt` — the watering history, one pot's or the garden's, over the form it was
  opened from so Back gives a half-typed edit back. The rows that went wrong carry their own
  line in the error colour instead of being filtered out, and the list says it is the last N
  rather than everything, since the commands table is never pruned.
- `Settings.kt` — where the butler is, as this phone holds it. `ButlerConfig` (whose toString
  never prints the token — a data class in a state flow is one crash report away from a log line),
  `normaliseUrl` (a missing scheme becomes **http**, never https: the NAS is plain HTTP on the
  tailnet and the laptop is plain HTTP on the LAN, so cleartext has to keep working), `urlProblem`
  (OkHttp's own parser, since it is the one that will have to dial it), `tokenProblem` (whitespace
  is a wrong token, because the backend compares byte for byte), and the four-way `Probe` with
  `readHello`/`probeLine`. `EncryptedConfigStore` is the store itself: EncryptedSharedPreferences,
  `commit()` not `apply()`, and a store that will not decrypt (a restore onto another device, a
  wiped keystore) is deleted and asked for again rather than thrown — the alternative is an app
  that never starts.
- `SetupScreen.kt` — the address and the token, on first start and from the garden afterwards.
  Nothing here can be checked by looking at it, so Connect makes a real `GET /hello` and the
  sentence under the fields says **which** of three things went wrong: the address, the token, or
  what is listening there. Only one of them is fixed by retyping the token, and telling them apart
  is most of what the screen is for. The token field is dots with a show/hide.
- `Photos.kt` — a pot's own growth history, as decisions: `sampleSize`/`fitted` (the long edge is
  capped at 1600 before anything is uploaded — a phone photo is several megabytes and the NAS
  volume and its backup were never sized for hundreds of them; subsampled on the way out of the
  decoder, so twelve megapixels never arrive whole in memory), `strip` (oldest first, so it reads
  left to right as the plant grew — the wire is newest first like every other list), `speciesBreaks`
  (a pot outlives its plant and nothing records a replant, so the mark is where the species the
  picture was taken under changed; honest about what it cannot see — basil after basil leaves no
  trace, and a null species is "nobody said" rather than "a different plant"), `photoDay`,
  `photoLine`, `fileSize`.
- `PhotoFile.kt` — the bitmap half, Android-only: where the camera writes (one name in the cache,
  reused: the full-size original is worth nothing once it has been shrunk and sent) and
  `shrinkJpeg`, which decodes subsampled, **turns the picture upright from EXIF** — a phone writes
  the sensor's orientation into a tag rather than into the pixels, re-encoding drops it, and
  without this every portrait picture would come back on its side for good — then scales and
  re-encodes. Null on anything unreadable.
- `PhotoStrip.kt` — the strip under the chart, the camera button, and the full-size dialog with
  Delete. The care source's picture of the species sits first and dimmed: it is the reference,
  never a stand-in for a picture of the actual pot. A row the butler reports as `missing` shows the
  gap and says "gone" rather than an image that will not load. Coil keys on the URL and a
  photograph's id is minted once, so nothing is re-downloaded over the tailnet.
- `Main.kt` — `App()`: one `when` over `Screen`, the 60 s refresh loop (paused while the
  wizard polls every 2 s), the BackHandler for the wizard. `GardenScreen.kt`, `PotScreen.kt`
  (hero line, the chart, the water row, proposal card, dose card with verdict chips, discard
  dialog; every refresh reloads the open pot's curve too), `CalibrateScreen.kt` (one card per
  state; leaving the foreground cancels the wizard so the restore runs).

`app/src/test/java/garden/butler/app/` — JVM tests only: reducers and lines
(`CalibrationTest`, `ChartTest`, `WaterTest`, `PotFormTest`, `GardenTest`, `SettingsTest`), wire
parsing (`BackendTest`), the HTTP layer against `MockWebServer` (`BackendWireTest`), the
ViewModel driver against `MockWebServer` with `Dispatchers.setMain` and a settable clock
(`GardenViewModelTest`), the photo strip's decisions (`PhotosTest`), and first start and changing butler against **two** fake butlers on two
sockets (`SetupFlowTest`) — two, because the half of that pitch worth testing only exists when
there are two: whose cache this is, and where a slow answer lands. The Canvas itself is the one thing without a test: everything it
draws comes from a pure function that has one. No emulator anywhere.

Known limits, on purpose: no clearing a field (`status=graveyard` and `mode=manual` cover the real cases), the backend keeps an interval override forever (a process death between
arming and restoring leaves the board at 5 s until the reset chip is tapped). Calibration
readings land in the pot's history like any other, so the wizard shows as a spike on the
chart. A manual dose bypasses cooldown, daily cap, quiet hours and the float/pos gates
(decision #5: the firmware protects) and then counts toward the rules' cooldown and cap;
it lands in the dose card and asks for a verdict like any other. The real board does not
execute commands until "Pump on command": the water row is verified against the fake board.
The chart has no zoom or pan, per the
pitch — three windows and a scrub, nothing continuous. The offline cache holds no history curves,
so a pot opened off the tailnet shows its numbers and no chart.

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
