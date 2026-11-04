package sh.hnet.comfychair.ui.components.shared

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.util.TagCompletion
import sh.hnet.comfychair.util.TagIndex
import sh.hnet.comfychair.util.TagSuggestion

/** Underscores in tags become spaces when inserted ("long_hair" -> "long hair"). */
private const val USE_SPACES = true

/** Most suggestions kept for one query (shown in the scrolling row and in the expanded view). */
private const val MAX_SUGGESTIONS = 100

private const val SEARCH_DEBOUNCE_MS = 80L

/**
 * Text and cursor of a prompt field. The prompt fields only kept a String, which doesn't know where
 * the cursor is; the autocompletion needs that to complete the word being typed.
 */
@Stable
class TagPromptState(initial: String) {
    var value by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
}

/**
 * Remembers the field's [TagPromptState]. When [text] (the ViewModel's prompt) changes from
 * outside (preset applied, prompt reset or cleared), the field follows it.
 */
@Composable
fun rememberTagPromptState(text: String): TagPromptState {
    val state = remember { TagPromptState(text) }
    if (state.value.text != text) {
        state.value = TextFieldValue(text, TextRange(text.length))
    }
    return state
}

/**
 * Row of booru tag suggestions for the word being typed in a prompt field. Swipe it sideways to see
 * more; the "..." button opens all of them at once. Picking one replaces the typed word.
 *
 * @param visible Normally the field's focus state: suggestions only make sense while typing
 * @param onTextChanged Called with the new prompt text after a suggestion was inserted
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagSuggestionBar(
    state: TagPromptState,
    visible: Boolean,
    onTextChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val value = state.value

    val token = remember(value.text, value.selection) {
        if (value.selection.collapsed) TagCompletion.findToken(value.text, value.selection.start) else null
    }

    var suggestions by remember { mutableStateOf<List<TagSuggestion>>(emptyList()) }
    var starterList by remember { mutableStateOf(TagIndex.isStarterList) }
    var expanded by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    // Bumped after a download so the current query is searched again in the new list
    var listVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(token?.query, listVersion) {
        val query = token?.query
        if (query == null) {
            suggestions = emptyList()
            expanded = false
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        TagIndex.ensureLoaded(context)
        suggestions = withContext(Dispatchers.Default) {
            TagIndex.search(query, MAX_SUGGESTIONS).distinctBy { it.name }
        }
        starterList = TagIndex.isStarterList
    }

    // Nothing to offer: no suggestion, and no full list to download either
    if (token == null || (suggestions.isEmpty() && !starterList)) return

    fun pick(tag: TagSuggestion) {
        val current = state.value
        val currentToken = TagCompletion.findToken(current.text, current.selection.start) ?: return
        val (newText, cursor) = TagCompletion.apply(current.text, currentToken, tag.name, USE_SPACES)
        state.value = TextFieldValue(newText, TextRange(cursor))
        expanded = false
        onTextChanged(newText)
    }

    val downloadOk = stringResource(R.string.tag_suggestions_download_ok)
    val downloadFailed = stringResource(R.string.tag_suggestions_download_failed)
    fun download() {
        if (downloading) return
        downloading = true
        scope.launch {
            val ok = TagIndex.downloadFullList(context)
            downloading = false
            starterList = TagIndex.isStarterList
            if (ok) listVersion++
            Toast.makeText(context, if (ok) downloadOk else downloadFailed, Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                items(suggestions, key = { it.name }) { tag ->
                    TagChip(tag = tag, onClick = { pick(tag) })
                }
                if (starterList) {
                    item(key = "download") {
                        AssistChip(
                            onClick = { download() },
                            enabled = !downloading,
                            label = {
                                Text(
                                    stringResource(
                                        if (downloading) R.string.tag_suggestions_downloading
                                        else R.string.tag_suggestions_download
                                    )
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                    }
                }
            }
            if (suggestions.size > 1) {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.Close else Icons.Default.MoreHoriz,
                        contentDescription = stringResource(
                            if (expanded) R.string.tag_suggestions_hide else R.string.tag_suggestions_show_all
                        )
                    )
                }
            }
        }

        AnimatedVisibility(visible = expanded && suggestions.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    suggestions.forEach { tag -> TagChip(tag = tag, onClick = { pick(tag) }) }
                }
            }
        }
    }
}

@Composable
private fun TagChip(tag: TagSuggestion, onClick: () -> Unit) {
    val label = if (USE_SPACES) TagCompletion.formatTag(tag.name, useSpaces = true).replace("\\", "") else tag.name
    val dotColor = categoryColor(tag.category)
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = if (dotColor != null) {
            {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
        } else null
    )
}

/** Small colored dot per Danbooru category; general tags get none. */
private fun categoryColor(category: Int): Color? = when (category) {
    1 -> Color(0xFFE57373)   // artist
    3 -> Color(0xFFBA68C8)   // copyright
    4 -> Color(0xFF66BB6A)   // character
    5 -> Color(0xFFFFB74D)   // meta
    else -> null
}
