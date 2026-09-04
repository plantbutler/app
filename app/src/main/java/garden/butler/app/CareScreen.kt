package garden.butler.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** The species lookup and the target-band offer: the two places where the
 * butler says something about the plant rather than about the wire.
 *
 * Neither writes anything on its own. The lookup puts words and pictures on
 * screen; the offer needs a tap on Apply, which is an ordinary pot edit.
 */
@Composable
fun SpeciesPanel(screen: Screen.Pot, model: GardenViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = model::lookUpSpecies, enabled = !screen.lookingUp && !screen.saving) {
            Text("Look up")
        }
        if (screen.lookingUp) Text("asking…", style = MaterialTheme.typography.bodySmall)
    }
    val answer = screen.lookup ?: return
    answer.accepted?.let { accepted ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(answer.care?.imageUrl, accepted)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(accepted, style = MaterialTheme.typography.titleSmall)
                commonName(answer.care, accepted)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                careLine(answer.care)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    Text(answer.note, style = MaterialTheme.typography.bodySmall)
    // Only when it differs from what is typed: the pot should hold the name
    // the caches are keyed on, so the garden can show this again later.
    betterName(answer, screen.draft["species"].orEmpty())?.let { name ->
        TextButton(onClick = { model.useName(name) }) { Text("Use $name") }
    }
    for (candidate in answer.candidates) {
        Row(
            Modifier.fillMaxWidth().clickable { model.pickCandidate(candidate.name) }.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(candidate.image, candidate.name)
            Column {
                Text(candidate.common ?: candidate.name, style = MaterialTheme.typography.bodyMedium)
                if (candidate.common != null) {
                    Text(candidate.name, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** The care source's photograph, or nothing. A plant with no picture is
 * common and must not leave a grey hole where one would be. */
@Composable
private fun Thumbnail(url: String?, name: String) {
    if (url == null) return
    AsyncImage(
        model = url,
        contentDescription = name,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
    )
}

/** The offered band. Apply is the approval the pitch asks for and nothing
 * else happens without it; Not now is remembered against these numbers, so
 * a repot or a change of season asks again. */
@Composable
fun AdviceCard(advice: Advice, enabled: Boolean, apply: () -> Unit, dismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(adviceLine(advice), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = apply, enabled = enabled) { Text("Apply") }
                TextButton(onClick = dismiss, enabled = enabled) { Text("Not now") }
            }
        }
    }
}
