# I refilled the tank — app implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The garden screen can say "I refilled the tank" and, when the butler has stopped watering a board, show why and resume it on a deliberate tap.

**Architecture:** Wire models in `Backend.kt` grow the fields backend 0.18.0 answers; pure decisions in `Garden.kt`/`Water.kt` say the words; `GardenViewModel` gets two actions in the exact shape of `resetInterval`; the controllers card in `GardenScreen.kt` gets a chip and a card. JVM tests only, as everywhere in this app.

**Tech Stack:** Kotlin 2.1, Jetpack Compose, kotlinx.serialization, OkHttp + MockWebServer, kotlin.test. Build: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew --offline test` (or without `--offline` if a dependency is missing).

**Spec:** `../backend/docs/superpowers/specs/2026-09-05-trust-the-tank-design.md` — D12 is the app; D3–D7 and D11 are the backend contract it reads. On this machine: `/Users/jcanton/projects/plant-butler/.worktrees/bench-owed/backend/docs/superpowers/specs/2026-09-05-trust-the-tank-design.md`.

## Global Constraints

- The wire contract, exact (backend 0.18.0 `GET /health` per-controller fields): `latched` is `null` or `{"since": <ts>, "reason": "contra"|"resetmid"}`; `last_refill` ts or null; `err` string or null; `err_ts` ts or null; `retired` 0|1; `pos_ok_seen` ts or null. `POST /refill` body `c=<n>` answers `refill=<ts>`; `POST /resume` body `c=<n>` answers `resumed=<n>`; a latched `POST /command` answers 409 `refused: board <n> stopped watering (...)` — shown verbatim like every other refusal.
- Every new field defaults, so a phone against 0.17.0 still parses (`Json { ignoreUnknownKeys }` is already set).
- A board is spelt through `boardName()` wherever a person reads it; never a bare integer.
- The reason words, exact: `contra` → "the float said full and the meter saw nothing"; `resetmid` → "it reset with the pump running"; any other reason → the reason itself.
- The resume dialog's sentence, exact: "Only after the tank has been checked and `clear contra` has been typed on the board. The butler will queue water again."
- Actions follow `resetInterval` exactly: `staleRefusal()` first, then `act { ... } { noteOnList.value = it }`; chips and buttons take `enabled = live`.
- Pure functions get tests in the file that already tests their neighbours (`GardenTest`, `WaterTest`, `BackendTest`, `BackendWireTest`, `GardenViewModelTest`); Compose code gets none, and compiles under `./gradlew test`.
- Commit messages end with `🤖 Written by an agent on behalf of @jcanton` as the last line, with no other trailer.

---

### Task 1: The wire — `ControllerHealth` fields, `refill()`, `resume()`

**Files:**
- Modify: `app/src/main/java/garden/butler/app/Backend.kt` (`ControllerHealth`, a new `Latch`, `parseRefillAnswer`, `Backend.refill`, `Backend.resume`)
- Test: `app/src/test/java/garden/butler/app/BackendTest.kt`, `app/src/test/java/garden/butler/app/BackendWireTest.kt`

**Interfaces:**
- Produces: `data class Latch(val since: Long = 0, val reason: String = "")`; on `ControllerHealth`: `latched: Latch?`, `lastRefill: Long?`, `err: String?`, `errTs: Long?`, `retired: Int`, `posOkSeen: Long?`; `fun parseRefillAnswer(answer: String): Long?`; `Backend.refill(controller: Int): Long?`; `Backend.resume(controller: Int): String`.

- [ ] **Step 1: Tests.** In `BackendTest.kt`, beside the existing `/health` parsing test, add:

```kotlin
    @Test
    fun `health carries the tank fields, and a 0_17 backend leaves them defaulted`() {
        val health =
            parseHealth(
                """{"ok": true, "controllers": [
                     {"controller": 0, "last_seen": 5, "latched": {"since": 4, "reason": "contra"},
                      "last_refill": 3, "err": "contra", "err_ts": 4, "retired": 0, "pos_ok_seen": 2},
                     {"controller": 1, "last_seen": 5}
                   ]}""",
            )
        val (latched, plain) = health.controllers
        assertEquals(Latch(since = 4, reason = "contra"), latched.latched)
        assertEquals(3L, latched.lastRefill)
        assertEquals("contra" to 4L, latched.err to latched.errTs)
        assertEquals(0, latched.retired)
        assertEquals(2L, latched.posOkSeen)
        assertNull(plain.latched)
        assertNull(plain.lastRefill)
        assertNull(plain.err)
        assertEquals(0, plain.retired)
        assertNull(plain.posOkSeen)
    }

    @Test
    fun `the refill answer is a timestamp or nothing`() {
        assertEquals(1757000000L, parseRefillAnswer("refill=1757000000\n"))
        assertNull(parseRefillAnswer("ok\n"))
    }
```

In `BackendWireTest.kt`, beside the `interval` wire test, add:

```kotlin
    @Test
    fun `refill and resume post the board and nothing else`() =
        withServer { server, backend ->
            server.enqueue(MockResponse().setBody("refill=1757000000\n"))
            server.enqueue(MockResponse().setBody("resumed=0\n"))
            assertEquals(1757000000L, backend.refill(0))
            assertEquals("resumed=0", backend.resume(0))
            val refill = server.takeRequest()
            assertEquals("/refill" to "c=0", refill.path to refill.body.readUtf8())
            assertEquals("s3cret", refill.getHeader("X-Token"))
            val resume = server.takeRequest()
            assertEquals("/resume" to "c=0", resume.path to resume.body.readUtf8())
        }
```

- [ ] **Step 2: Run** `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew --offline test --tests 'garden.butler.app.BackendTest' --tests 'garden.butler.app.BackendWireTest'` — compile error on `Latch`.

- [ ] **Step 3: The model.** Above `ControllerHealth`:

```kotlin
/** The butler has stopped watering this board until a person resumes it:
 * when, and the board's own word for why (contra | resetmid). */
@Serializable
data class Latch(
    val since: Long = 0,
    val reason: String = "",
)
```

and on `ControllerHealth`, after `command`:

```kotlin
    val latched: Latch? = null,
    @SerialName("last_refill") val lastRefill: Long? = null,
    /** The board's last safety error token, as it sent it. */
    val err: String? = null,
    @SerialName("err_ts") val errTs: Long? = null,
    val retired: Int = 0,
    /** When the board last said pos=ok; null for one that never has. */
    @SerialName("pos_ok_seen") val posOkSeen: Long? = null,
```

Beside `parseNextAnswer`:

```kotlin
/** `refill=1757000000` as POST /refill answers it; null when it is not that. */
fun parseRefillAnswer(answer: String): Long? =
    answer.trim().removePrefix("refill=").takeIf { it != answer.trim() }?.toLongOrNull()
```

In `class Backend`, after `interval`:

```kotlin
    /** The human says the tank was refilled; the butler records when. */
    fun refill(controller: Int): Long? = parseRefillAnswer(post("/refill", "c=$controller"))

    /** The human says the tank was checked; the butler waters again. Answers
     * `resumed=<n>`, idempotent on a board that was not stopped. */
    fun resume(controller: Int): String = post("/resume", "c=$controller")
```

- [ ] **Step 4: Focused tests green, then the whole suite** (`./gradlew --offline test`), then **commit** `Backend.kt` and the two test files: "The wire learns the tank: latched, last_refill, err, retired, pos_ok_seen, and two posts".

---

### Task 2: The words — controller line, problem strip, water gate

**Files:**
- Modify: `app/src/main/java/garden/butler/app/Garden.kt` (`LATCH_WORDS`, `latchReason`, `latchLine`, `controllerLine`, `problems`, `describeAlert`), `app/src/main/java/garden/butler/app/Water.kt` (`cannotWater`)
- Test: `app/src/test/java/garden/butler/app/GardenTest.kt`, `app/src/test/java/garden/butler/app/WaterTest.kt`

**Interfaces:**
- Consumes: Task 1's fields.
- Produces: `fun latchReason(reason: String): String`; `fun latchLine(c: ControllerHealth, nowS: Long): String`.

- [ ] **Step 1: Tests.** In `GardenTest.kt`, the `controller(...)` helper gains `latched: Latch? = null, retired: Int = 0, posOkSeen: Long? = null` parameters passed through (read the helper first; keep every existing default). Add:

```kotlin
    @Test
    fun `the controller line says stopped and retired`() {
        val stopped = controller(lastSeen = 990, float = 1, pos = "ok", latched = Latch(900, "contra"))
        assertEquals(
            "board 0 · seen 10s ago · every 60s · float ok · pos ok · STOPPED",
            controllerLine(stopped, 1000, 60),
        )
        val retired = controller(lastSeen = 990, retired = 1)
        assertTrue(controllerLine(retired, 1000, 60).endsWith(" · retired"))
    }

    @Test
    fun `the latch line says why, since when, and what to do`() {
        val c = controller(lastSeen = 990, latched = Latch(since = 400, reason = "contra"))
        assertEquals(
            "board 0 stopped watering 10min ago: the float said full and the meter saw nothing. " +
                "Check the tank, type clear contra on the board, then resume.",
            latchLine(c, 1000),
        )
        assertEquals("it reset with the pump running", latchReason("resetmid"))
        assertEquals("heap", latchReason("heap"))
    }

    @Test
    fun `a stopped board is a problem until the backend's own page stands`() {
        val stopped = controller(lastSeen = 990, float = 1, pos = "ok", latched = Latch(400, "contra"))
        assertEquals(
            listOf("board 0 stopped watering: the float said full and the meter saw nothing"),
            problems(Health(ok = true, controllers = listOf(stopped)), nowS = 1000),
        )
        val paged =
            Health(
                ok = true,
                controllers = listOf(stopped),
                alerts = listOf(RaisedAlert("latch:0", raisedTs = 500)),
            )
        assertEquals(listOf("board 0 stopped watering (8min ago)"), problems(paged, nowS = 1000))
        assertEquals(
            "the float on board 0 never moved across the refill (8min ago)",
            describeAlert("stale:0", nowS = 1000, raisedTs = 500),
        )
    }

    @Test
    fun `a board that never knew its position is not a problem, and a retired one is never silent`() {
        val dark = controller(lastSeen = 990, float = 1, pos = "unknown", posOkSeen = null)
        assertEquals(emptyList(), problems(Health(ok = true, controllers = listOf(dark)), nowS = 1000))
        val lost = controller(lastSeen = 990, float = 1, pos = "unknown", posOkSeen = 100)
        assertEquals(
            listOf("board 0 lost its manifold position"),
            problems(Health(ok = true, controllers = listOf(lost)), nowS = 1000),
        )
        val retired = controller(lastSeen = 10, retired = 1)
        assertEquals(emptyList(), problems(Health(ok = true, controllers = listOf(retired)), nowS = 100000))
    }
```

In `WaterTest.kt`, the `controller(...)` helper gains `latched: Latch? = null`; add:

```kotlin
    @Test
    fun `a stopped board refuses after silence and before the busy slot`() {
        val stopped = controller(command = InFlight(3, state = "sent"), latched = Latch(900, "contra"))
        assertEquals(
            "board 0 stopped watering (the float said full and the meter saw nothing) — check the tank, then resume it on the garden screen",
            cannotWater(ready, stopped, 1000, 60, emptySet()),
        )
        val silentAndStopped = controller(lastSeen = 10, latched = Latch(900, "contra"))
        assertTrue(cannotWater(ready, silentAndStopped, 1000, 60, emptySet())!!.startsWith("board 0 is silent"))
    }
```

- [ ] **Step 2: Run the two test classes** — compile errors on `latched`, `latchLine`.

- [ ] **Step 3: `Garden.kt`.** Beside `boardName`:

```kotlin
/** The board's own word for why the butler stopped, in a person's words. */
private val LATCH_WORDS =
    mapOf(
        "contra" to "the float said full and the meter saw nothing",
        "resetmid" to "it reset with the pump running",
    )

fun latchReason(reason: String): String = LATCH_WORDS[reason] ?: reason

/** The card under a stopped board: why, since when, and the three things to
 * do — two of which are not in this app. */
fun latchLine(c: ControllerHealth, nowS: Long): String {
    val latch = c.latched ?: return ""
    val since = if (latch.since in 1..nowS) " ${agoText(latch.since, nowS)}" else ""
    return "${boardName(c.controller)} stopped watering$since: ${latchReason(latch.reason)}. " +
        "Check the tank, type clear contra on the board, then resume."
}
```

In `controllerLine`, after the command part: `if (c.latched != null) parts += "STOPPED"` and `if (c.retired == 1) parts += "retired"`.

In `problems`, inside the loop: first line `if (c.retired == 1) continue` (a retired board is quiet by choice); change the pos line's condition to `c.pos == "unknown" && c.posOkSeen != null && "pos:${c.controller}" !in raised`; and add after it:

```kotlin
        c.latched?.let { latch ->
            if ("latch:${c.controller}" !in raised) {
                found += "${boardName(c.controller)} stopped watering: ${latchReason(latch.reason)}"
            }
        }
```

In `describeAlert`, two cases: `"latch" -> "${board(1)} stopped watering$since"` and `"stale" -> "the float on ${board(1)} never moved across the refill$since"`.

- [ ] **Step 4: `Water.kt`.** In `cannotWater`, after the silence check and before `controller.command?.let`:

```kotlin
    controller.latched?.let {
        return "$c stopped watering (${latchReason(it.reason)}) — check the tank, then resume it on the garden screen"
    }
```

and extend the function's doc comment list with "a stopped board" in its place.

- [ ] **Step 5: Suite green; commit** — "The garden says when the butler stopped watering a board, and why".

---

### Task 3: The two actions on the view model

**Files:**
- Modify: `app/src/main/java/garden/butler/app/GardenViewModel.kt`
- Test: `app/src/test/java/garden/butler/app/GardenViewModelTest.kt`

- [ ] **Step 1: Tests.** In the fake `Butler` dispatcher add two routes: `"/refill" -> MockResponse().setBody("refill=1757000000\n")` and `"/resume" -> MockResponse().setBody("resumed=0\n")`. Then, beside the `resetInterval` tests:

```kotlin
    @Test
    fun `refill and resume post the board and land a note on the list`() {
        ready()
        onMain { refill(0) }
        var note = waitFor("the refill note") { model.listNote.value }
        assertEquals("board 0: refill noted", note)
        val refill = waitFor("the post") { butler.posts().firstOrNull { it.path == "/refill" } }
        assertEquals("c=0", refill.body.copy().readUtf8())
        onMain { resume(0) }
        note = waitFor("the resume note") { model.listNote.value?.takeIf { it != "board 0: refill noted" } }
        assertEquals("board 0 waters again", note)
        val resume = waitFor("the post") { butler.posts().firstOrNull { it.path == "/resume" } }
        assertEquals("c=0", resume.body.copy().readUtf8())
        waitFor("the refresh after it") { butler.sent("/pots").getOrNull(2) }
    }

    @Test
    fun `a refusal from resume lands verbatim`() {
        butler.resumeAnswer = MockResponse().setResponseCode(503).setBody("try again: locked\n")
        ready()
        onMain { resume(0) }
        assertEquals("try again: locked", waitFor("the note") { model.listNote.value })
    }
```

(`resumeAnswer` is a new `@Volatile var` on the fake, used by the `/resume` route; read how `commandAnswer` is done and do the same.)

- [ ] **Step 2: Run** `--tests 'garden.butler.app.GardenViewModelTest'` — compile errors on `refill`/`resume`.

- [ ] **Step 3: The actions**, right after `resetIntervalNow`, in `resetInterval`'s exact shape:

```kotlin
    /** The human refilled the tank: the butler records when, and the
     * stuck-float rule has something to measure against. */
    fun refill(controller: Int) {
        staleRefusal()?.let { why ->
            noteOnList.value = why
            return
        }
        act({
            backend.refill(controller)
            "${boardName(controller)}: refill noted"
        }) { noteOnList.value = it }
    }

    /** The human checked the tank (and typed clear contra on the board):
     * the butler queues water for this board again. */
    fun resume(controller: Int) {
        staleRefusal()?.let { why ->
            noteOnList.value = why
            return
        }
        act({
            backend.resume(controller)
            "${boardName(controller)} waters again"
        }) { noteOnList.value = it }
    }
```

- [ ] **Step 4: Suite green; commit** — "Refill and resume, two taps through the view model".

---

### Task 4: The chip and the card

**Files:**
- Modify: `app/src/main/java/garden/butler/app/GardenScreen.kt` (`ControllersCard` and its call site)

- [ ] **Step 1:** `ControllersCard` takes two more callbacks, `refill: (Int) -> Unit` and `resume: (Int) -> Unit`, and the call site passes `model::refill` and `model::resume`. Per controller, the `Row` gains, before the reset chip, `AssistChip(onClick = { refill(c.controller) }, enabled = live, label = { Text("refilled") })`. Under the row, when `c.latched != null`:

```kotlin
                c.latched?.let {
                    var asking by remember(c.controller) { mutableStateOf(false) }
                    Text(
                        latchLine(c, nowS),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { asking = true }, enabled = live) { Text("Resume watering") }
                    if (asking) {
                        AlertDialog(
                            onDismissRequest = { asking = false },
                            title = { Text("Resume watering on ${boardName(c.controller)}?") },
                            text = {
                                Text(
                                    "Only after the tank has been checked and `clear contra` has been " +
                                        "typed on the board. The butler will queue water again."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { asking = false; resume(c.controller) }) { Text("Resume") }
                            },
                            dismissButton = { TextButton(onClick = { asking = false }) { Text("Not yet") } },
                        )
                    }
                }
```

Add the imports the file lacks (`AlertDialog`, `TextButton`, `remember`, `mutableStateOf`, `getValue`, `setValue`) — check what `PotScreen.kt` imports for its dialogs and mirror it.

- [ ] **Step 2:** `./gradlew --offline test` compiles it and stays green. **Commit** — "The controllers card: a refilled chip, and a resume behind one question".

---

### Task 5: `AGENTS.md`

**Files:**
- Modify: `AGENTS.md`

- [ ] **Step 1:** In "Pitches, in order", add "**I refilled the tank** — done (app#N, this PR): a refilled chip per board, and a stopped board's card with why and a Resume behind a confirmation." In "What is here": `Backend.kt` gains "`Latch` and the tank fields on `ControllerHealth` (`latched`, `last_refill`, `err`, `retired`, `pos_ok_seen`), `refill()` and `resume()`"; `Garden.kt` gains "`latchLine`/`latchReason` (the board's reason in a person's words), the STOPPED and retired marks on the controller line, the latch and stale alert descriptions, and the pos line gated on `pos_ok_seen`"; `Water.kt` gains "a stopped board" in `cannotWater`'s order (after silence, before busy); `GardenViewModel.kt` gains "`refill`/`resume`, in `resetInterval`'s shape". **Commit** — "AGENTS.md: the tank".
