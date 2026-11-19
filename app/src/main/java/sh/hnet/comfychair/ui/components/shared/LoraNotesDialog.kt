package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.util.LoraNotes

/**
 * Edits the note of a LoRA (trigger words...). The text field can be selected and copied by hand,
 * and the Copy button copies the whole note.
 */
@Composable
fun LoraNotesDialog(loraPath: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by remember(loraPath) { mutableStateOf(LoraNotes.get(loraPath)) }
    val fileName = loraPath.replace('\\', '/').substringAfterLast('/').removeSuffix(".safetensors")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lora_notes_title), maxLines = 1) },
        text = {
            Column {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.lora_notes_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp)
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(text))
                }) { Text(stringResource(R.string.lora_notes_copy)) }
                TextButton(onClick = {
                    LoraNotes.set(context, loraPath, text.trim())
                    onDismiss()
                }) { Text(stringResource(R.string.lora_notes_save)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}
