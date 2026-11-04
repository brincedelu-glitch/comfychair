package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R

/**
 * Clear ("X") button for a text field that only reacts to a double tap, so it can't wipe a
 * prompt by accident. A single tap does nothing.
 *
 * TalkBack users get the same action as a custom action, since a double tap is already how
 * they activate a button.
 */
@Composable
fun DoubleTapClearButton(
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnClear by rememberUpdatedState(onClear)
    val description = stringResource(R.string.content_description_clear)

    Box(
        modifier = modifier
            .size(48.dp)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { currentOnClear() })
            }
            .semantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(description) {
                        currentOnClear()
                        true
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Clear,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
