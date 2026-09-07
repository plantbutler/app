// The few pieces more than one screen draws, said once so they cannot drift.
package garden.butler.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Whatever went wrong, in the colour that says so. The size is the caller's
 * — a refusal under a button is not the banner over a whole screen — but the
 * colour is not, so nothing anywhere invents its own shade of wrong. */
@Composable
fun ErrorText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    textAlign: TextAlign? = null,
) {
    Text(text, color = MaterialTheme.colorScheme.error, style = style, textAlign = textAlign)
}

/** A read failed but the last good one is still on screen: say both, and
 * quietly — what is under this banner is still the butler's own words, which
 * is what tells it apart from the cached banner's error colour. */
@Composable
fun StaleCard(line: String) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Text(line, Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
    }
}

/** Nothing to show at all, and why. `more` is whatever the screen can offer
 * about it — a retry on the garden, nothing on a history that has a Back. */
@Composable
fun NotAnswering(
    why: String,
    modifier: Modifier = Modifier,
    more: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("The butler is not answering", style = MaterialTheme.typography.titleMedium)
        Text(why, style = MaterialTheme.typography.bodySmall)
        more()
    }
}
