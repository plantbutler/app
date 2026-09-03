package garden.butler.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The watering history: one pot's, or the whole garden's. Every row's
 * words come from a pure function in `Doses.kt`; this file is layout.
 *
 * The rows that went wrong are not filtered out — they are the reason the
 * screen exists — so they carry their own line in the error colour instead
 * of hiding among the clean ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DosesScreen(model: GardenViewModel, screen: Screen.Doses) {
    BackHandler(onBack = model::back)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screen.title) },
                navigationIcon = {
                    IconButton(onClick = model::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val doses = screen.doses
            when {
                doses == null && screen.why != null ->
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("The butler is not answering", style = MaterialTheme.typography.titleMedium)
                        Text(screen.why, style = MaterialTheme.typography.bodySmall)
                    }
                doses == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> DoseList(screen, doses, model)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DoseList(screen: Screen.Doses, doses: List<Dose>, model: GardenViewModel) {
    PullToRefreshBox(isRefreshing = screen.loading, onRefresh = model::reloadDoses) {
        LazyColumn(Modifier.fillMaxSize()) {
            screen.why?.let { why ->
                item {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    ) {
                        Text(
                            "reload failed ($why) — showing the last good read",
                            Modifier.padding(8.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            if (doses.isEmpty()) {
                item {
                    Text(
                        "Nothing has been poured yet.",
                        Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(doses, key = { it.id }) { dose -> DoseRow(dose, screen.potId == null, screen.nowS) }
            if (screen.more) {
                item {
                    // The commands table is never pruned, so the list is a
                    // page at a time rather than everything at once.
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (screen.loadingMore) {
                            CircularProgressIndicator()
                        } else {
                            Button(onClick = model::loadOlderDoses) { Text("Load older") }
                        }
                    }
                }
            } else if (doses.isNotEmpty()) {
                item {
                    Text(
                        "That is all of it.",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DoseRow(dose: Dose, showPot: Boolean, nowS: Long) {
    val trouble = doseTrouble(dose)
    ListItem(
        headlineContent = { Text(doseHistoryLine(dose, nowS)) },
        overlineContent = if (showPot) ({ Text(doseWho(dose)) }) else null,
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                trouble?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                doseSource(dose)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        },
        trailingContent = dose.verdict?.let { v -> ({ AssistChip(onClick = {}, label = { Text(verdictLabel(v)) }) }) },
    )
}
