package garden.butler.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The garden list: every plant, the room readings and anything wrong. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GardenScreen(model: GardenViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val listNote by model.listNote.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plant Butler") },
                actions = {
                    IconButton(onClick = { model.openDoses(null, "Watering") }) {
                        Icon(Icons.AutoMirrored.Filled.List, "Watering history")
                    }
                    IconButton(onClick = model::newPot) { Icon(Icons.Default.Add, "New pot") }
                    IconButton(onClick = model::openSettings) {
                        Icon(Icons.Default.Settings, "Where the butler is")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val it = state) {
                is UiState.Loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is UiState.Trouble ->
                    Trouble(it, model::refresh, Modifier.align(Alignment.Center))
                is UiState.Ready ->
                    GardenList(it.garden, it.refreshing, it.why, it.cachedAtS, listNote, model)
            }
        }
    }
}

@Composable
private fun Trouble(state: UiState.Trouble, retry: () -> Unit, modifier: Modifier = Modifier) {
    NotAnswering(state.why, modifier) {
        if (state.retrying) {
            CircularProgressIndicator(Modifier.padding(top = 12.dp).size(28.dp))
        } else {
            Button(onClick = retry, Modifier.padding(top = 12.dp)) { Text("Try again") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GardenList(
    garden: Garden,
    refreshing: Boolean,
    why: String?,
    cachedAtS: Long?,
    listNote: String?,
    model: GardenViewModel,
) {
    val nowS = model.nowS()
    // Long press, not swipe: a swipe fires mid-scroll, and both actions here matter.
    var sheetFor by remember { mutableStateOf<Pot?>(null) }
    sheetFor?.let { RowActions(it, model) { sheetFor = null } }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = model::refresh) {
        LazyColumn(Modifier.fillMaxSize()) {
            // A stale reading shown without its age is worse than showing nothing.
            if (cachedAtS != null) {
                item { CachedBanner(staleLine(cachedAtS, nowS)) }
            } else if (why != null) {
                item { StaleCard("refresh failed ($why) — showing the last good read") }
            }
            if (garden.problems.isNotEmpty()) {
                item { ProblemStrip(garden.problems) }
            }
            if (garden.health.controllers.isNotEmpty()) {
                item {
                    ControllersCard(
                        garden.health,
                        nowS,
                        cachedAtS == null,
                        model::resetInterval,
                        model::refill,
                        model::resume,
                    )
                }
            }
            if (listNote != null) {
                item {
                    Text(
                        listNote,
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (garden.env.isNotEmpty()) {
                item { EnvCard(garden.env, nowS, model::open) }
            }
            items(garden.pots, key = { potKey(it) }) { pot ->
                PotRow(pot, nowS, model, model::open) { sheetFor = pot }
            }
            if (garden.pots.isEmpty() && garden.graveyard.isEmpty() && garden.env.isEmpty()) {
                item {
                    Text(
                        "No pots yet — tap + to plant one.",
                        Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (garden.graveyard.isNotEmpty()) {
                item {
                    Text(
                        "Graveyard",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                items(garden.graveyard, key = { "off:" + potKey(it) }) { pot ->
                    Box(Modifier.alpha(0.6f)) {
                        PotRow(pot, nowS, model, model::open) { sheetFor = pot }
                    }
                }
            }
        }
    }
}

/** Nothing on screen came from the butler this launch — every number below is a
 * memory, so this is loud on purpose: error colour, above everything else. */
@Composable
private fun CachedBanner(line: String) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(line, Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall)
    }
}

/** Visible only when something is wrong; a healthy garden shows no strip. */
@Composable
private fun ProblemStrip(problems: List<String>) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            problems.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** One line per controller. "refilled" is the human tap that clears OVER and
 * marks where the tank measurement restarts; a retired board offers neither
 * chip. A stopped board's Resume sits behind a confirmation, since two of the
 * three fixes happen at the tank and the board, not in this app. */
@Composable
private fun ControllersCard(
    health: Health,
    nowS: Long,
    live: Boolean,
    reset: (Int) -> Unit,
    refill: (Int) -> Unit,
    resume: (Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            health.controllers.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        controllerLine(c, nowS, health.nextDefault),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (offersChips(c)) {
                        AssistChip(
                            onClick = { refill(c.controller) },
                            enabled = live,
                            label = { Text("refilled") },
                        )
                        if (hasOverride(c)) {
                            AssistChip(
                                onClick = { reset(c.controller) },
                                enabled = live,
                                label = { Text("reset") },
                            )
                        }
                    }
                }
                tankHint(c)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                overLine(c)?.let { ErrorText(it) }
                c.latched?.let { StoppedBoard(c, it, nowS, live) { resume(c.controller) } }
            }
        }
    }
}

/** A stopped board: why, and the way out. Resume sits behind a confirmation
 * because two of its three steps happen at the tank and at the board, not
 * here, and a resume without them re-latches at the board's next report. */
@Composable
private fun StoppedBoard(
    c: ControllerHealth,
    latch: Latch,
    nowS: Long,
    live: Boolean,
    resume: () -> Unit,
) {
    var asking by remember(c.controller) { mutableStateOf(false) }
    ErrorText(latchLine(c, nowS))
    TextButton(onClick = { asking = true }, enabled = live) { Text("Resume watering") }
    if (!asking) return
    AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Resume watering on ${boardName(c.controller)}?") },
        text = { Text(resumeText(latch)) },
        confirmButton = { TextButton(onClick = { asking = false; resume() }) { Text("Resume") } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text("Not yet") } },
    )
}

@Composable
private fun EnvCard(env: List<Pot>, nowS: Long, open: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            env.forEach { pot ->
                val (label, value) = envEntry(pot)
                Column(Modifier.clickable { open(pot.id) }) {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                    Text(value, style = MaterialTheme.typography.titleMedium)
                    envStale(pot, nowS)?.let { ErrorText(it, MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

/** The two things worth doing to a row without opening it. Neither is
 * destructive here: Delete opens the pot, whose confirmation names what goes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowActions(pot: Pot, model: GardenViewModel, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Text(
            pot.name,
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        if (pot.status == ALIVE) {
            ListItem(
                headlineContent = { Text("Move to the graveyard") },
                supportingContent = {
                    Text("Keeps everything; frees its channel and its outlet.")
                },
                modifier =
                    Modifier.clickable {
                        dismiss()
                        model.bury(pot.id)
                    },
            )
        } else {
            ListItem(
                headlineContent = { Text("Bring it back") },
                supportingContent = { Text("Comes back unwired: say where the new plant went.") },
                modifier =
                    Modifier.clickable {
                        dismiss()
                        model.revive(pot.id)
                    },
            )
        }
        ListItem(
            headlineContent = { Text("Delete it") },
            supportingContent = { Text("Opens the pot; it asks before erasing anything.") },
            modifier =
                Modifier.clickable {
                    dismiss()
                    model.open(pot.id)
                },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PotRow(
    pot: Pot,
    nowS: Long,
    model: GardenViewModel,
    open: (String) -> Unit,
    longPress: () -> Unit,
) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = { open(pot.id) }, onLongClick = longPress),
        // A pot never photographed gets no placeholder: an empty grey square
        // in every row is noise, so the row just starts at the name.
        leadingContent =
            pot.photo?.let { photoId ->
                {
                    Picture(
                        photoId,
                        model,
                        Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                    )
                }
            },
        headlineContent = { Text(pot.name) },
        supportingContent = {
            Column {
                Text(potLine(pot, nowS))
                pot.proposal?.takeIf { pot.status == ALIVE }?.let {
                    Text(
                        "proposal waiting: ${it.ml ?: "?"} ml",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                verdictNudge(pot, nowS)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        trailingContent = {
            if (pot.mode != "manual") {
                AssistChip(onClick = { open(pot.id) }, label = { Text(pot.mode) })
            }
        },
    )
}
