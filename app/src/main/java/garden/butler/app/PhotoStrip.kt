package garden.butler.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val THUMB = 104.dp

/** A pot's own photographs, oldest first, with the camera at the end.
 *
 * Whether a month of watering did the plant any good is a question a
 * photograph answers and a moisture chart does not — so this sits under the
 * chart and reads the same way, left to right, over time.
 *
 * The care source's picture of the species, when there is one, sits first
 * and dimmed: it is the reference, what this plant is supposed to look
 * like, and never a stand-in for a picture of the actual pot.
 */
@Composable
fun PhotoStrip(screen: Screen.Pot, pot: Pot?, model: GardenViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nowS = System.currentTimeMillis() / 1000
    // One name, reused: the full-size original is worth nothing once it has
    // been shrunk and sent, and keeping them would be exactly the several
    // megabytes each that the pitch says to keep off the phone.
    val target = remember { cameraFile(context) }
    val uri =
        remember(target) {
            FileProvider.getUriForFile(context, context.packageName + ".photos", target)
        }
    var reading by remember { mutableStateOf(false) }
    var unreadable by remember { mutableStateOf<String?>(null) }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            if (!taken) return@rememberLauncherForActivityResult
            reading = true
            scope.launch {
                // Decoding twelve megapixels is not main-thread work, and
                // the strip must not freeze while it happens.
                val shrunk = withContext(Dispatchers.IO) { shrinkJpeg(context, uri) }
                reading = false
                if (shrunk == null) {
                    unreadable = "that picture could not be read — try taking it again"
                } else {
                    unreadable = null
                    model.addPhoto(shrunk.jpeg, shrunk.w, shrunk.h)
                }
            }
        }

    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Pictures", style = MaterialTheme.typography.titleSmall)
                TextButton(
                    onClick = { camera.launch(uri) },
                    enabled = screen.id != null && !screen.uploading && !reading,
                ) {
                    Text("Take one")
                }
            }
            val photos = screen.photos.orEmpty()
            if (photos.isEmpty()) {
                Text(
                    stripEmptyLine(screen.id != null),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                val ordered = strip(photos)
                val breaks = speciesBreaks(photos)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pot?.care?.imageUrl?.let { reference ->
                        item("reference") {
                            Reference(reference, pot.species ?: pot.care?.commonName)
                        }
                    }
                    items(ordered, key = { it.id }) { photo ->
                        // A pot outlives its plant, and nothing records a
                        // replant: the mark is where the species the picture
                        // was taken under changed.
                        if (photo.id in breaks) {
                            Break(photo.species)
                        }
                        Thumb(photo, nowS, model) { model.viewPhoto(photo.id) }
                    }
                }
            }
            if (reading || screen.uploading) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                    Text(
                        if (reading) "shrinking it…" else "sending it to the butler…",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            (unreadable ?: screen.photosWhy)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    screen.viewing?.let { id ->
        screen.photos?.firstOrNull { it.id == id }?.let { photo ->
            FullSize(photo, nowS, model)
        }
    }
}

/** One picture, small. A row whose file has gone shows the gap and says so
 * rather than a picture that will not load. */
@Composable
private fun Thumb(photo: Photo, nowS: Long, model: GardenViewModel, open: () -> Unit) {
    Column(
        Modifier.width(THUMB).clickable(enabled = !photo.missing, onClick = open),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(THUMB)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (photo.missing) {
                Text(
                    "gone",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Picture(photo.id, model, Modifier.size(THUMB))
            }
        }
        Text(
            photoDay(photo.ts, nowS),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}

/** The care source's own picture of the species. Dimmed and labelled, so it
 * is never mistaken for a picture of this pot. */
@Composable
private fun Reference(url: String, species: String?) {
    Column(Modifier.width(THUMB).alpha(0.55f), horizontalAlignment = Alignment.CenterHorizontally) {
        AsyncImage(
            model = url,
            contentDescription = species,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(THUMB).clip(RoundedCornerShape(8.dp)),
        )
        Text(
            "the species",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}

/** Where one plant ended and the next began. */
@Composable
private fun Break(species: String?) {
    Column(
        Modifier.width(56.dp).height(THUMB),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            species?.takeIf { it.isNotBlank() } ?: "replanted",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

/** The bytes, with the one header the photo routes want. Coil keys its
 * caches on the URL, and a photograph's id is minted once and its bytes
 * never change, so nothing is ever re-downloaded over the tailnet. */
@Composable
private fun Picture(photoId: String, model: GardenViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val source = remember(photoId) { model.photoSource(photoId) }
    AsyncImage(
        model =
            ImageRequest.Builder(context)
                .data(source.url)
                .addHeader("X-Token", source.token)
                .crossfade(true)
                .build(),
        contentDescription = "A picture of this plant",
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

@Composable
private fun FullSize(photo: Photo, nowS: Long, model: GardenViewModel) {
    var confirming by remember(photo.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { model.viewPhoto(null) },
        title = { Text(photoLine(photo, nowS)) },
        text = {
            if (confirming) {
                Text("Forget this picture? The butler deletes it; there is no other copy.")
            } else {
                Picture(photo.id, model, Modifier.fillMaxWidth().height(340.dp))
            }
        },
        confirmButton = {
            if (confirming) {
                TextButton(onClick = { model.deletePhoto(photo.id) }) { Text("Forget it") }
            } else {
                TextButton(onClick = { model.viewPhoto(null) }) { Text("Close") }
            }
        },
        dismissButton = {
            if (confirming) {
                TextButton(onClick = { confirming = false }) { Text("Keep it") }
            } else {
                TextButton(onClick = { confirming = true }) { Text("Delete") }
            }
        },
    )
    // Nothing to look at while a deleted picture's dialog closes.
    LaunchedEffect(photo.missing) { if (photo.missing) model.viewPhoto(null) }
}
