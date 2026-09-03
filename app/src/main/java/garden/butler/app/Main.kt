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
import kotlinx.coroutines.delay

private const val REFRESH_EVERY_MS = 60_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { // stock Material3; theming is a no-go in v1
                App(viewModel())
            }
        }
    }
}

/** Four screens, one `when`: a navigation library would be the pitch's
 * architecture rabbit hole. The pot form owns its own back handling (it
 * has a discard dialog to ask first). The minute refresh lives here, not
 * on the list, so the form's readings tick too; the wizard polls on its
 * own and pauses it. */
@Composable
fun App(model: GardenViewModel) {
    val screen by model.screen.collectAsStateWithLifecycle()
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
    }
}
