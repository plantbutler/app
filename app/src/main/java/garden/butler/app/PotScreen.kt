package garden.butler.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.time.ZoneId
import kotlinx.coroutines.delay

private val MODES = listOf("manual", "learning", "auto")
private val VERDICTS = listOf("ok", "too_much", "too_little")

/** One pot: current reading, chart and edit form — rendered from the screen's
 * own snapshot, so a pot that vanishes mid-edit does not blank the fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PotScreen(model: GardenViewModel, screen: Screen.Pot) {
    val state by model.state.collectAsStateWithLifecycle()
    val garden = (state as? UiState.Ready)?.garden
    val cachedAtS = (state as? UiState.Ready)?.cachedAtS
    val pot = screen.id?.let { garden?.potById(it) }
    // Keyed on the pot, not fixed at open: a rename shows up on the next
    // refresh, and a pot that vanished keeps the name the form opened on.
    val title = pot?.name ?: screen.original["name"] ?: "New pot"
    val nowS = model.nowS()
    val emptied = emptiedFields(screen.original, screen.draft)
    val dirty = formDirty(screen.original, screen.draft)
    val collision = garden != null && nameTaken(garden, screen.draft["name"].orEmpty(), screen.id)
    var askDiscard by remember { mutableStateOf(false) }
    val leave = { if (dirty) askDiscard = true else model.back() }
    BackHandler(onBack = leave)
    // Polling for the queued dose lives on the screen, not the model, so it
    // stops when the screen does, and reads the latest form so Done/Expired ends it.
    val latest by rememberUpdatedState(screen)
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(screen.watering) {
        val issued = screen.watering ?: return@LaunchedEffect
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (stillFollowing(issued, model.currentWaterStatus(latest), model.phoneS())) {
                delay(model.followEveryMs)
                model.followWater()
            }
        }
    }
    // A dialog, not a tooltip: it survives rotation and uses the same tap
    // gesture as the rest of the screen, unlike a long-press nobody finds.
    fieldFor(screen.explaining)?.let { field ->
        AlertDialog(
            onDismissRequest = model::stopExplaining,
            title = { Text(field.label) },
            text = { Text(field.help) },
            confirmButton = { TextButton(onClick = model::stopExplaining) { Text("Got it") } },
        )
    }
    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text("Discard changes?") },
            confirmButton = { TextButton(onClick = { askDiscard = false; model.back() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { askDiscard = false }) { Text("Keep editing") } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            cachedAtS?.let { ErrorText(staleLine(it, nowS), MaterialTheme.typography.titleSmall) }
            if (pot != null) Text(potLine(pot, nowS), style = MaterialTheme.typography.headlineSmall)
            val health = garden?.health
            val board = health?.controllers?.firstOrNull { it.controller == pot?.controller }
            // Gated on the pot alone: an unwired pot still owns every reading it ever took.
            if (pot != null) {
                Chart(
                    screen.history,
                    screen.historyWhy,
                    pot,
                    board,
                    health?.nextDefault ?: 60,
                    screen.window,
                    model::setChartWindow,
                )
            }
            // An environment pot is a sensor on a shelf, not a plant, so no strip.
            if (pot == null || !pot.name.startsWith(ENV_PREFIX)) {
                PhotoStrip(screen, pot, model)
            }
            if (pot != null && !pot.name.startsWith(ENV_PREFIX)) {
                val dirtyKeys = changedFields(screen.original, screen.draft).keys + emptied.map { it.key }
                WaterRow(
                    screen,
                    pot,
                    cannotWater(pot, board, nowS, health?.nextDefault ?: 60, dirtyKeys, cachedAtS),
                    model,
                )
            }
            // Stale data disables these rather than refusing only after the tap.
            val live = cachedAtS == null
            if (pot?.status == ALIVE) { // a buried pot is neither proposed for nor dosed
                pot.proposal?.let { ProposalCard(it, nowS, live) { model.approve(it.id) } }
                pot.lastDose?.let { DoseCard(it, nowS, live) { v -> model.verdict(it.id, v) } }
                pot.advice?.let {
                    AdviceCard(it, live && !screen.busy, { model.applyAdvice(it) }, model::dismissAdvice)
                }
            }
            if (pot != null) {
                TextButton(
                    onClick = { model.openDoses(pot.id, "${pot.name}'s water") },
                    enabled = !screen.busy, // mid-save, leaving would strand the form
                ) {
                    Text("Watering history")
                }
            }
            screen.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val draftPot = draftPot(screen.draft, pot)
            val gaps = learningGaps(draftPot, health?.controllers?.firstOrNull { it.controller == draftPot.controller })
            if ((screen.draft["mode"] ?: "manual") != "manual" && gaps.isNotEmpty()) {
                Text("learning needs: ${gaps.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            }
            Form(screen, garden?.health?.controllers.orEmpty().map { it.controller }, collision, dirty, live, model)
            if (emptied.isNotEmpty()) {
                Text(
                    "cannot clear: ${emptied.joinToString(", ") { it.label }} — the backend keeps a stored value",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val named = !screen.draft["name"].isNullOrBlank()
            // Save goes grey without a name; say so rather than leave it unexplained.
            if (!named) {
                Text("give the pot a name", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = model::save,
                enabled = dirty && named && !screen.busy && emptied.isEmpty() && !collision && live,
            ) {
                Text("Save")
            }
            screen.refused?.let { ErrorText(it) }
            // The graveyard chip above is the reversible answer; this is not,
            // so it stands apart, below Save, and only for a pot that exists.
            if (screen.id != null) {
                HorizontalDivider()
                Erase(screen.id, pot?.name ?: screen.original["name"].orEmpty(), live, model)
            }
        }
    }
}

/** Erasing the pot. Two taps; the second names the plant and lists what goes,
 * not a generic "cannot be undone". Disabled while the screen is a cached
 * memory — the one irreversible action must never be the one thing still allowed. */
@Composable
private fun Erase(potId: String, name: String, live: Boolean, model: GardenViewModel) {
    var asking by remember(potId) { mutableStateOf(false) }
    TextButton(
        onClick = { asking = true },
        enabled = live,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Text("Delete this pot")
    }
    if (!asking) return
    AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Delete $name?") },
        text = {
            Text(
                "Its readings, its watering history and its photographs go with it, and none " +
                    "of it comes back. To keep the record and free the channel and outlet, set " +
                    "its status to graveyard instead."
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    asking = false
                    model.deletePot()
                },
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
            ) {
                Text("Delete for ever")
            }
        },
        dismissButton = { TextButton(onClick = { asking = false }) { Text("Keep it") } },
    )
}

/** The stored pot with the draft's own prerequisites laid over it, so the
 * hint follows what the user is typing, not only what was saved. */
private fun draftPot(draft: Map<String, String>, stored: Pot?): Pot =
    (stored ?: Pot(name = "")).copy(
        species = draft["species"]?.ifBlank { null },
        controller = draft["controller"]?.toIntOrNull(),
        channel = draft["channel"]?.toIntOrNull(),
        outlet = draft["outlet"]?.toIntOrNull(),
        dryRaw = draft["dry_raw"]?.toLongOrNull(),
        wetRaw = draft["wet_raw"]?.toLongOrNull(),
        targetLowPct = draft["target_low_pct"]?.toIntOrNull(),
        doseMl = draft["dose_ml"]?.toIntOrNull(),
    )

/** The chosen window as a polyline over faint gridlines: % inside the pot's
 * target band when calibrated, else raw counts on their own span. A silent
 * stretch is a gap, not a straight line across it; the last dose is a hairline. */
@Composable
private fun Chart(
    history: History?,
    why: String?,
    pot: Pot,
    board: ControllerHealth?,
    nextDefault: Int,
    window: ChartWindow,
    onWindow: (ChartWindow) -> Unit,
) {
    // The chips stay up while the next window loads: a spinner you cannot leave is a trap.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChartWindow.entries.forEach { w ->
            FilterChip(selected = w == window, onClick = { onWindow(w) }, label = { Text(w.label) })
        }
    }
    if (history == null) {
        if (why == null) Text("loading the last ${window.label}…", style = MaterialTheme.typography.bodySmall)
        why?.let { ErrorText(it) }
        return
    }
    val caption = chartCaption(history, pot.dryRaw, pot.wetRaw, env = pot.name.startsWith(ENV_PREFIX))
    // Finger position in pixels and canvas width; -1 means nothing is touching
    // it. The decision itself is sampleNearest(), a pure function — this is only plumbing.
    var scrubX by remember { mutableFloatStateOf(-1f) }
    var widthPx by remember { mutableIntStateOf(0) }
    var scrubText: String? = null
    if (history.points.isNotEmpty()) {
        val calibrated = isCalibrated(pot.dryRaw, pot.wetRaw)
        val gapS = chartGapS(history.bucketS, board, nextDefault)
        val series =
            remember(history, pot.dryRaw, pot.wetRaw, gapS) { chartSeries(history.points, pot.dryRaw, pot.wetRaw, gapS) }
        val range = chartRange(series, calibrated)
        val ticksY = yTicks(range, calibrated)
        val zone = ZoneId.systemDefault()
        val ticksX =
            remember(history.since, history.to, zone, window) { windowTicks(window, history.since, history.to, zone) }
        val scrub =
            if (scrubX >= 0 && widthPx > 0) {
                sampleNearest(series, scrubX / widthPx.toDouble(), history.since, history.to)
            } else {
                null
            }
        scrubText = scrub?.let { scrubLabel(it, calibrated, zone) }
        val primary = MaterialTheme.colorScheme.primary
        val tertiary = MaterialTheme.colorScheme.tertiary
        val grid = MaterialTheme.colorScheme.outlineVariant
        val label = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        val measurer = rememberTextMeasurer()
        Canvas(
            Modifier.fillMaxWidth()
                .height(180.dp)
                .onSizeChanged { widthPx = it.width }
                // Horizontal only, so dragging the chart never fights the
                // form scrolling underneath it.
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { scrubX = it.x },
                        onDragEnd = { scrubX = -1f },
                        onDragCancel = { scrubX = -1f },
                    ) { change, _ -> scrubX = change.position.x }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        scrubX = it.x
                        tryAwaitRelease()
                        scrubX = -1f
                    })
                },
        ) {
            val pad = 2.dp.toPx()
            val labelH = measurer.measure("00:00", label).size.height
            val top = labelH.toFloat() // the top value label sits above its gridline
            val bottom = size.height - labelH - pad // the hour labels sit under the plot
            val span = (history.to - history.since).coerceAtLeast(1).toFloat()
            fun x(ts: Long) = (ts - history.since) / span * size.width
            fun y(v: Double) = (bottom - (v - range.low) / (range.high - range.low) * (bottom - top)).toFloat()
            for (t in ticksY) drawLine(grid, Offset(0f, y(t.at)), Offset(size.width, y(t.at)), 1f)
            for (t in ticksX) drawLine(grid, Offset(x(t.ts), top), Offset(x(t.ts), bottom), 1f)
            val lo = pot.targetLowPct
            val hi = pot.targetHighPct
            if (calibrated && lo != null && hi != null && hi > lo) {
                drawRect(
                    primary.copy(alpha = 0.12f),
                    topLeft = Offset(0f, y(hi.toDouble())),
                    size = Size(size.width, y(lo.toDouble()) - y(hi.toDouble())),
                )
            }
            for (segment in series) {
                if (segment.size == 1) {
                    drawCircle(primary, 3.dp.toPx(), Offset(x(segment[0].ts), y(segment[0].value)))
                    continue
                }
                val path = Path()
                segment.forEachIndexed { i, s ->
                    if (i == 0) path.moveTo(x(s.ts), y(s.value)) else path.lineTo(x(s.ts), y(s.value))
                }
                drawPath(path, primary, style = Stroke(2.dp.toPx()))
            }
            pot.lastDose?.sentTs?.takeIf { it in history.since..history.to }?.let { ts ->
                drawLine(tertiary, Offset(x(ts), top), Offset(x(ts), bottom), 1.dp.toPx())
            }
            scrub?.let { s ->
                drawLine(primary, Offset(x(s.ts), top), Offset(x(s.ts), bottom), 1.dp.toPx())
                drawCircle(primary, 4.dp.toPx(), Offset(x(s.ts), y(s.value)))
            }
            for (t in ticksY) {
                val text = measurer.measure(t.label, label)
                drawText(text, topLeft = Offset(pad, y(t.at) - text.size.height))
            }
            val now = measurer.measure("now", label)
            drawText(now, topLeft = Offset(size.width - now.size.width, bottom + pad))
            for (t in ticksX) { // an hour label that would run into "now" is left out
                val text = measurer.measure(t.label, label)
                val left = x(t.ts) + pad
                if (left + text.size.width + pad < size.width - now.size.width) {
                    drawText(text, topLeft = Offset(left, bottom + pad))
                }
            }
        }
    }
    // Under the finger: the sample's own value and time, never an
    // interpolation — that would be a reading that never happened.
    if (scrubText != null) {
        Text(scrubText, style = MaterialTheme.typography.bodyMedium)
    } else {
        Text(caption, style = MaterialTheme.typography.bodySmall)
    }
    why?.let { ErrorText(it) }
}

/** Waters the stored pot, and says under itself why it cannot, or where the
 * queued dose has got to. The slot's "busy" reason stays hidden while the
 * dose is queued or sent — that busy slot is this form's own command. */
@Composable
private fun WaterRow(screen: Screen.Pot, pot: Pot, reason: String?, model: GardenViewModel) {
    val status = screen.watering?.let { model.currentWaterStatus(screen) ?: WaterStatus.Queued }
    val following = stillFollowing(screen.watering, status, model.phoneS())
    val ownWords = status == WaterStatus.Queued || status == WaterStatus.Sent || status == WaterStatus.NoNews
    var askWater by remember { mutableStateOf(false) }
    if (askWater) {
        AlertDialog(
            onDismissRequest = { askWater = false },
            text = { Text(waterDialogText(pot)) },
            confirmButton = { TextButton(onClick = { askWater = false; model.water() }) { Text("Water") } },
            dismissButton = { TextButton(onClick = { askWater = false }) { Text("Cancel") } },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = { askWater = true }, enabled = reason == null && !screen.busy && !following) {
            Text(pot.doseMl?.let { "Water $it ml" } ?: "Water")
        }
        status?.let {
            Text(waterLine(it, pot.controller?.toString() ?: "?"), style = MaterialTheme.typography.bodySmall)
        }
        if (reason != null && !ownWords) Text(reason, style = MaterialTheme.typography.bodySmall)
        screen.waterRefused?.let { ErrorText(it) }
    }
}

@Composable
private fun ProposalCard(p: Proposal, nowS: Long, enabled: Boolean, approve: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(proposalLine(p, nowS))
            Button(onClick = approve, enabled = enabled) { Text("Approve") }
        }
    }
}

@Composable
private fun DoseCard(d: LastDose, nowS: Long, enabled: Boolean, verdict: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(doseLine(d, nowS))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VERDICTS.forEach { v ->
                    FilterChip(
                        selected = d.verdict == v,
                        onClick = { verdict(v) },
                        enabled = enabled,
                        label = { Text(verdictLabel(v)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Form(
    screen: Screen.Pot,
    controllers: List<Int>,
    collision: Boolean,
    dirty: Boolean,
    live: Boolean,
    model: GardenViewModel,
) {
    val draft = screen.draft
    // The id is the key, so editing the nickname here renames the pot
    // rather than creating a second one.
    OutlinedTextField(
        value = draft["name"].orEmpty(),
        onValueChange = { model.edit("name", it) },
        label = { Text(NAME_FIELD.label) },
        singleLine = true,
        enabled = !screen.busy,
        trailingIcon = { Explain(NAME_FIELD, model) },
        modifier = Modifier.fillMaxWidth(),
    )
    if (collision) {
        ErrorText(
            if (screen.id == null) {
                "${tokenize(draft["name"].orEmpty())} already exists — open it from the list"
            } else {
                "${tokenize(draft["name"].orEmpty())} is another pot's name"
            }
        )
    }
    for (field in POT_FIELDS) {
        when (field.key) {
            // The label above the chips says both what they are and that
            // manual, learning and auto are three different amounts of trust.
            "mode" -> {
                Labelled(field, model)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MODES.forEach { m ->
                        FilterChip(
                            selected = (draft["mode"] ?: "manual") == m,
                            onClick = { model.edit("mode", m) },
                            label = { Text(m) },
                        )
                    }
                }
            }
            // Chips, not a dropdown: both words visible at once, since
            // burying a plant is worth seeing before tapping.
            "status" -> {
                Labelled(field, model)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    POT_STATUSES.forEach { s ->
                        FilterChip(
                            selected = (draft["status"] ?: ALIVE) == s.wire,
                            onClick = { model.edit("status", s.wire) },
                            label = { Text(s.label) },
                        )
                    }
                }
            }
            "controller" -> {
                ValueField(field, draft, model::edit, model)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    controllers.forEach { c ->
                        AssistChip(
                            onClick = { model.edit("controller", c.toString()) },
                            label = { Text(c.toString()) },
                        )
                    }
                }
            }
            "dry_raw" ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ValueField(field, draft, model::edit, model, Modifier.weight(1f))
                    ValueField(
                        POT_FIELDS.first { it.key == "wet_raw" },
                        draft,
                        model::edit,
                        model,
                        Modifier.weight(1f),
                    )
                    Button(
                        onClick = model::startCalibration,
                        enabled = screen.id != null && !screen.busy && !dirty && live,
                    ) {
                        Text("Recalibrate")
                    }
                }
            "species" -> {
                ValueField(field, draft, model::edit, model)
                SpeciesPanel(screen, model)
            }
            "wet_raw" -> Unit
            // A closed set is picked, never typed: free text here can silently
            // drift the water target with no warning.
            else ->
                if (field.input == Input.PICK) {
                    Picker(field, draft, model)
                } else {
                    ValueField(field, draft, model::edit, model)
                }
        }
    }
}

@Composable
private fun ValueField(
    field: Field,
    draft: Map<String, String>,
    edit: (String, String) -> Unit,
    model: GardenViewModel,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = draft[field.key].orEmpty(),
        onValueChange = { edit(field.key, it) },
        label = { Text(field.label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardFor(field.input)),
        trailingIcon = { Explain(field, model) },
        modifier = modifier,
    )
}

private fun keyboardFor(input: Input): KeyboardType =
    when (input) {
        Input.INTEGER -> KeyboardType.Number
        // Centimetre measurements need a decimal point, or 14.5 is untypeable.
        Input.DECIMAL -> KeyboardType.Decimal
        // PICK never reaches a keyboard; TEXT is the only one left that does.
        else -> KeyboardType.Text
    }

/** One closed set as a dropdown. The read-only field shows the LABEL while
 * the draft holds the wire word, so renaming a choice on screen never
 * becomes a wire change.
 *
 * "Not said" is a real answer, not a placeholder — the unlabelled band for
 * plant kind, ordinary potting compost for soil. It writes an empty draft
 * value, which the wire cannot send, so it clears nothing already stored;
 * the form says so under Save rather than pretending otherwise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(field: Field, draft: Map<String, String>, model: GardenViewModel) {
    val choices = choicesFor(field.key) ?: return
    val chosen = draft[field.key].orEmpty()
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(
            expanded = open,
            onExpandedChange = { open = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = labelFor(field.key, chosen).ifEmpty { NOT_SAID },
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(field.label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                modifier =
                    Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(NOT_SAID) },
                    onClick = {
                        model.edit(field.key, "")
                        open = false
                    },
                )
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.label) },
                        onClick = {
                            model.edit(field.key, choice.wire)
                            open = false
                        },
                    )
                }
            }
        }
        Explain(field, model)
    }
}

private const val NOT_SAID = "not said"

/** The ⓘ. Small, and beside the thing it explains rather than in a help
 * screen nobody opens. */
@Composable
private fun Explain(field: Field, model: GardenViewModel) {
    IconButton(onClick = { model.explain(field.key) }) {
        Icon(Icons.Filled.Info, "What is ${field.label}?")
    }
}

/** A label with its own ⓘ, for the controls that are not a text field and
 * so have nowhere to hang one. */
@Composable
private fun Labelled(field: Field, model: GardenViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(field.label, style = MaterialTheme.typography.labelLarge)
        Explain(field, model)
    }
}

