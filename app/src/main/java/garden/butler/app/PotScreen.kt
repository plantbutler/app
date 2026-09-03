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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** One pot: what it reads now, what waits for a tap, and the form. The
 * form renders from the screen's own snapshot, so a pot that vanishes
 * mid-edit does not blank the fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PotScreen(model: GardenViewModel, screen: Screen.Pot) {
    val state by model.state.collectAsStateWithLifecycle()
    val garden = (state as? UiState.Ready)?.garden
    val pot = screen.id?.let { garden?.potById(it) }
    // The title follows the pot, not the key: a rename lands here on the
    // next refresh. A pot that vanished keeps the name the form opened on.
    val title = pot?.name ?: screen.original["name"] ?: "New pot"
    val nowS = model.nowS()
    val emptied = emptiedFields(screen.original, screen.draft)
    val dirty = formDirty(screen.original, screen.draft)
    val collision = garden != null && nameTaken(garden, screen.draft["name"].orEmpty(), screen.id)
    var askDiscard by remember { mutableStateOf(false) }
    val leave = { if (dirty) askDiscard = true else model.back() }
    BackHandler(onBack = leave)
    // The queued dose is followed from here, not the model: polling stops
    // with the screen, and reads the latest form so Done/Expired ends it.
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
            if (pot != null) Text(potLine(pot, nowS), style = MaterialTheme.typography.headlineSmall)
            val health = garden?.health
            val board = health?.controllers?.firstOrNull { it.controller == pot?.controller }
            if (pot != null && pot.controller != null && pot.channel != null) {
                Chart(screen.history, screen.historyWhy, pot, board, health?.nextDefault ?: 60)
            }
            if (pot != null && !pot.name.startsWith(ENV_PREFIX)) {
                val dirtyKeys = changedFields(screen.original, screen.draft).keys + emptied.map { it.key }
                WaterRow(screen, pot, cannotWater(pot, board, nowS, health?.nextDefault ?: 60, dirtyKeys), model)
            }
            if (pot?.enabled == 1) { // a disabled pot is neither proposed for nor dosed
                pot.proposal?.let { ProposalCard(it, nowS) { model.approve(it.id) } }
                pot.lastDose?.let { DoseCard(it, nowS) { v -> model.verdict(it.id, v) } }
            }
            if (pot != null) {
                TextButton(onClick = { model.openDoses(pot.id, "${pot.name}'s water") }) {
                    Text("Watering history")
                }
            }
            screen.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val draftPot = draftPot(screen.draft, pot)
            val gaps = learningGaps(draftPot, health?.controllers?.firstOrNull { it.controller == draftPot.controller })
            if ((screen.draft["mode"] ?: "manual") != "manual" && gaps.isNotEmpty()) {
                Text("learning needs: ${gaps.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            }
            Form(screen, garden?.health?.controllers.orEmpty().map { it.controller }, collision, dirty, model)
            if (emptied.isNotEmpty()) {
                Text(
                    "cannot clear: ${emptied.joinToString(", ") { it.label }} — the backend keeps a stored value",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val named = !screen.draft["name"].isNullOrBlank()
            // Save goes grey without a name; say so, the way a blanked
            // stored field says so, rather than leave the user hunting.
            if (!named) {
                Text("give the pot a name", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = model::save,
                enabled = dirty && named && !screen.saving && emptied.isEmpty() && !collision,
            ) {
                Text("Save")
            }
            screen.refused?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The stored pot with the draft's own prerequisites laid over it, so the
 * hint follows what the user is typing, not only what was saved. */
private fun draftPot(draft: Map<String, String>, stored: Pot?): Pot =
    (stored ?: Pot(name = "")).copy(
        species = draft["species"]?.ifBlank { null },
        controller = draft["controller"]?.ifBlank { null },
        channel = draft["channel"]?.toIntOrNull(),
        outlet = draft["outlet"]?.toIntOrNull(),
        dryRaw = draft["dry_raw"]?.toLongOrNull(),
        wetRaw = draft["wet_raw"]?.toLongOrNull(),
        targetLowPct = draft["target_low_pct"]?.toIntOrNull(),
        doseMl = draft["dose_ml"]?.toIntOrNull(),
    )

/** The last 24 h as a polyline over faint gridlines: % under the pot's
 * calibration inside its target band, else raw counts on their own span.
 * The axis values sit just inside the plot, the wall-clock hours under it.
 * A silent stretch is a hole, not a straight line across it; the last dose
 * is a hairline. */
@Composable
private fun Chart(history: History?, why: String?, pot: Pot, board: ControllerHealth?, nextDefault: Int) {
    if (history == null) {
        if (why == null) Text("loading the last 24 h…", style = MaterialTheme.typography.bodySmall)
        why?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        return
    }
    val caption = chartCaption(history, pot.dryRaw, pot.wetRaw, env = pot.name.startsWith(ENV_PREFIX))
    if (history.points.isNotEmpty()) {
        val calibrated = isCalibrated(pot.dryRaw, pot.wetRaw)
        val gapS = chartGapS(history.bucketS, board, nextDefault)
        val series =
            remember(history, pot.dryRaw, pot.wetRaw, gapS) { chartSeries(history.points, pot.dryRaw, pot.wetRaw, gapS) }
        val range = chartRange(series, calibrated)
        val ticksY = yTicks(range, calibrated)
        val zone = ZoneId.systemDefault()
        val ticksX = remember(history.since, history.to, zone) { timeTicks(history.since, history.to, zone) }
        val primary = MaterialTheme.colorScheme.primary
        val tertiary = MaterialTheme.colorScheme.tertiary
        val grid = MaterialTheme.colorScheme.outlineVariant
        val label = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        val measurer = rememberTextMeasurer()
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
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
    Text(caption, style = MaterialTheme.typography.bodySmall)
    why?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/** The button waters the stored pot, and says under itself why it cannot,
 * or where the queued dose has got to. While the dose is queued or sent
 * the slot's "busy" reason stays hidden: that is this form's own command;
 * and "no news" stands alone, since the reason under it would be a guess. */
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
        Button(onClick = { askWater = true }, enabled = reason == null && !screen.saving && !following) {
            Text(pot.doseMl?.let { "Water $it ml" } ?: "Water")
        }
        status?.let { Text(waterLine(it, pot.controller ?: "?"), style = MaterialTheme.typography.bodySmall) }
        if (reason != null && !ownWords) Text(reason, style = MaterialTheme.typography.bodySmall)
        screen.waterRefused?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProposalCard(p: Proposal, nowS: Long, approve: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(proposalLine(p, nowS))
            Button(onClick = approve) { Text("Approve") }
        }
    }
}

@Composable
private fun DoseCard(d: LastDose, nowS: Long, verdict: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(doseLine(d, nowS))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VERDICTS.forEach { v ->
                    FilterChip(selected = d.verdict == v, onClick = { verdict(v) }, label = { Text(verdictLabel(v)) })
                }
            }
        }
    }
}

@Composable
private fun Form(
    screen: Screen.Pot,
    controllers: List<String>,
    collision: Boolean,
    dirty: Boolean,
    model: GardenViewModel,
) {
    val draft = screen.draft
    // A stored pot's nickname is editable too: the id is the key, so this
    // field renames rather than creating a second pot.
    OutlinedTextField(
        value = draft["name"].orEmpty(),
        onValueChange = { model.edit("name", it) },
        label = { Text("name") },
        singleLine = true,
        enabled = !screen.saving,
        modifier = Modifier.fillMaxWidth(),
    )
    if (collision) {
        Text(
            if (screen.id == null) {
                "${tokenize(draft["name"].orEmpty())} already exists — open it from the list"
            } else {
                "${tokenize(draft["name"].orEmpty())} is another pot's name"
            },
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    for (field in POT_FIELDS) {
        when (field.key) {
            "mode" ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MODES.forEach { m ->
                        FilterChip(
                            selected = (draft["mode"] ?: "manual") == m,
                            onClick = { model.edit("mode", m) },
                            label = { Text(m) },
                        )
                    }
                }
            "enabled" ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(
                        checked = (draft["enabled"] ?: "1") == "1",
                        onCheckedChange = { model.edit("enabled", if (it) "1" else "0") },
                    )
                    Text("enabled")
                }
            "controller" -> {
                ValueField(field, draft, model::edit)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    controllers.forEach { c ->
                        AssistChip(onClick = { model.edit("controller", c) }, label = { Text(c) })
                    }
                }
            }
            "dry_raw" ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ValueField(field, draft, model::edit, Modifier.weight(1f))
                    ValueField(POT_FIELDS.first { it.key == "wet_raw" }, draft, model::edit, Modifier.weight(1f))
                    Button(
                        onClick = model::startCalibration,
                        enabled = screen.id != null && !screen.saving && !dirty,
                    ) {
                        Text("Recalibrate")
                    }
                }
            "wet_raw" -> Unit
            else -> ValueField(field, draft, model::edit)
        }
    }
}

@Composable
private fun ValueField(
    field: Field,
    draft: Map<String, String>,
    edit: (String, String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = draft[field.key].orEmpty(),
        onValueChange = { edit(field.key, it) },
        label = { Text(field.label) },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(keyboardType = if (field.numeric) KeyboardType.Number else KeyboardType.Text),
        modifier = modifier,
    )
}
