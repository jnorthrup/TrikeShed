package borg.trikeshed.narsese

/**
 * Reading order for a page laid out in columns, from word boxes alone. A gutter is a vertical band
 * no word covers over the page's text height (a ruled line or white space alike leaves no words in
 * it); the page's columns lie between gutters. Lines that cross a gutter (running headers, a
 * full-width heading) cut the page into bands; within a band the columns are read left to right,
 * each top to bottom. A page with no gutter reads as one column. Nothing about any book is assumed:
 * scale comes from the page's own word heights.
 */
object PageColumns {
    class Word(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val text: String) {
        val cx get() = (x0 + x1) / 2
        val h get() = y1 - y0
    }

    /** The gutters of a page: x intervals no word covers across the text body. */
    fun gutters(words: List<Word>): List<ClosedFloatingPointRange<Double>> {
        if (words.size < 20) return emptyList()
        val left = words.minOf { it.x0 }; val right = words.maxOf { it.x1 }
        val bins = (right - left).toInt().coerceAtLeast(1)
        val cover = DoubleArray(bins + 1)
        for (w in words) for (b in ((w.x0 - left).toInt())..((w.x1 - left).toInt()).coerceAtMost(bins)) cover[b] += w.h
        val peak = cover.max()
        val em = words.map { it.h }.sorted()[words.size / 2]
        val out = ArrayList<ClosedFloatingPointRange<Double>>()
        var start = -1
        for (b in 0..bins) {
            // An empty band: covered by less than one line's worth of height over the whole page.
            val empty = cover[b] <= em
            if (empty && start < 0) start = b
            if ((!empty || b == bins) && start >= 0) {
                val end = if (empty) b else b - 1
                // A gutter is interior and wider than a word space: at least one em.
                if (start > 0 && end < bins && end - start + 1 >= em && peak > 0) out.add((left + start)..(left + end + 1))
                start = -1
            }
        }
        return out
    }

    /** The page's text in reading order. */
    fun read(words: List<Word>): String {
        if (words.isEmpty()) return ""
        val gutters = gutters(words)
        val em = words.map { it.h }.sorted()[words.size / 2]
        // Lines: words whose vertical centers lie within half an em, left to right.
        fun lines(ws: List<Word>): List<List<Word>> {
            val out = ArrayList<MutableList<Word>>()
            for (w in ws.sortedBy { (it.y0 + it.y1) / 2 }) {
                val last = out.lastOrNull()
                if (last != null && kotlin.math.abs((last.last().y0 + last.last().y1) / 2 - (w.y0 + w.y1) / 2) <= em / 2) last.add(w)
                else out.add(mutableListOf(w))
            }
            return out.map { it.sortedBy(Word::x0) }
        }
        if (gutters.isEmpty()) return lines(words).joinToString("\n") { l -> l.joinToString(" ") { it.text } }
        fun column(w: Word) = gutters.count { g -> w.cx > g.endInclusive }
        val all = lines(words)
        // A line with a word lying in a gutter spans the page (a running header, a full-width heading) and
        // breaks the flow into bands; otherwise its words belong to the columns either side.
        fun spans(l: List<Word>) = gutters.any { g -> l.any { w -> w.x0 < g.endInclusive && w.x1 > g.start } }
        val out = StringBuilder()
        val band = ArrayList<Word>()
        fun flush() {
            if (band.isEmpty()) return
            for (c in 0..gutters.size) {
                val col = band.filter { column(it) == c }
                if (col.isNotEmpty()) out.append(lines(col).joinToString("\n") { l -> l.joinToString(" ") { it.text } }).append('\n')
            }
            band.clear()
        }
        for (l in all) {
            if (spans(l)) { flush(); out.append(l.joinToString(" ") { it.text }).append('\n') } else band.addAll(l)
        }
        flush()
        return out.toString().trimEnd()
    }

    private val PAGE = Regex("<page\\b[^>]*>([\\s\\S]*?)</page>")
    private val WORD = Regex("<word xMin=\"([\\d.]+)\" yMin=\"([\\d.]+)\" xMax=\"([\\d.]+)\" yMax=\"([\\d.]+)\">(.*?)</word>")

    /** Reads `pdftotext -bbox` output (one `<page>` of `<word>` boxes each) page by page in column order. */
    fun readBbox(html: String): String = PAGE.findAll(html).joinToString("\n\n") { p ->
        read(WORD.findAll(p.groupValues[1]).map { m ->
            Word(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), m.groupValues[3].toDouble(), m.groupValues[4].toDouble(),
                m.groupValues[5].replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'"))
        }.toList())
    }
}
