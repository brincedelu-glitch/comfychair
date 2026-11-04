package sh.hnet.comfychair.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One booru tag. [category] follows Danbooru: 0 general, 1 artist, 3 copyright, 4 character, 5 meta. */
data class TagSuggestion(val name: String, val category: Int, val count: Int)

/**
 * In-memory booru tag list for prompt autocompletion, searched locally (no network while typing).
 *
 * The list is a CSV with one tag per line and no header: `name,category,postCount,"alias1,alias2"`
 * (the format of the a1111 TagComplete extension's `danbooru.csv`). The app ships a small starter
 * list; the full list is downloaded once, on request, into the app's private storage.
 */
object TagIndex {

    /** Where the full list is downloaded from (the TagComplete extension, MIT license). */
    const val FULL_LIST_URL =
        "https://raw.githubusercontent.com/DominikDoom/a1111-sd-webui-tagcomplete/main/tags/danbooru.csv"

    private const val SEED_ASSET = "tags/seed.csv"
    private const val FULL_FILE = "tags/danbooru.csv"
    private const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024

    private val mutex = Mutex()

    /** The loaded list, swapped in as one piece so a search never sees a half-replaced list. */
    private class Data(
        val names: Array<String>,
        val aliases: Array<String>,
        val categories: ByteArray,
        val counts: IntArray
    )

    @Volatile private var data: Data? = null

    val isLoaded: Boolean get() = data != null

    /** True while only the small starter list is in use (the full list isn't downloaded yet). */
    @Volatile var isStarterList = true
        private set

    /** Loads the list on first use: the downloaded one if present, else the starter list. */
    suspend fun ensureLoaded(context: Context) {
        if (isLoaded) return
        mutex.withLock {
            if (isLoaded) return
            withContext(Dispatchers.IO) { load(context.applicationContext) }
        }
    }

    /**
     * Downloads the full list and switches to it. Returns false on any failure (the current list
     * stays in use).
     */
    suspend fun downloadFullList(context: Context): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val target = File(appContext.filesDir, FULL_FILE)
            val temp = File(appContext.filesDir, "$FULL_FILE.part")
            try {
                temp.parentFile?.mkdirs()
                val connection = (URL(FULL_LIST_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext false
                    var total = 0L
                    connection.inputStream.use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_DOWNLOAD_BYTES) return@withContext false
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }

                // Make sure it parses as a tag list before replacing anything
                if (!parse(temp.readText().lineSequence(), commit = false)) return@withContext false
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) return@withContext false
                load(appContext)
                true
            } catch (e: Exception) {
                DebugLogger.e("TagIndex", "Tag list download failed: ${e.message}")
                false
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
    }

    private fun load(context: Context) {
        val full = File(context.filesDir, FULL_FILE)
        if (full.exists() && full.length() > 0) {
            val ok = full.bufferedReader().use { parse(it.lineSequence(), commit = true) }
            if (ok) {
                isStarterList = false
                return
            }
        }
        context.assets.open(SEED_ASSET).bufferedReader().use { parse(it.lineSequence(), commit = true) }
        isStarterList = true
    }

    /** Parses CSV lines; when [commit] is true, the result replaces the loaded list. */
    private fun parse(lines: Sequence<String>, commit: Boolean): Boolean {
        val nameList = ArrayList<String>(120_000)
        val aliasList = ArrayList<String>(120_000)
        var categoryList = ByteArray(120_000)
        var countList = IntArray(120_000)

        for (line in lines) {
            if (line.isBlank()) continue
            val first = line.indexOf(',')
            if (first <= 0) continue
            val second = line.indexOf(',', first + 1)
            if (second < 0) continue
            val third = line.indexOf(',', second + 1)

            val category = line.substring(first + 1, second).trim().toIntOrNull() ?: continue
            val count = line.substring(second + 1, if (third < 0) line.length else third).trim().toIntOrNull() ?: 0
            val aliasText = if (third < 0) "" else line.substring(third + 1).trim().trim('"')

            val index = nameList.size
            if (index == categoryList.size) {
                categoryList = categoryList.copyOf(index * 2)
                countList = countList.copyOf(index * 2)
            }
            nameList.add(line.substring(0, first).trim().lowercase())
            aliasList.add(aliasText.lowercase())
            categoryList[index] = category.toByte()
            countList[index] = count
        }

        // Too few lines: not a tag list
        if (nameList.size < 10) return false
        if (commit) {
            data = Data(
                names = nameList.toTypedArray(),
                aliases = aliasList.toTypedArray(),
                categories = categoryList.copyOf(nameList.size),
                counts = countList.copyOf(nameList.size)
            )
        }
        return true
    }

    /**
     * Tags matching [query] (lowercase, underscores), best first: tags where the tag or one of its
     * words starts with it, then tags merely containing it, then tags whose alias matches. Within
     * each group the most used tags come first.
     *
     * A tag start and a word start are one group on purpose: character tags are mostly written
     * "family_given_(series)", so "mika" must find "misono_mika_(blue_archive)" next to "mikasa_...",
     * ranked by how popular they are rather than by where the match falls.
     */
    fun search(query: String, limit: Int): List<TagSuggestion> {
        val loaded = data
        if (loaded == null || query.isEmpty() || limit <= 0) return emptyList()
        val tagNames = loaded.names
        val tagAliases = loaded.aliases
        val tagCounts = loaded.counts
        val tagCategories = loaded.categories
        val wordStart = "_$query"

        var keys = LongArray(1024)
        var found = 0
        for (i in tagNames.indices) {
            val name = tagNames[i]
            val rank = when {
                name.startsWith(query) || name.contains(wordStart) -> 0
                name.contains(query) -> 1
                tagAliases[i].isNotEmpty() && tagAliases[i].contains(query) -> 2
                else -> continue
            }
            if (found == keys.size) keys = keys.copyOf(found * 2)
            // rank (2 bits) | inverted count (32 bits) | index (21 bits), sorts best first
            val invertedCount = 0xFFFFFFFFL - (tagCounts[i].toLong() and 0xFFFFFFFFL)
            keys[found++] = (rank.toLong() shl 53) or (invertedCount shl 21) or i.toLong()
        }

        java.util.Arrays.sort(keys, 0, found)
        val result = ArrayList<TagSuggestion>(minOf(limit, found))
        for (k in 0 until minOf(limit, found)) {
            val index = (keys[k] and 0x1FFFFFL).toInt()
            result.add(TagSuggestion(tagNames[index], tagCategories[index].toInt(), tagCounts[index]))
        }
        return result
    }
}
