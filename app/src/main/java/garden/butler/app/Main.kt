// The one activity: what the view model needs from Android, and the screen switch.
package garden.butler.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.delay

private const val REFRESH_EVERY_MS = 60_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { // stock Material3; a theme of our own is not in scope
                // The cache needs the app's own storage and the settings its
                // keystore, which is the only reason this view model is not
                // the no-argument one.
                val cache = FileGardenCache(File(applicationContext.filesDir, "garden.json"))
                val settings = EncryptedConfigStore(applicationContext)
                App(viewModel(factory = GardenViewModel.factory(cache, settings)))
            }
        }
    }
}

/** One `when` over the screens, in place of a navigation library. The pot
 * form owns its own back handling: it has a discard dialog to ask first.
 * The minute refresh lives here, not on the list, so the form's readings
 * tick too; the wizard polls on its own and pauses it. */
@Composable
fun App(model: GardenViewModel) {
    val screen by model.screen.collectAsStateWithLifecycle()
    // Disk first, so there is something to look at before the network has
    // been given its five seconds to fail.
    LaunchedEffect(Unit) { model.openCache() }
    val owner = LocalLifecycleOwner.current
    val calibrating = screen is Screen.Calibrate
    LaunchedEffect(calibrating) {
        if (calibrating) return@LaunchedEffect
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { // numbers and "ago" labels tick while watched
                model.refresh()
                delay(REFRESH_EVERY_MS)
            }
        }
    }
    BackHandler(enabled = calibrating) { model.calEvent(CalEvent.Cancel) }
    when (val it = screen) {
        Screen.List -> GardenScreen(model)
        is Screen.Pot -> PotScreen(model, it)
        is Screen.Calibrate -> CalibrateScreen(model, it)
        is Screen.Doses -> DosesScreen(model, it)
        is Screen.Setup -> SetupScreen(model, it)
    }
}
