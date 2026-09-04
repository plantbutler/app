package garden.butler.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Where the butler is. The first screen on a phone that has never been
 * told, and reachable from the garden afterwards.
 *
 * Nothing here can be checked by looking at it: an address that parses may
 * have nothing behind it, and a token is only right or wrong to the butler
 * itself. So Connect makes a real call and the sentence under the fields
 * says which of three things went wrong — the address, the token, or what
 * is listening there. Only one of them is fixed by retyping the token.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(model: GardenViewModel, screen: Screen.Setup) {
    // On first start there is nothing behind this, so Back leaves the app,
    // as it does on the garden.
    BackHandler(enabled = !screen.first) { model.back() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (screen.first) "Where is the butler?" else "The butler") },
                navigationIcon = {
                    if (!screen.first) {
                        IconButton(onClick = model::back) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (screen.first) {
                    "This app talks to one backend of your own — the NAS on the tailnet, or a " +
                        "laptop on the LAN. Type where it is and the token it was given."
                } else {
                    "Where this app looks for the butler. Changing it forgets the garden it " +
                        "was showing: the offline copy belongs to one backend."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = screen.url,
                onValueChange = { model.editSetup(url = it) },
                label = { Text("Address") },
                placeholder = { Text("100.x.y.z:9380") },
                singleLine = true,
                enabled = !screen.checking,
                supportingText = {
                    // The one thing worth saying before anything is typed:
                    // plain http is normal here and https is not required.
                    Text("http:// is assumed — plain HTTP over the tailnet is the usual thing")
                },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        imeAction = ImeAction.Next,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = screen.token,
                onValueChange = { model.editSetup(token = it) },
                label = { Text("Token") },
                singleLine = true,
                enabled = !screen.checking,
                // Dots by default even when it is already on this device:
                // the value of showing it is only ever checking a paste, and
                // that is worth one tap rather than a secret left on screen.
                visualTransformation =
                    if (screen.show) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                trailingIcon = {
                    TextButton(onClick = { model.revealToken(!screen.show) }) {
                        Text(if (screen.show) "hide" else "show")
                    }
                },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        imeAction = ImeAction.Done,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
            screen.why?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = model::saveSetup,
                enabled = !screen.checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (screen.first) "Connect" else "Save")
            }
            if (screen.checking) {
                Text("asking that address…", style = MaterialTheme.typography.labelSmall)
                CircularProgressIndicator(Modifier.size(28.dp))
            }
            Text(
                "The token is kept in this phone's encrypted store and is never in the app " +
                    "itself, so the same install works on another phone with another butler.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
