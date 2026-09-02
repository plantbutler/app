package garden.butler.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    val pot = screen.name?.let { garden?.potNamed(it) }
    val nowS = model.nowS()
    val emptied = emptiedFields(screen.original, screen.draft)
    val dirty =
        changedFields(screen.original, screen.draft).isNotEmpty() ||
            emptied.isNotEmpty() ||
            (screen.name == null && !screen.draft["name"].isNullOrBlank())
    val collision = screen.name == null && garden != null && nameTaken(garden, screen.draft["name"].orEmpty())
    var askDiscard by remember { mutableStateOf(false) }
    val leave = { if (dirty) askDiscard = true else model.back() }
    BackHandler(onBack = leave)
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
                title = { Text(screen.name ?: "New pot") },
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
            if (pot != null) Text(potLine(pot, nowS), style = MaterialTheme.typography.titleMedium)
            if (pot?.enabled == 1) { // a disabled pot is neither proposed for nor dosed
                pot.proposal?.let { ProposalCard(it, nowS) { model.approve(it.id) } }
                pot.lastDose?.let { DoseCard(it, nowS) { v -> model.verdict(it.id, v) } }
            }
            screen.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val draftPot = draftPot(screen.draft, pot)
            val board = garden?.health?.controllers?.firstOrNull { it.controller == draftPot.controller }
            val gaps = learningGaps(draftPot, board)
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
            val named = screen.name != null || !screen.draft["name"].isNullOrBlank()
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
        controller = draft["controller"]?.ifBlank { null },
        channel = draft["channel"]?.toIntOrNull(),
        outlet = draft["outlet"]?.toIntOrNull(),
        dryRaw = draft["dry_raw"]?.toLongOrNull(),
        wetRaw = draft["wet_raw"]?.toLongOrNull(),
        targetLowPct = draft["target_low_pct"]?.toIntOrNull(),
        doseMl = draft["dose_ml"]?.toIntOrNull(),
    )

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
    if (screen.name == null) {
        OutlinedTextField(
            value = draft["name"].orEmpty(),
            onValueChange = { model.edit("name", it) },
            label = { Text("name") },
            singleLine = true,
            enabled = !screen.saving, // the save's outcome is addressed to this name
            modifier = Modifier.fillMaxWidth(),
        )
        if (collision) {
            Text(
                "${tokenize(draft["name"].orEmpty())} already exists — open it from the list",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
                        enabled = screen.name != null && !screen.saving && !dirty,
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
