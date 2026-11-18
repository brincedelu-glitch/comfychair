package sh.hnet.comfychair.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Random prompt completion without any AI: the tags the user already typed stay untouched, and the
 * missing parts of a scene (outfit, background, expression, lighting...) are filled with one tag
 * per slot, picked from an editable vocabulary (assets/tags/random_prompt.txt).
 *
 * Coherence comes from themes: one theme is picked per roll (or deduced from the typed tags), and
 * outfit, background and props all come from it. A slot the user already filled is left alone, and
 * when a character tag is present, hair and eyes are skipped so the character's looks aren't
 * contradicted.
 */
object RandomPrompt {

    enum class Length { SHORT, MEDIUM, LONG }

    /** One slot's candidates with their weights. */
    class Slot(val tags: List<String>, val weights: List<Int>) {
        val totalWeight = weights.sum()
    }

    class Theme(val name: String, val slots: Map<String, Slot>)

    class Vocabulary(
        val common: Map<String, Slot>,
        val themes: List<Theme>,
        /** Replaces the matching common slots in NSFW mode. */
        val nsfwCommon: Map<String, Slot> = emptyMap(),
        /** Replace the regular themes in NSFW mode. */
        val nsfwThemes: List<Theme> = emptyList()
    ) {
        /** Every tag a slot can hold, over all lists (to spot filled slots). */
        val tagsBySlot: Map<String, Set<String>> by lazy {
            val result = HashMap<String, MutableSet<String>>()
            for (slots in listOf(common, nsfwCommon)) {
                for ((name, slot) in slots) result.getOrPut(name) { HashSet() }.addAll(slot.tags)
            }
            for (theme in themes + nsfwThemes) for ((name, slot) in theme.slots) {
                result.getOrPut(name) { HashSet() }.addAll(slot.tags)
            }
            result
        }
    }

    // Slots in the order they are written into the prompt, per length
    private val SHORT = listOf("subject", "hair_color", "outfit", "expression", "background")
    private val MEDIUM = listOf(
        "subject", "hair_color", "hair_length", "eyes", "outfit", "expression", "gaze", "pose",
        "framing", "background", "lighting"
    )
    private val LONG = listOf(
        "subject", "hair_color", "hair_length", "hair_style", "eyes", "outfit", "accessory",
        "expression", "gaze", "pose", "framing", "background", "prop", "prop", "lighting", "mood"
    )
    private val NSFW_SHORT = listOf(
        "subject", "partner", "hair_color", "outfit", "expression", "pose", "background", "detail"
    )
    private val NSFW_MEDIUM = listOf(
        "subject", "partner", "hair_color", "hair_length", "eyes", "body", "outfit", "expression",
        "gaze", "pose", "framing", "background", "detail", "detail", "lighting"
    )
    private val NSFW_LONG = listOf(
        "subject", "partner", "hair_color", "hair_length", "hair_style", "eyes", "body", "outfit",
        "accessory", "expression", "gaze", "pose", "framing", "background", "prop", "prop",
        "detail", "detail", "detail", "lighting", "mood"
    )

    /** Slots that describe the looks of a character, already fixed once a character tag is typed. */
    private val CHARACTER_SLOTS = setOf("hair_color", "hair_length", "hair_style", "eyes")

    /** Tags ending in "eyes" that describe an expression, not the eye color. */
    private val EXPRESSION_EYES = setOf("closed eyes", "half-closed eyes", "wide eyes", "empty eyes", "covered eyes")

    private val SUBJECT_REGEX = Regex("""\d+\+?(girl|girls|boy|boys|other|others)""")

    @Volatile private var vocabulary: Vocabulary? = null

    suspend fun ensureLoaded(context: Context): Vocabulary {
        vocabulary?.let { return it }
        return withContext(Dispatchers.IO) {
            val loaded = context.applicationContext.assets.open("tags/random_prompt.txt")
                .bufferedReader().use { parse(it.readLines()) }
            vocabulary = loaded
            loaded
        }
    }

    /** Parses the vocabulary file (see its header for the format). */
    fun parse(lines: List<String>): Vocabulary {
        val common = LinkedHashMap<String, Slot>()
        val nsfwCommon = LinkedHashMap<String, Slot>()
        val themes = ArrayList<Theme>()
        val nsfwThemes = ArrayList<Theme>()
        var section: MutableMap<String, Slot> = common
        var themeName: String? = null
        var themeIsNsfw = false

        fun closeTheme() {
            themeName?.let { (if (themeIsNsfw) nsfwThemes else themes).add(Theme(it, section)) }
            themeName = null
        }

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                closeTheme()
                val title = line.substring(1, line.length - 1).trim()
                if (title.startsWith("theme ")) {
                    var name = title.removePrefix("theme ").trim()
                    themeIsNsfw = name.endsWith(" nsfw")
                    if (themeIsNsfw) name = name.removeSuffix(" nsfw").trim()
                    themeName = name
                    section = LinkedHashMap()
                } else {
                    section = if (title == "nsfw") nsfwCommon else common
                }
                continue
            }
            val colon = line.indexOf(':')
            // ":d" is a tag, the slot name ends at the first colon followed by a space
            val separator = Regex(""":\s""").find(line)?.range?.first ?: colon
            if (separator <= 0) continue
            val slotName = line.substring(0, separator).trim()
            val tags = ArrayList<String>()
            val weights = ArrayList<Int>()
            for (item in line.substring(separator + 1).split(',')) {
                val entry = item.trim()
                if (entry.isEmpty()) continue
                val star = entry.lastIndexOf('*')
                val weight = if (star > 0) entry.substring(star + 1).toIntOrNull() else null
                tags.add(normalize(if (weight != null) entry.substring(0, star) else entry))
                weights.add(weight?.coerceAtLeast(1) ?: 1)
            }
            if (tags.isNotEmpty()) section[slotName] = Slot(tags, weights)
        }
        closeTheme()
        return Vocabulary(common, themes, nsfwCommon, nsfwThemes)
    }

    /** Comparable form of a tag: lowercase, spaces for underscores, no weight or escaping. */
    fun normalize(tag: String): String {
        var text = tag.trim().lowercase().replace('_', ' ').replace("\\(", "(").replace("\\)", ")")
        // "(blue eyes:1.2)" -> "blue eyes"; "mika (blue archive)" keeps its own parentheses
        while (text.length > 2 && isWrapped(text)) text = text.substring(1, text.length - 1).trim()
        val weight = Regex(""":\s*-?\d+(\.\d+)?$""").find(text)
        if (weight != null) text = text.substring(0, weight.range.first)
        return text.trim()
    }

    /** True when one pair of brackets encloses the whole text. */
    private fun isWrapped(text: String): Boolean {
        val open = text.first()
        val close = when (open) {
            '(' -> ')'
            '[' -> ']'
            '{' -> '}'
            else -> return false
        }
        if (text.last() != close) return false
        var depth = 0
        for (i in text.indices) {
            if (text[i] == open) depth++ else if (text[i] == close) depth--
            if (depth == 0 && i < text.length - 1) return false
        }
        return depth == 0
    }

    /**
     * Completes [base] (what the user typed, possibly empty) and returns the whole new prompt.
     *
     * @param isCharacter Tells whether a normalized tag is a character name (booru category 4)
     * @param nsfw Use the NSFW themes and slots
     * @param exists Tells whether a tag is in the booru list; tags it rejects are never picked (null: no check)
     */
    fun generate(
        base: String,
        length: Length,
        vocab: Vocabulary,
        isCharacter: (String) -> Boolean,
        nsfw: Boolean = false,
        exists: ((String) -> Boolean)? = null,
        random: Random = Random.Default
    ): String {
        val typed = base.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val typedNormalized = typed.map { normalize(it) }.toSet()

        val filled = HashSet<String>()
        for ((slotName, tags) in vocab.tagsBySlot) {
            // Props add up, a typed prop doesn't use the slot up
            if (slotName == "prop") continue
            if (typedNormalized.any { it in tags }) filled.add(slotName)
        }
        // Tags the vocabulary doesn't list still fill a slot ("pink hair", "heterochromia"...)
        for (tag in typedNormalized) {
            if (tag.endsWith(" eyes") && tag !in EXPRESSION_EYES) filled.add("eyes")
            if (tag.endsWith(" hair") && tag !in (vocab.tagsBySlot["hair_length"] ?: emptySet())) filled.add("hair_color")
            if (tag == "1boy" || tag == "2boys" || tag == "hetero" || tag == "yuri" || tag == "multiple boys") {
                filled.add("partner")
            }
            if (SUBJECT_REGEX.matches(tag) || tag == "solo" || tag == "multiple girls" || tag == "multiple boys") {
                filled.add("subject")
            }
        }

        // Names the vocabulary doesn't know are looked up as possible characters
        val vocabTags = vocab.tagsBySlot.values.flatten().toHashSet()
        val hasCharacter = typedNormalized.any { it !in vocabTags && isCharacter(it.replace(' ', '_')) }
        if (hasCharacter) {
            filled.addAll(CHARACTER_SLOTS)
            filled.add("subject")
        }

        val theme = pickTheme(if (nsfw) vocab.nsfwThemes else vocab.themes, typedNormalized, random)
        val plan = when (length) {
            Length.SHORT -> if (nsfw) NSFW_SHORT else SHORT
            Length.MEDIUM -> if (nsfw) NSFW_MEDIUM else MEDIUM
            Length.LONG -> if (nsfw) NSFW_LONG else LONG
        }

        val used = HashSet(typedNormalized)
        val added = ArrayList<String>()
        for (slotName in plan) {
            if (slotName in filled) continue
            val slot = theme?.slots?.get(slotName)
                ?: (if (nsfw) vocab.nsfwCommon[slotName] else null)
                ?: vocab.common[slotName]
                ?: continue
            val tag = pick(slot, used, exists, random) ?: continue
            used.addAll(tag.split(", "))
            added.add(tag)
        }

        if (added.isEmpty()) return base
        val head = base.trim().trimEnd(',', ' ', '\n')
        return if (head.isEmpty()) added.joinToString(", ") else head + ", " + added.joinToString(", ")
    }

    /** The theme the typed tags belong to, else a random one. */
    private fun pickTheme(themes: List<Theme>, typed: Set<String>, random: Random): Theme? {
        if (themes.isEmpty()) return null
        val scores = themes.map { theme ->
            theme to theme.slots.values.sumOf { slot -> slot.tags.count { it in typed } }
        }
        val best = scores.maxOf { it.second }
        if (best == 0) return themes.random(random)
        return scores.filter { it.second == best }.map { it.first }.random(random)
    }

    private fun pick(slot: Slot, used: Set<String>, exists: ((String) -> Boolean)?, random: Random): String? {
        // An entry can be several tags ("a, b"); it is usable when none is used yet and all exist
        fun usable(i: Int): Boolean {
            val parts = slot.tags[i].split(", ")
            return parts.none { it in used } && (exists == null || parts.all { exists(it.replace(' ', '_')) })
        }
        var total = 0
        for (i in slot.tags.indices) if (usable(i)) total += slot.weights[i]
        if (total <= 0) return null
        var roll = random.nextInt(total)
        for (i in slot.tags.indices) {
            if (!usable(i)) continue
            roll -= slot.weights[i]
            if (roll < 0) return slot.tags[i]
        }
        return null
    }
}
