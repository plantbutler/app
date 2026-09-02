# plantbutler / app

The Plant Butler Android app: Kotlin + Jetpack Compose. It talks only to the backend (over the
tailnet) — the garden as a list with an environment card and a health strip, one pot's form
(names, mapping, thresholds, mode), a recalibration wizard (sensor in the air, tap; in water,
tap), one pot's last day as a curve with a water-now button through the backend's hand-off,
and the learning loop (approve a proposal, judge the dose).

What it does and in which order is in the [plan](https://github.com/plantbutler/plan);
the decisions it is built on are in the [umbrella](https://github.com/plantbutler/plantbutler/blob/main/DECISIONS.md).
