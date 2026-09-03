package garden.butler.app

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** The wizard's face: one card per CalState. Polling runs only while the
 * screen is started, and leaving the foreground cancels the wizard — a
 * phone in a pocket must not keep a board at 5 s, and the restore has to
 * run while the app is still awake to run it. A rotation is not leaving. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrateScreen(model: GardenViewModel, screen: Screen.Calibrate) {
    val state by model.state.collectAsStateWithLifecycle()
    // Through the id: a pot renamed from another phone mid-wizard still
    // titles this screen, and titles it with the name it now has.
    val pot = screen.parent.id?.let { (state as? UiState.Ready)?.garden?.potById(it) }
    val name = pot?.name ?: screen.parent.original["name"].orEmpty()
    val controller = pot?.controller ?: "the board"
    var nowS by remember { mutableLongStateOf(model.nowS()) }
    val owner = LocalLifecycleOwner.current
    val activity = LocalContext.current as? Activity
    LaunchedEffect(Unit) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (true) {
                    model.calPoll()
                    nowS = model.nowS()
                    delay(POLL_MS)
                }
            } finally {
                if (activity?.isChangingConfigurations != true) model.calEvent(CalEvent.Cancel)
            }
        }
    }
    val send = { e: CalEvent -> model.calEvent(e) }
    Scaffold(topBar = { TopAppBar(title = { Text("Recalibrate $name") }) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(
                    Modifier.padding(20.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Body(screen.cal, controller, nowS, send)
                }
            }
        }
    }
}

@Composable
private fun Body(cal: CalState, controller: String, nowS: Long, send: (CalEvent) -> Unit) {
    when (cal) {
        is CalState.SpeedingUp -> {
            Centered("Asking $controller to report every $FAST_NEXT_S s… up to ${cal.timeoutS} s")
            CircularProgressIndicator()
            cal.seen.firstOrNull()?.let { Small("last report ${agoText(it.readTs, nowS)}") }
            Cancel(send)
        }
        is CalState.Stalled -> {
            Centered("$controller never sped up")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { send(CalEvent.Retry) }) { Text("Retry") }
                OutlinedButton(onClick = { send(CalEvent.ContinueSlow) }) { Text("Continue slowly") }
            }
            Cancel(send)
        }
        is CalState.Air -> {
            Readings("Hold the sensor in the AIR. Wait for the number to settle.", cal, nowS)
            Button(onClick = { send(CalEvent.Tap) }, enabled = canTap(cal, nowS)) { Text("Tap") }
            Cancel(send)
        }
        is CalState.Water -> {
            Readings("Hold the sensor in a glass of WATER. Wait for the number to settle.", cal, nowS)
            Small("dry ${cal.dry}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { send(CalEvent.Tap) }, enabled = canTap(cal, nowS)) { Text("Tap") }
                OutlinedButton(onClick = { send(CalEvent.BackToAir) }) { Text("Back to air") }
            }
            Cancel(send)
        }
        is CalState.Review -> {
            Text("dry ${cal.dry} · wet ${cal.wet}", style = MaterialTheme.typography.titleLarge)
            calHint(cal.dry, cal.wet)?.let { Centered(it) }
            cal.refused?.let {
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { send(CalEvent.Save) }) { Text("Save") }
                OutlinedButton(onClick = { send(CalEvent.BackToAir) }) { Text("Back to air") }
            }
            Cancel(send)
        }
        is CalState.Saving -> CircularProgressIndicator() // the POST is on the wire: no way out
        is CalState.Finished -> {
            Text("dry ${cal.dry} · wet ${cal.wet} saved", style = MaterialTheme.typography.titleLarge)
            Restoring(controller)
        }
        is CalState.Cancelled -> Restoring(controller)
    }
}

/** The driver pops once the board is back on its pace; until then the
 * wizard says what it is waiting for rather than hanging blank. */
@Composable
private fun Restoring(controller: String) {
    Centered("Putting $controller back to its usual pace…")
    CircularProgressIndicator()
}

/** The number to watch, how old it is, and the few before it so "settled"
 * is visible rather than guessed — plus how many of the three this endpoint
 * would be the median of. Tapping with fewer is allowed; it says what it
 * would use. */
@Composable
private fun Readings(instruction: String, cal: CalState, nowS: Long) {
    Centered(instruction)
    val seen = if (cal is CalState.Air) cal.seen else (cal as CalState.Water).seen
    val newest = seen.firstOrNull()
    Text(newest?.raw?.toString() ?: "—", style = MaterialTheme.typography.displayMedium)
    Small(newest?.let { agoText(it.readTs, nowS) } ?: "waiting for a report")
    if (seen.isNotEmpty()) Small(seen.joinToString(" · ") { "${it.raw}" })
    val samples = tapSamples(cal)
    Small(settleLine(samples.size))
}

@Composable
private fun Centered(text: String) = Text(text, textAlign = TextAlign.Center)

@Composable
private fun Small(text: String) = Text(text, style = MaterialTheme.typography.labelSmall)

@Composable
private fun Cancel(send: (CalEvent) -> Unit) {
    TextButton(onClick = { send(CalEvent.Cancel) }) { Text("Cancel") }
}
