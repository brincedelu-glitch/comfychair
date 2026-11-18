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
 * Dice button of a prompt field. A tap completes the prompt with random tags (the typed text stays
 * as it is); tapping again rerolls only the tags added by the previous roll. A long press chooses
 * how long the completion is.
 *
 * Editing the text after a roll locks the added tags in: they become part of what is typed.
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
    // What the last roll started from and produced, to reroll without keeping its own tags
    var lastBase by remember { mutableStateOf("") }
    var lastResult by remember { mutableStateOf<String?>(null) }

    fun roll(chosen: RandomPrompt.Length) {
        scope.launch {
            val current = state.value.text
            val base = if (lastResult != null && lastResult == current) lastBase else current
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
            lastBase = base
            lastResult = result
            state.value = TextFieldValue(result, TextRange(result.length))
            onTextChanged(result)
        }
    }

    val description = stringResource(R.string.content_description_random_prompt)
    Box(modifier = modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .combinedClickable(onClick = { roll(length) }, onLongClick = { menuOpen = true }),
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
