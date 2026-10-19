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
            val section = m.groupValues[2].let { if (m.value.contains("§§")) it.substringBefore('-') else it }
            val href = "$LII/uscode/text/${m.groupValues[1]}/$section"
            out.getOrPut(href) { MarkupStreams.Link(href, m.value.trim(), line(m.range)) }
        }
        for (m in CFR.findAll(text)) {
            val section = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
            val href = "$LII/cfr/text/${m.groupValues[1]}/${m.groupValues[2]}$section"
            out.getOrPut(href) { MarkupStreams.Link(href, m.value.trim(), line(m.range)) }
        }
        return out.values.toList()
    }

    private const val CONTEXT = 120
    private val USC = Regex("\\b(\\d{1,2})\\s+U\\.?\\s?S\\.?\\s?C\\.?(?:A\\.?)?\\s+(?:§§?\\s*|[Ss]ec(?:tion|\\.)?\\s+)?(\\d+[a-z]?(?:-\\d+[a-z]?)?)")
    private val CFR = Regex("\\b(\\d{1,2})\\s+C\\.?\\s?F\\.?\\s?R\\.?\\s+(?:§§?\\s*|[Pp]arts?\\s+)?(\\d+)(?:\\.(\\d+[a-z]?))?")
}
