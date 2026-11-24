package borg.trikeshed.lcnc

/** The dictionary a definition is applied from: its title, its edition and the year that edition was printed. */
data class DictionarySource(val dictionary: String, val edition: String, val year: Int)

/** One entry of a dictionary text: the headword as printed and the text that follows it, up to the next headword. */
data class DictionaryEntry(val headword: String, val definition: String)

/** Reads the entries of a dictionary text. A headword is a paragraph that opens with capitals, a full stop and the definition. */
object DictionaryEntries {
    private val headword = Regex("^([A-Z][A-Z .,&'-]{1,60}?)\\.\\s+(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val blankLine = Regex("\\n[ \\t\\u00A0]*\\n")
    private val space = Regex("[ \\t\\u00A0\\r\\n]+")

    /** The entries in the order the text prints them. Text before the first headword is not an entry. */
    fun read(text: String): List<DictionaryEntry> {
        val heads = ArrayList<String>()
        val bodies = ArrayList<StringBuilder>()
        for (paragraph in text.split(blankLine)) {
            val p = paragraph.trim()
            val match = headword.matchEntire(p)
            if (match != null) {
                heads += match.groupValues[1]
                bodies += StringBuilder(match.groupValues[2])
            } else if (bodies.isNotEmpty()) {
                bodies.last().append(' ').append(p)
            }
        }
        return heads.indices.map { DictionaryEntry(space.replace(heads[it], " ").trim(), space.replace(bodies[it], " ").trim()) }
    }
}
