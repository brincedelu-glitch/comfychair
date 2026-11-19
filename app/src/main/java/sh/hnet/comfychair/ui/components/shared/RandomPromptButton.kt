package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.util.RandomPrompt
import sh.hnet.comfychair.util.TagIndex

/** Booru category of character tags. */
private const val CHARACTER_CATEGORY = 4

/**
 * Dice button of a prompt field. A double tap completes the prompt with random tags (the typed text
 * stays as it is); another double tap rerolls only the tags added by the previous roll, even after
 * the text was edited in between: tags the user typed since stay, the dice's own tags are rerolled.
 * A single tap does nothing, so a stray tap can't replace the prompt. A long press chooses the
 * length and the NSFW mode.
 *
 * @param onTextChanged Called with the new prompt text after a roll
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RandomPromptButton(
    state: TagPromptState,
    onTextChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var length by rememberSaveable { mutableStateOf(RandomPrompt.Length.MEDIUM) }
    var nsfw by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // The tags added by the last roll (normalized), taken out again before the next one
    var lastAdded by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun roll(chosen: RandomPrompt.Length) {
        scope.launch {
            // Everything typed except what the dice added before
            val base = state.value.text
                .split(',', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() && RandomPrompt.normalize(it) !in lastAdded }
                .joinToString(", ")
            val vocabulary = RandomPrompt.ensureLoaded(context)
            TagIndex.ensureLoaded(context)
            val result = withContext(Dispatchers.Default) {
                RandomPrompt.generate(
                    base = base,
                    length = chosen,
                    vocab = vocabulary,
                    isCharacter = { TagIndex.categoryOf(it) == CHARACTER_CATEGORY },
                    nsfw = nsfw,
                    // Only tags that exist in the booru list (the vocabulary was written by hand)
                    exists = { TagIndex.exists(it) }
                )
            }
            lastAdded = result.added.map { RandomPrompt.normalize(it) }.toSet()
            state.value = TextFieldValue(result.text, TextRange(result.text.length))
            onTextChanged(result.text)
        }
    }

    val description = stringResource(R.string.content_description_random_prompt)
    Box(modifier = modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .combinedClickable(
                    onClick = {},
                    onDoubleClick = { roll(length) },
                    onLongClick = { menuOpen = true }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Casino,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            for (option in RandomPrompt.Length.values()) {
                val label = when (option) {
                    RandomPrompt.Length.SHORT -> R.string.random_prompt_short
                    RandomPrompt.Length.MEDIUM -> R.string.random_prompt_medium
                    RandomPrompt.Length.LONG -> R.string.random_prompt_long
                    RandomPrompt.Length.XL -> R.string.random_prompt_xl
                }
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = {
                        if (option == length) Icon(Icons.Default.Check, contentDescription = null)
                    },
                    onClick = {
                        length = option
                        menuOpen = false
                        roll(option)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.random_prompt_nsfw)) },
                leadingIcon = {
                    if (nsfw) Icon(Icons.Default.Check, contentDescription = null)
                },
                onClick = {
                    nsfw = !nsfw
                    menuOpen = false
                    roll(length)
                }
            )
        }
    }
}
