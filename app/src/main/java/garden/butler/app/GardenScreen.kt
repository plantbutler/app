package garden.butler.app

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

private const val REFRESH_EVERY_MS = 60_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GardenScreen(model: GardenViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { // numbers and "ago" labels tick while watched
                model.refresh()
                delay(REFRESH_EVERY_MS)
            }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Plant Butler") }) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val it = state) {
                is UiState.Loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is UiState.Trouble ->
                    Trouble(it, model::refresh, Modifier.align(Alignment.Center))
                is UiState.Ready ->
                    GardenList(it.garden, it.refreshing, it.why, model::refresh)
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
    refresh: () -> Unit,
) {
    val nowS = System.currentTimeMillis() / 1000
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = refresh) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (why != null) {
                item { StaleBanner(why) }
            }
            if (garden.problems.isNotEmpty()) {
                item { ProblemStrip(garden.problems) }
            }
            if (garden.env.isNotEmpty()) {
                item { EnvCard(garden.env, nowS) }
            }
            items(garden.pots, key = { it.name }) { pot -> PotRow(pot, nowS) }
            if (garden.pots.isEmpty()) {
                item {
                    Text(
                        "No pots yet — POST /pot on the backend to plant one.",
                        Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
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

@Composable
private fun EnvCard(env: List<Pot>, nowS: Long) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            env.forEach { pot ->
                val (label, value) = envEntry(pot)
                Column {
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

@Composable
private fun PotRow(pot: Pot, nowS: Long) {
    ListItem(
        headlineContent = { Text(pot.name) },
        supportingContent = {
            Column {
                Text(potLine(pot, nowS))
                pot.proposal?.let {
                    Text(
                        "proposal waiting: ${it.ml ?: "?"} ml — approve on the backend",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        trailingContent = {
            if (pot.mode != "manual") {
                AssistChip(onClick = {}, label = { Text(pot.mode) })
            }
        },
    )
}
