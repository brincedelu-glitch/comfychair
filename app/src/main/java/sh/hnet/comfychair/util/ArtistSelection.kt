package sh.hnet.comfychair.util

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

/**
 * Artist tag added to the positive prompt when a generation starts: none, a random one, or one
 * chosen from the list. The prompt text itself is never touched, so a random artist changes at every
 * generation without cluttering the prompt field.
 *
 * The list is the most used artist tags of the booru list (assets/tags/artists.txt, "name,count" per
 * line, most used first).
 */
object ArtistSelection {

    enum class Mode { NONE, RANDOM, CHOSEN }

    data class Artist(val name: String, val count: Int)

    private const val PREFS = "artist_selection"
    private const val KEY_MODE = "mode"
    private const val KEY_CHOSEN = "chosen"
    private const val ASSET = "tags/artists.txt"

    var mode by mutableStateOf(Mode.NONE)
        private set

    /** Chosen artist (booru name, underscores), used in [Mode.CHOSEN]. */
    var chosen by mutableStateOf<String?>(null)
        private set

    private var prefsLoaded = false
    @Volatile private var artists: List<Artist>? = null

    /** Restores the saved choice (once). */
    fun ensurePrefs(context: Context) {
        if (prefsLoaded) return
        prefsLoaded = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = prefs.getString(KEY_MODE, null)?.let { saved -> Mode.values().firstOrNull { it.name == saved } } ?: Mode.NONE
        chosen = prefs.getString(KEY_CHOSEN, null)
    }

    fun setMode(context: Context, newMode: Mode) {
        ensurePrefs(context)
        mode = newMode
        save(context)
    }

    fun choose(context: Context, name: String) {
        ensurePrefs(context)
        chosen = name
        mode = Mode.CHOSEN
        save(context)
    }

    private fun save(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, mode.name)
            .putString(KEY_CHOSEN, chosen)
            .apply()
    }

    /** The artist list, most used first (read from the app's assets on first use). */
    fun artists(context: Context): List<Artist> {
        artists?.let { return it }
        val loaded = try {
            context.applicationContext.assets.open(ASSET).bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val comma = line.lastIndexOf(',')
                    if (comma <= 0) return@mapNotNull null
                    Artist(line.substring(0, comma), line.substring(comma + 1).trim().toIntOrNull() ?: 0)
                }.toList()
            }
        } catch (e: Exception) {
            DebugLogger.e("ArtistSelection", "Could not read the artist list: ${e.message}")
            emptyList()
        }
        artists = loaded
        return loaded
    }

    /**
     * Returns [prompt] with the artist tag appended according to the current mode (unchanged when
     * the mode is none, nothing is chosen, or the prompt already has that tag).
     */
    fun withArtist(context: Context?, prompt: String): String {
        if (context == null) return prompt
        ensurePrefs(context)
        val name = when (mode) {
            Mode.NONE -> null
            Mode.RANDOM -> artists(context).takeIf { it.isNotEmpty() }?.random(Random.Default)?.name
            Mode.CHOSEN -> chosen
        } ?: return prompt

        val tag = TagCompletion.formatTag(name, true)
        if (prompt.split(',').any { it.trim().equals(tag, ignoreCase = true) }) return prompt
        DebugLogger.i("ArtistSelection", "Artist added to the prompt (mode ${mode.name})")
        val head = prompt.trim().trimEnd(',', ' ', '\n')
        return if (head.isEmpty()) tag else "$head, $tag"
    }
}
