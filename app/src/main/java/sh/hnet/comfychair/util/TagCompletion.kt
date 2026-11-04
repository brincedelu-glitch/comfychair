package sh.hnet.comfychair.util

/**
 * Pure text logic for booru tag autocompletion in the prompt field: find the tag being typed at the
 * cursor and build the new text when a suggestion is picked. No Android dependencies, so it is
 * easy to test.
 */
object TagCompletion {

    /** Minimum number of typed characters before suggestions are offered. */
    const val MIN_QUERY_LENGTH = 2

    /**
     * The tag being typed.
     *
     * @param start Index in the text where the replaced part begins
     * @param end Index where it ends (the cursor)
     * @param query What was typed, normalized for searching (lowercase, underscores)
     */
    data class Token(val start: Int, val end: Int, val query: String)

    private val twoLetters = Regex("[a-zA-Z]{2}")

    /**
     * Finds the tag being typed just before [cursor]: the text since the last comma or line break,
     * ignoring leading spaces and opening brackets (so "(mik" works). Returns null when no
     * suggestion makes sense (too short, inside a `<lora:...>` tag or a `:1.2` weight, ...).
     */
    fun findToken(text: String, cursor: Int): Token? {
        val end = cursor.coerceIn(0, text.length)
        var start = end
        while (start > 0 && text[start - 1] != ',' && text[start - 1] != '\n') start--
        while (start < end && (text[start].isWhitespace() || text[start] in "([{\\")) start++
        if (start >= end) return null

        val raw = text.substring(start, end)
        if (raw.startsWith("<")) return null
        if (raw.any { it == ':' || it == ')' || it == ']' || it == '}' || it == '|' }) return null

        val query = raw.replace("\\", "").trim().lowercase().replace(' ', '_')
        if (query.length < MIN_QUERY_LENGTH) return null
        return Token(start, end, query)
    }

    /** A tag formatted for insertion in a prompt. */
    fun formatTag(tag: String, useSpaces: Boolean): String {
        // Kaomoji tags such as "^_^" or ">_<" keep their underscores
        val spaced = if (useSpaces && twoLetters.containsMatchIn(tag)) tag.replace('_', ' ') else tag
        return spaced.replace("(", "\\(").replace(")", "\\)")
    }

    /**
     * Replaces [token] by [tag] in [text].
     *
     * A ", " is added after the tag unless the text already continues with a comma or a closing
     * bracket. Returns the new text and the cursor position after the inserted tag.
     */
    fun apply(text: String, token: Token, tag: String, useSpaces: Boolean): Pair<String, Int> {
        val insertion = formatTag(tag, useSpaces)
        val after = text.substring(token.end.coerceAtMost(text.length))
        val next = after.firstOrNull { !it.isWhitespace() }
        val separator = if (next == ',' || next == ')' || next == ']' || next == '}') "" else ", "
        val before = text.substring(0, token.start)
        val newText = before + insertion + separator + after.trimStart(' ')
        return newText to (before.length + insertion.length + separator.length)
    }
}
