package borg.trikeshed.narsese

/**
 * A printed page read as separate streams from its word boxes (Tesseract TSV), never from a template:
 * the running head (first line, set apart from the body, a number at one end — recto and verso put
 * folio and section at opposite ends), the body, and the notes — the lines below the widest vertical
 * gap past which the type steps down. Notes are cut at their markers; lines above a page's first
 * marker continue the previous page's last note (the overflow of a long note into the next bottom
 * margin). Pixels that fail on a superscript are filled by sequence: an unread marker takes the ordinal
 * after the one before it. Across a book the heads teach the section sign, and a body line opening
 * with an OCR misreading of that sign (`$`, `§$`, `$8`) followed by the next numbers in sequence is
 * restored to the sign.
 */
object PageStreams {
    class Line(val block: Int, val par: Int, val top: Int, val left: Int, val right: Int, val height: Int, val text: String) {
        val width get() = right - left
    }

    /** One page: its folio and head section as the head printed them, body and note lines in reading order. */
    class Page(val page: Int, val folio: String?, val headSection: String?, val head: String?, val body: List<Line>, val notes: List<Line>) {
        /** The text measure: the body's median line width. */
        val measure: Int get() = (body.ifEmpty { notes }).map { it.width }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
    }

    /**
     * A note fragment: [from] is the page the note began on; [continued] marks the overflow of a prior page's note.
     * [stream] is the measure the note is set to — a full-measure series and a column-set series (an author's
     * notes and an editor's) run side by side, and an overflow continues the open note of its own measure.
     */
    class Note(val page: Int, val folio: String?, val sections: String, val from: Int, val marker: String, val ordinal: Int, val continued: Boolean, val stream: Int, val text: String)

    class Book(val body: String, val notes: List<Note>, val sign: String?)

    /** The lines of a TSV page, in Tesseract's reading order; a line's height is its words' median. */
    fun lines(tsv: CharSequence): List<Line> {
        val out = ArrayList<Line>()
        var key = ""; var block = 0; var par = 0; var top = 0; var left = 0; var right = 0
        val words = StringBuilder(); val hs = ArrayList<Int>()
        fun flush() {
            if (words.isNotEmpty()) out.add(Line(block, par, top, left, right, hs.sorted()[hs.size / 2], words.toString()))
            words.setLength(0); hs.clear()
        }
        for (row in tsv.lineSequence()) {
            val c = row.split('\t')
            if (c.size < 12 || c[0] != "5") continue
            val text = c[11].trim()
            if (text.isEmpty()) continue
            val k = c[2] + "." + c[3] + "." + c[4]
            if (k != key) {
                flush(); key = k
                block = c[2].toInt(); par = c[3].toInt(); top = c[7].toInt(); left = c[6].toInt(); right = left + c[8].toInt()
            } else { top = minOf(top, c[7].toInt()); left = minOf(left, c[6].toInt()); right = maxOf(right, c[6].toInt() + c[8].toInt()) }
            if (words.isNotEmpty()) words.append(' ')
            words.append(text); hs.add(c[9].toInt())
        }
        flush()
        return out
    }

    private val sectionMark = Regex("^[^\\p{L}\\p{N}\\s]{1,3}$")
    /** A sign as a head prints it, or as OCR misreads it there (`§` → `8`, `S`, `$`). */
    private val headMark = Regex("^(?:[^\\p{L}\\p{N}\\s]{1,3}|[8S]{1,2})$")
    private fun median(xs: List<Int>) = if (xs.isEmpty()) 0 else xs.sorted()[xs.size / 2]

    fun page(page: Int, tsv: CharSequence): Page {
        val all = lines(tsv)
        if (all.isEmpty()) return Page(page, null, null, null, emptyList(), emptyList())
        val byTop = all.sortedBy { it.top }
        val gaps = (1 until byTop.size).map { byTop[it].top - (byTop[it - 1].top + byTop[it - 1].height) }
        val leading = median(gaps.filter { it > 0 }).coerceAtLeast(1)
        // The head: the topmost line, set apart by more than the leading, a number at one end.
        val first = byTop[0]
        val toks = first.text.split(' ').filter { it.isNotEmpty() }
        // A folio read with a speck in it (`33-4`) is still a number.
        fun num(t: String) = t.filter(Char::isDigit).takeIf { it.isNotEmpty() && t.length - it.length <= 1 && (it.length >= 2 || t.length == 1) }
        val numberedEnd = num(toks.first()) != null || num(toks.last()) != null
        // Set apart by more than the leading, or set in capitals: a running head, recto or verso.
        val caps = first.text.count(Char::isUpperCase) * 2 > first.text.count(Char::isLetter)
        val isHead = byTop.size > 1 && numberedEnd && (gaps[0] * 2 > 3 * leading || caps)
        var folio: String? = null; var section: String? = null
        if (isHead) {
            for ((i, t) in toks.withIndex()) num(t)?.let { n ->
                if (i > 0 && headMark.matches(toks[i - 1])) section = n
                else if (i == 0 || i == toks.lastIndex) folio = n
            }
        }
        val rest = if (isHead) all.filter { it !== first } else all
        // The notes band: below the widest gap past which the type steps down (median height < 85% above it).
        val sorted = rest.sortedBy { it.top }
        var cut = -1; var widest = 2 * leading
        for (i in 1 until sorted.size) {
            val gap = sorted[i].top - (sorted[i - 1].top + sorted[i - 1].height)
            if (gap <= widest) continue
            val above = median(sorted.subList(0, i).map { it.height }); val below = median(sorted.subList(i, sorted.size).map { it.height })
            if (below * 100 < above * 85) { cut = sorted[i].top; widest = gap }
        }
        val notes = if (cut < 0) emptyList() else rest.filter { it.top >= cut }
        return Page(page, folio, section, if (isHead) first.text else null, rest.filter { cut < 0 || it.top < cut }, notes)
    }

    /** A note opening: `(a)`, `1`, `12.`, or a marker glyph the scan read in place of a superscript. */
    private val opening = Regex("^(\\(([a-z]{1,2})\\)|(\\d{1,3})[.)]?|([*†‡¹²³⁴⁵⁶⁷⁸⁹°’'?]))\\s+(.*)$")

    /** Body heading misread: one to three sign-like glyphs, a number, a closing mark. */
    private val misread = Regex("^([§\$S8]{1,3})\\s*(\\d{1,5})(?=[.,-])")

    fun book(pages: List<Page>): Book {
        // The sign the heads print before a section number, when they print one.
        val sign = pages.mapNotNull { p -> p.head?.split(' ')?.let { t -> t.indices.firstOrNull { i -> i + 1 < t.size && sectionMark.matches(t[i]) && t[i + 1].all(Char::isDigit) }?.let { t[it] } } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.key
        val body = StringBuilder(); val notes = ArrayList<Note>()
        var last = 0
        val open = HashMap<Int, Note>()
        // Marker sequences run on across pages; a note series restarting at 1 is always believed.
        var digit = 0; var letter = 0
        for (p in pages) {
            val first = last
            var par = -1; var block = -1
            for (l in p.body) {
                var text = l.text
                if (sign != null) misread.find(text)?.let { m ->
                    val n = m.groupValues[2].toInt()
                    // The printed sign is believed as read; a misreading only when its number is next in sequence.
                    if (m.groupValues[1] == sign || n in (last + 1)..(last + 25)) {
                        last = n
                        if (m.groupValues[1] != sign) text = sign + " " + text.substring(m.groupValues[0].lastIndexOf(m.groupValues[2]) + m.range.first)
                    }
                }
                body.append(if (body.isEmpty()) "" else if (l.block != block || l.par != par) "\n\n" else "\n").append(text)
                block = l.block; par = l.par
            }
            if (body.isNotEmpty()) body.append('\n')
            p.headSection?.toIntOrNull()?.let { if (it > last) last = it }
            val sections = listOfNotNull(p.headSection, if (last > first) last.toString() else null).distinct().joinToString("-")
            var ordinal = 0
            var cur: StringBuilder? = null; var mark = ""; var from = p.page; var cont = false; var wide = 0
            val measure = p.measure
            // A fragment's measure is its widest line: full measure (0) spans most of the body's width, else a column's (1).
            // A one-line note decides nothing, so it keeps the measure of the fragment before it.
            var lastStream = 0
            fun close() {
                val c = cur ?: return
                val stream = if (measure <= 0) 0 else if (wide * 10 >= measure * 7) 0 else if (wide * 10 >= measure * 4) 1 else lastStream
                if (cont) open[stream]?.let { o -> mark = o.marker; ordinal = o.ordinal; from = o.from }
                notes.add(Note(p.page, p.folio, sections, from, mark, ordinal, cont, stream, c.toString()).also { n -> open[stream] = n })
                lastStream = stream
                cur = null
            }
            // A marker is believed only in sequence: a number is the next after the last (or 1, a page's
            // numbering restarting); a letter likewise. Otherwise the line opens with a citation's volume.
            for (l in p.notes) {
                val m = opening.find(l.text)
                val ltr = m?.groupValues?.get(2).orEmpty(); val dig = m?.groupValues?.get(3).orEmpty()
                val lv = ltr.fold(0) { a, c -> a * 26 + (c - 'a' + 1) }
                val dv = dig.toIntOrNull() ?: -1
                val inSequence = m != null && when {
                    // A superscript merged with a speck reads with an extra leading digit (`12` for `2`).
                    dig.isNotEmpty() -> dv == 1 || dv in (digit + 1)..(digit + 2) || dv in 10..99 && dv % 10 == digit + 1
                    ltr.isNotEmpty() -> lv == 1 || lv in (letter + 1)..(letter + 2)
                    else -> true
                }
                if (m != null && inSequence) {
                    close()
                    ordinal = when {
                        dig.isNotEmpty() -> (if (dv in (digit + 1)..(digit + 2) || dv == 1) dv else dv % 10).also { digit = it }
                        ltr.isNotEmpty() -> lv.also { letter = it }
                        else -> (digit + 1).also { digit = it }   // the pixels failed the superscript: the next in sequence
                    }
                    mark = m.groupValues[1]; from = p.page; cont = false; wide = l.width
                    cur = StringBuilder(m.groupValues[5])
                } else if (cur == null) {
                    // Above the page's first marker: a prior page's open note of this measure runs on.
                    mark = ""; ordinal = 0; from = p.page; cont = true; wide = l.width
                    cur = StringBuilder(l.text)
                } else { cur!!.append(' ').append(l.text); wide = maxOf(wide, l.width) }
            }
            close()
        }
        return Book(body.toString(), notes, sign)
    }

    /** The notes as TSV rows, one fragment a row: page, folio, sections, from, marker, ordinal, continued, text. */
    fun notesTsv(notes: List<Note>): String = buildString {
        append("page\tfolio\tsections\tfrom\tmarker\tordinal\tcontinued\tstream\ttext\n")
        for (n in notes) append(n.page).append('\t').append(n.folio.orEmpty()).append('\t').append(n.sections).append('\t')
            .append(n.from).append('\t').append(n.marker).append('\t').append(n.ordinal).append('\t').append(if (n.continued) 1 else 0)
            .append('\t').append(n.stream).append('\t').append(n.text.replace('\t', ' ')).append('\n')
    }
}
