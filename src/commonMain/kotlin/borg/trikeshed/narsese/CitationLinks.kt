package borg.trikeshed.narsese

/**
 * The links a text makes without markup: its citations of codified law, each resolved to the Legal Information
 * Institute's page for it (https://www.law.cornell.edu). `15 U.S.C. § 1693` is
 * `/uscode/text/15/1693`; `12 C.F.R. § 1005.2` is `/cfr/text/12/1005.2`, and `12 C.F.R. Part 1005` the part.
 * The anchor is the citation as written, the line the text around it.
 */
object CitationLinks {
    const val LII = "https://www.law.cornell.edu"

    fun of(text: String): List<MarkupStreams.Link> {
        val out = LinkedHashMap<String, MarkupStreams.Link>()
        fun line(at: IntRange) = text.substring(maxOf(0, at.first - CONTEXT), minOf(text.length, at.last + 1 + CONTEXT))
        for (m in USC.findAll(text)) {
            // A span of sections (`§§ 1-16`) is read from its first section; LII has no page for the span itself.
            val href = "$LII/uscode/text/${m.groupValues[1]}/${m.groupValues[2]}"
            out.getOrPut(href) { MarkupStreams.Link(href, m.value.trim(), line(m.range)) }
        }
        for (m in CFR.findAll(text)) {
            val section = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
            val href = "$LII/cfr/text/${m.groupValues[1]}/${m.groupValues[2]}$section"
            out.getOrPut(href) { MarkupStreams.Link(href, m.value.trim(), line(m.range)) }
        }
        return out.values.toList()
    }

    /**
     * Each citation as its key path, outermost first: `15 U.S.C. § 1693f(a)(1)` is (15, U.S.C., 1693f, a, 1) and
     * `12 C.F.R. § 1005.2(b)` is (12, C.F.R., 1005, 2, b). A span of numbered sections (`§§ 1-16`) cites each of them.
     */
    fun keys(text: CharSequence): List<List<String>> {
        val out = ArrayList<List<String>>()
        fun subs(g: String) = SUB.findAll(g).map { it.groupValues[1] }.toList()
        for (m in USC.findAll(text)) {
            val (title, first, last, sub) = m.destructured
            val from = first.toIntOrNull(); val to = last.toIntOrNull()
            val sections = if (from != null && to != null && to > from && to - from <= SPAN) (from..to).map { it.toString() } else listOf(first)
            for (sec in sections) out.add(listOf(title, "U.S.C.", sec) + if (sections.size == 1) subs(sub) else emptyList())
        }
        for (m in CFR.findAll(text)) {
            val (title, part, section, sub) = m.destructured
            out.add(listOf(title, "C.F.R.", part) + listOfNotNull(section.takeIf { it.isNotEmpty() }) + subs(sub))
        }
        return out
    }

    private const val CONTEXT = 120
    /** The widest span of sections read as one citation each. */
    private const val SPAN = 100
    private val USC = Regex("\\b(\\d{1,2})\\s+U\\.?\\s?S\\.?\\s?C\\.?(?:A\\.?)?\\s+(?:§§?\\s*|[Ss]ec(?:tion|\\.)?\\s+)?(\\d+[a-z]?)(?:\\s*-\\s*(\\d+[a-z]?))?((?:\\([a-zA-Z0-9]+\\))*)")
    private val CFR = Regex("\\b(\\d{1,2})\\s+C\\.?\\s?F\\.?\\s?R\\.?\\s+(?:§§?\\s*|[Pp]arts?\\s+)?(\\d+)(?:\\.(\\d+[a-z]?))?((?:\\([a-zA-Z0-9]+\\))*)")
    private val SUB = Regex("\\(([a-zA-Z0-9]+)\\)")
}
