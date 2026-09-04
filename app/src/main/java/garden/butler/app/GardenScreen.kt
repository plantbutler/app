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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("The butler is not answering", style = MaterialTheme.typography.titleMedium)
        Text(state.why, style = MaterialTheme.typography.bodySmall)
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
    val nowS = System.currentTimeMillis() / 1000
    // A long press, never a swipe: a swipe fires while the list is being
    // scrolled, and one of these two actions cannot be taken back.
    var sheetFor by remember { mutableStateOf<Pot?>(null) }
    sheetFor?.let { RowActions(it, model) { sheetFor = null } }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = model::refresh) {
        LazyColumn(Modifier.fillMaxSize()) {
            // The age has to be as loud as the numbers it qualifies: a
            // stale reading shown without it is worse than showing nothing.
            if (cachedAtS != null) {
                item { CachedBanner(staleLine(cachedAtS, nowS)) }
            } else if (why != null) {
                item { StaleBanner(why) }
            }
            if (garden.problems.isNotEmpty()) {
                item { ProblemStrip(garden.problems) }
            }
            if (garden.health.controllers.isNotEmpty()) {
                item { ControllersCard(garden.health, nowS, cachedAtS == null, model::resetInterval) }
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
                PotRow(pot, nowS, model::open) { sheetFor = pot }
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
                        PotRow(pot, nowS, model::open) { sheetFor = pot }
                    }
                }
            }
        }
    }
}

/** Nothing on this screen came from the butler this launch. Loud on
 * purpose, in the error colour and above everything, because every number
 * underneath it is a memory. */
@Composable
private fun CachedBanner(line: String) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(line, Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall)
    }
}

/** A refresh failed but the last good read is still on screen: say both. */
@Composable
private fun StaleBanner(why: String) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Text(
            "refresh failed ($why) — showing the last good read",
            Modifier.padding(8.dp),
            style = MaterialTheme.typography.labelSmall,
        )
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

/** One line per controller; a leftover interval override (a wizard that
 * could not restore it) gets its reset here. */
@Composable
private fun ControllersCard(health: Health, nowS: Long, live: Boolean, reset: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            health.controllers.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        controllerLine(c, nowS, health.nextDefault),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
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
        }
    }
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
                    envStale(pot, nowS)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/** The two things worth doing to a row without opening it. Both end up in
 * the form anyway — this only saves the trip — so neither is destructive
 * from here: Delete opens the pot and its confirmation lives there, beside
 * the sentence that says what goes. */
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
private fun PotRow(pot: Pot, nowS: Long, open: (String) -> Unit, longPress: () -> Unit) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = { open(pot.id) }, onLongClick = longPress),
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
                rowNote(pot, nowS)?.let {
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
