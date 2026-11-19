package sh.hnet.comfychair.util

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/**
 * Notes the user attaches to LoRAs (trigger words, recommended settings...), stored on the phone.
 * A note belongs to the file name, not to its folder, so moving a LoRA keeps its note.
 */
object LoraNotes {

    private const val PREFS = "lora_notes"

    // Observable, so menus recompose when a note is added or removed
    private val notes = mutableStateMapOf<String, String>()
    @Volatile private var loaded = false

    fun keyOf(loraPath: String): String =
        loraPath.replace('\\', '/').substringAfterLast('/').removeSuffix(".safetensors").lowercase()

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for ((key, value) in prefs.all) if (value is String && value.isNotBlank()) notes[key] = value
        loaded = true
    }

    fun get(loraPath: String): String = notes[keyOf(loraPath)] ?: ""

    fun has(loraPath: String): Boolean = !notes[keyOf(loraPath)].isNullOrBlank()

    /** Saves the note; an empty text removes it. */
    fun set(context: Context, loraPath: String, text: String) {
        ensureLoaded(context)
        val key = keyOf(loraPath)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (text.isBlank()) {
            notes.remove(key)
            prefs.edit().remove(key).apply()
        } else {
            notes[key] = text
            prefs.edit().putString(key, text).apply()
        }
    }

    /** Every note (key = file name), for the backup. */
    fun all(context: Context): Map<String, String> {
        ensureLoaded(context)
        return HashMap(notes)
    }

    /** Merges notes from a backup; existing notes are kept. */
    fun restore(context: Context, imported: Map<String, String>) {
        ensureLoaded(context)
        for ((key, text) in imported) if (key !in notes && text.isNotBlank()) set(context, key, text)
    }
}
