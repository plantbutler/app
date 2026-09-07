# Plant Butler app

The phone half of a hobby plant-watering system: it shows every plant and how wet its soil is,
waters one by hand, keeps a photograph of each plant over time, and edits what the backend knows.
It talks only to the backend, never to the board. How the three parts fit together is in the
[umbrella README](https://github.com/plantbutler/plantbutler#readme); the words are in its
[glossary](https://github.com/plantbutler/plantbutler/blob/main/GLOSSARY.md).

## Build and install

Needs a Java Development Kit 17 and the Android command-line tools. No Android Studio.

```bash
brew install openjdk@17
brew install --cask android-commandlinetools
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties
```

`local.properties` is gitignored and has to exist, or Gradle stops with "SDK location not found"
before it does anything else. Setting `ANDROID_HOME` instead works too.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew test            # the tests, on this machine, no phone needed
./gradlew assembleDebug   # the app file, at app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android 12 or newer. The internet is the only permission it asks for: a photograph is taken by
handing the job to the phone's own camera app, which needs no permission here.

## Point it at your backend

On first start the app asks for the backend's address and token, checks both against the backend
before storing them, and keeps them in the phone's encrypted store. Nothing is compiled in, so
one build works on any phone against any backend, and moving the backend is a typed line rather
than a rebuild.

`butler.properties.sample` exists only to prefill that screen on a development build. Copy it to
`butler.properties`, which is gitignored, and fill it in. It is optional.

## Screens

| screen | what it shows |
| --- | --- |
| the garden | every plant, its soil moisture, the room readings, and anything wrong |
| one pot | the moisture curve over a day, a week or a month, a water button, the photographs, and what is known about the species |
| the pot form | the name, the wiring, the target range, the kind of plant, the soil, the sizes |
| the watering history | every dose, why it happened and how it turned out |
| the calibration wizard | hold the sensor in the air, then in water, so the backend learns what this pot's dry and wet readings are |
| setup | where the backend is and what its token is |

## Files

The app never talks to the board. Some of what it shows is about a board, though: whether one has
stopped itself and needs a person, and whether its water tank was refilled. Those go through the
backend like everything else.

Everything is in one package, `garden.butler.app`. Three kinds of file, and the kind is the name:

- `*Screen.kt` is what you see: Compose code, no logic worth testing.
- A bare noun (`Garden.kt`, `Chart.kt`, `Water.kt`, `Doses.kt`, `Care.kt`, `Photos.kt`) is pure
  logic with no Android in it, so the tests can reach it on an ordinary Java virtual machine.
- `Backend.kt` is the only file that speaks to the network.

| file | what it holds |
| --- | --- |
| `GardenViewModel.kt` | the state flows, the refresh, the clocks; one class, one of each flow |
| `Model*.kt` | that class's actions, by concern: the calibration wizard, the setup screen, photographs, the species lookup, the watering history, and the buttons that resume a stopped board or record a refill |
| `State.kt` | the shapes the screens read: what the app is showing and which screen is up |
| `Backend.kt` | the wire: what is sent, what comes back |
| `Settings.kt` | the address and the token, in the phone's encrypted store |
| `Cache.kt` | the last garden seen, so a phone off the network still shows something |
| `Main.kt` | the one activity, and the choice of which screen is up |
| `PhotoFile.kt` | turning a camera picture upright and small enough to send |
| `Care.kt` | what the backend says about a species, and the target range it offers for a pot |
| `Widgets.kt` | the few small pieces every screen draws the same way |

Where to go: a new screen is a case in `State.kt` plus its own `*Screen.kt` and a branch in
`Main.kt`; a new backend call is a method in `Backend.kt` and an action in the `Model*.kt` file
for its concern; a new field on the pot form is the field table in `PotForm.kt`.

## Tests

`./gradlew test` runs everything on this machine, with a fake backend on a real socket where the
network matters. There are no tests that need a phone or an emulator. Each file is named for what
it tests, and `Fixtures.kt` holds the builders they share. What only a device can prove, the
camera and a first start with nothing stored, is checked by hand.
