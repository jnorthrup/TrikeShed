package borg.trikeshed.narsese

/**
 * Tuplers for what a sentence parser cannot register: it reads one sentence at a time, so the
 * features that live across lines and sections — a line's typographic shape, the number a shape
 * carries and its order, how much text a shape heads, prose that points at another numbered line —
 * never reach it. Each tupler only describes; every decision is NAL evidence over the tuples.
 *
 * Tuples, one row per line, packed columns:
 *  - shape    the line's leading tokens abstracted: digits → 9, roman → R, letter runs → A/a
 *  - ordinal  the number the shape carries, as a dotted tuple, if any
 *  - heads    characters between this line and the next line of the same shape
 * and, per section, `cites` — an inline sign + number of the believed shape inside prose.
 *
 * Judgements per shape, each occurrence one source: *counts* (its ordinal follows the previous one)
 * and *heads* (it heads more text than it holds). Their NAL intersection is the belief that the
 * shape opens sections, chosen by expectation > ½ — the rule NARS uses for every choice. Recurrence
 * sets the confidence, so nothing about any one document is assumed. With no believed shape the
 * sections are paragraphs.
 */
object SectionShapes {
    /** The layout tuples of one text: per line its shape ordinal (-1 none), carried ordinal, and chars headed. */
    class Tuples(val lines: List<String>, val shapeOf: IntArray, val shapes: List<String>, val ordinals: Array<LongArray?>, val heads: IntArray)

    class Shape(val key: String, val lines: IntArray, val counts: TruthCoord, val heads: TruthCoord, val names: TruthCoord,
                /** The literal characters before the number in its occurrences (`§`, `CHAPTER`), empty when the number leads. */
                val sign: String = "",
                /** The headings: per number the occurrence heading the most text, those on the ascending chain, in line order. */
                val starts: IntArray = IntArray(0)) {
        /** NAL intersection of the three judgements. */
        val belief: TruthCoord get() = TruthCoord(counts.frequency * heads.frequency * names.frequency,
            counts.confidence * heads.confidence * names.confidence)
        /** How much structure the shape explains: its headings, weighed by the belief that the shape heads. */
        val weight: Double get() = starts.size * belief.expectation().toDouble()
    }

    private val roman = Regex("^[IVXLCDM]+\\b")
    private val number = Regex("\\d+(?:\\.\\d+)*")

    /**
     * The leading shape of a line: its sign, the number it carries and the mark that closes it,
     * spacing ignored (OCR spaces `§ 9` and `§9` alike) and a dotted number one token, e.g.
     * `§ 1.01. Plenary` → `§9.`, `CHAPTER 2 — …` → `A9`. A line whose opening carries no number has no shape.
     */
    fun shapeOf(line: String): String? {
        val head = line.trimStart().take(24)
        val out = StringBuilder()
        var i = 0; var numbered = false
        while (i < head.length && out.length < 6 && !numbered) {
            val c = head[i]
            val r = if (out.isNotEmpty() && !head[i - 1].isLetter()) roman.find(head.substring(i)) else null
            when {
                c.isDigit() -> {
                    while (i < head.length && (head[i].isDigit() || head[i] == '.' && i + 1 < head.length && head[i + 1].isDigit())) i++
                    out.append('9'); numbered = true
                    // The mark closing the number is part of the shape: `§ 9.` (a heading) is not `§9 PAGE` (a header).
                    if (i < head.length && !head[i].isLetterOrDigit() && !head[i].isWhitespace()) out.append(head[i])
                }
                r != null -> { i += r.value.length; out.append('R'); numbered = true }
                c.isLetter() -> {
                    val upper = c.isUpperCase()
                    while (i < head.length && head[i].isLetter()) i++
                    out.append(if (upper) 'A' else 'a')
                }
                else -> { if (!c.isWhitespace()) out.append(c); i++ }
            }
        }
        return out.toString().takeIf { numbered }
    }

    /** The title an occurrence names: the letters after its leading number, lower-cased. */
    private fun titleOf(line: String): String {
        val t = line.trimStart()
        val after = number.find(t)?.range?.last?.plus(1) ?: return ""
        return t.substring(after).filter { it.isLetter() || it == ' ' }.trim().lowercase()
    }

    /** The layout tupler. */
    fun tuples(text: String): Tuples {
        val lines = text.lines()
        val ids = HashMap<String, Int>(); val names = ArrayList<String>()
        val shapeOf = IntArray(lines.size) { i -> shapeOf(lines[i])?.let { s -> ids.getOrPut(s) { names.size.also { names.add(s) } } } ?: -1 }
        val ordinals = Array(lines.size) { i ->
            if (shapeOf[i] < 0) null else number.find(lines[i].trimStart().take(16))?.value?.split('.')?.map { it.toLong() }?.toLongArray()
        }
        val heads = IntArray(lines.size)
        val nextOf = IntArray(names.size) { lines.size }
        for (i in lines.indices.reversed()) {
            val s = shapeOf[i]; if (s < 0) continue
            heads[i] = (i + 1 until nextOf[s]).sumOf { lines[it].length }
            nextOf[s] = i
        }
        return Tuples(lines, shapeOf, names, ordinals, heads)
    }

    /** Per-shape judgements over [t], strongest belief first. */
    fun shapes(t: Tuples): List<Shape> {
        val at = Array(t.shapes.size) { ArrayList<Int>() }
        for (i in t.lines.indices) if (t.shapeOf[i] >= 0) at[t.shapeOf[i]].add(i)
        return t.shapes.indices.map { s ->
            // One number has one heading: of its occurrences, the one heading the most text. The others
            // restate it (a contents entry, a running page header) and are no evidence either way.
            val byNumber = LinkedHashMap<String, Int>()
            val unnumbered = ArrayList<Int>()
            for (i in at[s]) {
                val key = t.ordinals[i]?.joinToString(".") ?: run { unnumbered.add(i); null } ?: continue
                val prior = byNumber[key]
                if (prior == null || t.heads[i] > t.heads[prior]) byNumber[key] = i
            }
            val reps = (byNumber.values + unnumbered).sorted()
            var hp = 0L; var hn = 0L; var np = 0L; var nn = 0L
            // A heading names something; a line that only repeats a title named before does not.
            val seen = HashSet<String>()
            for (i in reps) {
                if (t.heads[i] > t.lines[i].length) hp++ else hn++
                val title = titleOf(t.lines[i])
                if (title.isEmpty() || !seen.add(title)) nn++ else np++
            }
            // counts: headings on the longest ascending chain of numbers are in order; the rest are not.
            val chain = ascendingChain(reps.filter { t.ordinals[it] != null }) { t.ordinals[it]!! }
            val cp = chain.size.toLong(); val cn = reps.size - cp
            Shape(t.shapes[s], at[s].toIntArray(), Nal.truthOf(EvidenceCoord(cp * Nal.UNIT, cn * Nal.UNIT)),
                Nal.truthOf(EvidenceCoord(hp * Nal.UNIT, hn * Nal.UNIT)), Nal.truthOf(EvidenceCoord(np * Nal.UNIT, nn * Nal.UNIT)),
                at[s].firstOrNull()?.let { i -> t.lines[i].trimStart().let { l -> l.substring(0, number.find(l)?.range?.first ?: 0).trim() } }.orEmpty(),
                chain.filter { t.heads[it] > t.lines[it].length }.toIntArray())
        }.sortedByDescending { it.weight }
    }

    /** The longest strictly ascending subsequence of [items] by [key] (patience sorting), in order. */
    private fun ascendingChain(items: List<Int>, key: (Int) -> LongArray): List<Int> {
        val tails = ArrayList<Int>(); val back = IntArray(items.size) { -1 }; val tailAt = ArrayList<Int>()
        for ((k, item) in items.withIndex()) {
            val o = key(item)
            var lo = 0; var hi = tails.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (compare(key(tails[mid]), o) < 0) lo = mid + 1 else hi = mid }
            if (lo > 0) back[k] = tailAt[lo - 1]
            if (lo == tails.size) { tails.add(item); tailAt.add(k) } else { tails[lo] = item; tailAt[lo] = k }
        }
        val out = ArrayList<Int>(); var k = tailAt.lastOrNull() ?: -1
        while (k >= 0) { out.add(items[k]); k = back[k] }
        return out.asReversed()
    }

    private fun compare(a: LongArray, b: LongArray): Int {
        for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return a.size.compareTo(b.size)
    }

    /** A book's sections, each one's carried ordinal, and the believed shapes that cut them (empty: paragraphs). */
    class Sections(val believed: List<Shape>, val texts: List<String>, val ordinals: List<LongArray?>,
                   val genre: Genre? = null) {
        val shape: Shape? get() = believed.firstOrNull()
    }

    /**
     * What kind of book a text is, judged from its own layout as NAL evidence — never from a title or
     * a filename. A reference work (dictionary, encyclopedia, digest, code) is a long run of short,
     * independent entries keyed by a term or number in order, each opening with that key and defining
     * or stating it; a treatise is fewer, longer sections of argued prose that lean on each other.
     *
     * Judgements, each over the book's entries (one source per entry):
     *  - keyed:     the entry opens with its own key (headword or numbered heading), in order
     *  - short:     the entry is a unit of lookup: under four times the book's median entry, under 4000 chars
     *  - defining:  its key is followed at once by a gloss, a copula or a register (`X. Lat. To fall`)
     *  - glossed:   it carries source abbreviations or citations (`Co. Litt. 72`, `Escriche, Dic.`)
     *  - crossref:  it points at another entry (`q. v.`, `See X`, `§ n`)
     * Reference-ness is the intersection of keyed, short and defining; glossed and crossref corroborate.
     */
    class Genre(val kind: Kind, val entries: Int, val keyed: TruthCoord, val short: TruthCoord, val defining: TruthCoord,
                val glossed: TruthCoord, val crossref: TruthCoord, val belief: TruthCoord) {
        enum class Kind { REFERENCE, TREATISE, PROSE }
        val reference: Boolean get() = kind == Kind.REFERENCE
    }

    /** Longest entry that still reads as a unit of lookup. */
    const val SHORT_ENTRY = 1500

    private val CROSSREF = Regex("\\bq\\.\\s?v\\.|\\bSee\\s+[A-Z]|\\bvid\\.|\\bcf\\.|§\\s?\\d")
    private val GLOSS = Regex("\\b(?:[A-Z][a-z]{0,6}\\.\\s?){1,3}\\d{1,4}|\\b(?:Lat|Fr|Sax|Span)\\.")

    /**
     * The genre of a cut text: reference work, treatise, or plain prose. Core evidence is keyed ∧ short
     * (a reference entry is a unit of lookup, a treatise section is an argument); being keyed by terms in
     * alphabetical order rather than numbers, glosses and cross-references corroborate.
     */
    fun genre(texts: List<String>, keyedCount: Int, termKeyed: Boolean = false): Genre {
        val entries = texts.size
        fun tv(p: Long, n: Long) = Nal.truthOf(EvidenceCoord(p * Nal.UNIT, n * Nal.UNIT))
        if (entries < 2) { val z = tv(0, 1); return Genre(Genre.Kind.PROSE, entries, z, z, z, z, z, z) }
        val lengths = texts.map { it.length }.sorted()
        val median = lengths[lengths.size / 2].coerceAtLeast(1)
        var sp = 0L; var sn = 0L; var dp = 0L; var dn = 0L; var gp = 0L; var gn = 0L; var xp = 0L; var xn = 0L
        for (e in texts) {
            // Short: a unit of lookup, not an argued section.
            if (e.length <= SHORT_ENTRY) sp++ else sn++
            // Defining: past any sign and number, the key's first sentence says what the key is — a gloss,
            // a copula, a register — rather than opening an argument that runs on.
            val head = e.take(240).replaceFirst(Regex("^\\W*\\d+(?:\\.\\d+)*\\.?\\s*"), "")
            val key = head.takeWhile { it != '.' && it != ',' }.trim()
            val after = head.drop(key.length + 1).trimStart()
            val firstSentence = after.takeWhile { it != '.' }
            if (key.isNotEmpty() && key.length <= 60 && firstSentence.length in 1..120 && (after.startsWith("is ") || after.startsWith("means ") ||
                    after.startsWith("In ") || after.startsWith("Lat") || after.startsWith("Fr") || after.startsWith("A ") || after.startsWith("The "))) dp++ else dn++
            if (GLOSS.containsMatchIn(e)) gp++ else gn++
            if (CROSSREF.containsMatchIn(e)) xp++ else xn++
        }
        val keyed = tv(keyedCount.toLong(), (entries - keyedCount).coerceAtLeast(0).toLong())
        val short = tv(sp, sn); val defining = tv(dp, dn); val glossed = tv(gp, gn); val crossref = tv(xp, xn)
        val coreF = keyed.frequency * short.frequency
        val coreC = keyed.confidence * short.confidence
        // Keyed by terms in alphabetical order is the dictionary's own mark; defining openings, glosses
        // and cross-references each corroborate.
        val lift = ((if (termKeyed) 1f else 0f) + defining.frequency + glossed.frequency + crossref.frequency) / 4f
        val belief = TruthCoord((coreF + (1 - coreF) * coreF * lift).coerceIn(0f, 1f), coreC)
        val kind = when {
            belief.expectation() > .5f -> Genre.Kind.REFERENCE
            keyed.expectation() > .5f -> Genre.Kind.TREATISE
            else -> Genre.Kind.PROSE
        }
        return Genre(kind, entries, keyed, short, defining, glossed, crossref, belief)
    }

    /**
     * Sections of [text] at the believed heading shape. An occurrence that heads nothing (a contents
     * entry) folds into the next; with no believed shape the sections are paragraphs.
     */
    fun sections(text: String): Sections {
        val t = tuples(text)
        // The strongest believed shape cuts; the others stay as candidates the caller can read.
        val believed = shapes(t).filter { it.belief.expectation() > .5f }
        val numbered = believed.firstOrNull()
        // A shape that cuts a handful of sections from a long text is not the text's structure: the
        // other kind of book keys its entries by a word, not a number (a dictionary's headwords).
        val headwords = headwords(t)
        val byHeadword = !(numbered != null && numbered.starts.size >= headwords.size / 8) && headwords.size >= MIN_ENTRIES
        val starts = when {
            !byHeadword && numbered != null -> numbered.starts.toList()
            byHeadword -> headwords
            else -> return text.split(Regex("\n\\s*\n")).map(::clean).filter { it.isNotEmpty() }.let { Sections(emptyList(), it, it.map { null }, genre(it, 0)) }
        }
        val offsets = IntArray(t.lines.size + 1)
        for (i in t.lines.indices) offsets[i + 1] = offsets[i] + t.lines[i].length + 1
        val texts = ArrayList<String>(); val ords = ArrayList<LongArray?>()
        if (starts.isNotEmpty()) clean(text.substring(0, offsets[starts[0]])).takeIf { it.isNotEmpty() }?.let { texts.add(it); ords.add(null) }
        for ((k, at) in starts.withIndex()) {
            val body = clean(text.substring(offsets[at], minOf(text.length, if (k + 1 < starts.size) offsets[starts[k + 1]] else text.length)))
            if (body.isNotEmpty()) { texts.add(body); ords.add(t.ordinals[at]) }
        }
        // Cut by headwords, no numbered shape describes the book: nothing to cite by sign + number.
        return Sections(if (byHeadword) emptyList() else believed, texts, ords, genre(texts, starts.size, byHeadword))
    }

    /**
     * The citation tupler: inside a section's prose, the believed shape's sign followed by a number
     * points at the section carrying that ordinal. Returns (from, to) section pairs. A shape whose
     * number leads (no sign) cannot be told from other numbers in prose, so it cites nothing.
     */
    fun cites(s: Sections): List<Pair<Int, Int>> {
        // A word sign (`CHAPTER`) reads as ordinary prose; only a symbol marks a citation.
        val signs = listOfNotNull(s.shape?.sign).filter { it.isNotEmpty() && it.none(Char::isLetterOrDigit) }
        if (signs.isEmpty()) return emptyList()
        val index = HashMap<String, Int>()
        for ((k, o) in s.ordinals.withIndex()) if (o != null) index.getOrPut(o.joinToString(".")) { k }
        val ref = Regex("(?:" + signs.joinToString("|") { Regex.escape(it) } + ")\\s*(\\d+(?:\\.\\d+)*)")
        val out = ArrayList<Pair<Int, Int>>()
        for ((from, body) in s.texts.withIndex()) for (m in ref.findAll(body)) {
            val to = index[m.groupValues[1]] ?: continue
            if (to != from) out.add(from to to)
        }
        return out.distinct()
    }

    /**
     * The headword tupler: a line that opens with an all-capital word run ended by a period
     * (`ACCIDERE.`, `ACTION.`) is a headword when the headwords it keeps come in alphabetical order —
     * the ordering a dictionary's entries carry instead of numbers. Judged the same way as numbered
     * shapes: the longest ascending chain of headwords is the book's entries; strays are not.
     */
    fun headwords(t: Tuples): List<Int> {
        val cand = ArrayList<Int>(); val keys = ArrayList<String>()
        for ((i, l) in t.lines.withIndex()) {
            val m = HEADWORD.find(l.trimStart()) ?: continue
            val w = m.groupValues[1]
            if (w.count(Char::isLetter) < 2) continue
            cand.add(i); keys.add(w.filter(Char::isLetter))
        }
        if (cand.isEmpty()) return emptyList()
        // Longest ascending (non-decreasing, one entry may continue a prior one) chain by headword.
        val tails = ArrayList<Int>(); val back = IntArray(cand.size) { -1 }; val tailAt = ArrayList<Int>()
        for (k in cand.indices) {
            var lo = 0; var hi = tails.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (keys[tails[mid]] <= keys[k]) lo = mid + 1 else hi = mid }
            if (lo > 0) back[k] = tailAt[lo - 1]
            if (lo == tails.size) { tails.add(k); tailAt.add(k) } else { tails[lo] = k; tailAt[lo] = k }
        }
        val out = ArrayList<Int>(); var k = tailAt.lastOrNull() ?: -1
        while (k >= 0) { out.add(cand[k]); k = back[k] }
        return out.asReversed()
    }

    private val HEADWORD = Regex("^([A-Z][A-Z'\\- ]{1,40}[A-Z])[.,]")
    const val MIN_ENTRIES = 200

    fun clean(s: String) = s.replace(Regex("(\\w)-\n(\\w)"), "$1$2").replace(Regex("\\s+"), " ").trim()
}
