# Working on the app

[README.md](README.md) says what this is, how to build it, how to point it at a backend and where
each thing lives. The umbrella's
[AGENTS.md](https://github.com/plantbutler/plantbutler/blob/main/AGENTS.md) holds the
project-wide rules; its
[DECISIONS.md](https://github.com/plantbutler/plantbutler/blob/main/DECISIONS.md) entries 3 (why
this is a native app), 5 (the backend decides, not the phone) and 19 (the address and the token
belong to the device, not the build) are what this code keeps.

## The rule that shapes every file

Logic that can be tested lives in a file named after the thing, not in a screen. A `*Screen.kt`
holds Compose code and no decisions; `Garden.kt`, `Chart.kt`, `Water.kt`, `Doses.kt`, `Care.kt`
and `Photos.kt` hold the decisions, so the tests reach them on an ordinary Java virtual machine
with no emulator anywhere. When a screen needs to decide something, the decision moves out and
the screen calls it.

The view model is one class with three state flows, split across files by concern: the flows and
the refresh are in `GardenViewModel.kt`, and each `Model*.kt` adds that class's actions for one
concern as extensions. Keep it one class: a second flow would have to be combined with the
others, and every screen reads the same one.

## Traps

- A fresh clone has no `local.properties`, and Gradle stops with "SDK location not found" before
  it compiles anything. Write it or set `ANDROID_HOME`.
- The tests need `JAVA_HOME` pointing at Java 17.
- A photograph from the camera carries its orientation in a tag, and re-encoding drops the tag.
  Turn the picture upright before sending it, or every portrait photograph arrives on its side
  for good.
- The network client quotes an illegal header value back inside its exception message, and for
  one header that value is the token. Never put such a message on screen.
- Two clocks: the phone's and the backend's. A phone running behind would call every fresh
  reading stale, so "now" is never earlier than the newest report the backend has.
- A refresh must not throw away a half-typed form. The open form is its own state, beside the
  garden, for that reason.
- Adding a field to the pot form touches the field table, the wire fields and the screen's
  handling of that key. Change one and the field looks saved while nothing stores it.

## Driving it on a phone

`adb` reaches the phone over the private network. The wireless-debugging port changes whenever
that setting is toggled and dies on its own within the hour, so ask Jacopo for it and install
straight away rather than retrying. A development build is pointed at a laptop backend by the
laptop's address on the local network, never `localhost`. Back leaves a pot, the history or the
setup screen; from the garden it leaves the app; during calibration it cancels the wizard.

## Comments

- A file starts with one line saying what it holds.
- A comment says why, not what. Keep Android and Kotlin traps, clock invariants, units, security
  reasons and the reason a control refuses.
- No dates, backend version numbers, decision numbers, pitch names, pull request numbers,
  reviewer names or history. Keep the fact, drop the provenance.
- In a test, a comment says what a case proves only when its name does not.
