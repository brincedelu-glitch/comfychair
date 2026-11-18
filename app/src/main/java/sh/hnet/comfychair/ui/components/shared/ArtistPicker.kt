package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.util.ArtistSelection
import sh.hnet.comfychair.util.TagCompletion

/** Most artists listed at once in the picker (the search narrows the rest down). */
private const val MAX_LISTED = 300

/**
 * Artist box of the generation options: no artist, a random one at every generation, or one chosen
 * from a searchable list. The artist is added to the prompt when the generation starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistPicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { ArtistSelection.ensurePrefs(context) }

    val mode = ArtistSelection.mode
    val chosen = ArtistSelection.chosen
    var showList by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.artist_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = mode == ArtistSelection.Mode.NONE,
                onClick = { ArtistSelection.setMode(context, ArtistSelection.Mode.NONE) },
                label = { Text(stringResource(R.string.artist_none)) }
            )
            FilterChip(
                selected = mode == ArtistSelection.Mode.RANDOM,
                onClick = { ArtistSelection.setMode(context, ArtistSelection.Mode.RANDOM) },
                label = { Text(stringResource(R.string.artist_random)) }
            )
            FilterChip(
                selected = mode == ArtistSelection.Mode.CHOSEN,
                onClick = {
                    if (chosen == null) {
                        showList = true
                    } else {
                        ArtistSelection.setMode(context, ArtistSelection.Mode.CHOSEN)
                    }
                },
                label = { Text(stringResource(R.string.artist_choose)) }
            )
        }
        if (mode == ArtistSelection.Mode.CHOSEN && chosen != null) {
            OutlinedButton(
                onClick = { showList = true },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Text(TagCompletion.formatTag(chosen, true).replace("\\(", "(").replace("\\)", ")"))
            }
        }
    }

    if (showList) {
        ArtistListDialog(
            onPick = {
                ArtistSelection.choose(context, it)
                showList = false
            },
            onDismiss = { showList = false }
        )
    }
}

@Composable
private fun ArtistListDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val artists = remember { ArtistSelection.artists(context) }
    var query by remember { mutableStateOf("") }
    val results = remember(query) {
        val q = query.trim().lowercase().replace(' ', '_')
        if (q.isEmpty()) artists.take(MAX_LISTED) else artists.filter { it.name.contains(q) }.take(MAX_LISTED)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.artist_label)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.artist_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (results.isEmpty()) {
                    Text(stringResource(R.string.artist_no_result))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(results, key = { it.name }) { artist ->
                            Text(
                                text = artist.name.replace('_', ' '),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(artist.name) }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
        }
    )
}
