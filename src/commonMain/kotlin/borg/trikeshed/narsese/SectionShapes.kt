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
                // A contents entry whose number heads nothing anywhere in the text is the contents of text
                // that is not here (a multi-volume work's full table in its first volume): no evidence either
                // way. It is told by its neighbours — a run of the same shape, each heading nothing.
                .filter { i -> t.heads[i] > t.lines[i].length || !contents(t, s, i) }
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

    /** Line [i] of shape [s] sits in a contents run: a line of the same shape within two lines on either side. */
    private fun contents(t: Tuples, s: Int, i: Int): Boolean =
        (maxOf(0, i - 2)..minOf(t.lines.size - 1, i + 2)).any { j -> j != i && t.shapeOf[j] == s }

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
                   val genre: Genre? = null,
                   /** The heading each section opens with, where the text declares its headings ([declared]). */
                   val headings: List<String>? = null,
                   /** Each section's path through the declared headings above it, `Part › 1. Title › 1.1. Head`. */
                   val paths: List<String>? = null) {
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
     * entry) folds into the next; with no believed shape the sections are paragraphs. Headings the text
     * declares come first ([declared]): a shape is judged only where the text states none.
     */
    fun sections(text: String): Sections {
        declared(text)?.let { return it }
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

    /**
     * The sections [text] declares, when it declares at least [MIN_DECLARED] headings past its first line; else null.
     * A declared heading is an ATX line (`#`…`######`, its markup level) or a paragraph led by a `**bold**` run-in head —
     * numbered, or closed by a period or colon — as [MarkupStreams] writes what a page's markup states; a run-in head
     * restating the heading just above it is that heading's text, not another heading. Every heading cuts, and each
     * section keeps its path through the headings above it, nested by numbering before looks:
     *  - a heading continuing an open series (`APPENDIX 3` after `APPENDIX 2`, `1.2.` after `1.1.`) is its sibling,
     *    whatever its markup level;
     *  - one restarting at 1 a series its parent has already run (`1. Coinbase Card` after `9.`, `APPENDIX 4`) opens
     *    under the heading just before it;
     *  - else its markup level places it: a numbered run-in head at 6 plus its number's depth, an unnumbered one a leaf
     *    under the heading before it.
     * A heading with no text of its own lives on in its children's paths.
     */
    fun declared(text: String): Sections? {
        val lines = text.lines()
        class Head(val line: Int, val level: Int, val title: String, val leaf: Boolean) {
            val series: String?; val n: Int
            init {
                val dotted = number.find(title)?.takeIf { it.range.first == 0 }?.value?.split('.')
                val labelled = if (dotted == null) LABEL.find(title) else null
                series = dotted?.let { "#" + it.dropLast(1).joinToString(".") } ?: labelled?.groupValues?.get(1)?.uppercase()
                n = dotted?.last()?.toIntOrNull() ?: labelled?.groupValues?.get(2)?.let(::ordinal) ?: 0
            }
        }
        fun same(a: String, b: String) = a.trimEnd(' ', '.', ':').lowercase() == b.trimEnd(' ', '.', ':').lowercase()
        val heads = ArrayList<Head>()
        for ((i, l) in lines.withIndex()) {
            val atx = ATX.find(l)
            if (atx != null) { heads.add(Head(i, atx.groupValues[1].length, atx.groupValues[2].trim(), false)); continue }
            val lead = RUN_IN.find(l) ?: continue
            val title = lead.groupValues[1].trim()
            if (title.none { it.isLetterOrDigit() }) continue
            val prior = heads.lastOrNull()
            if (prior != null && ATX.matches(lines[prior.line]) && same(prior.title, title)) continue
            val marked = number.find(title)?.takeIf { it.range.first == 0 }
            val closed = title.endsWith('.') || title.endsWith(':') || lead.groupValues[2].isNotEmpty()
            if (marked == null && !(closed && title.split(' ').size <= RUN_IN_WORDS)) continue
            heads.add(Head(i, if (marked != null) 6 + marked.value.split('.').size else 0, title + lead.groupValues[2], marked == null))
        }
        if (heads.count { it.line > 0 } < MIN_DECLARED) return null
        val texts = ArrayList<String>(); val ords = ArrayList<LongArray?>(); val titles = ArrayList<String>(); val paths = ArrayList<String>()
        if (heads[0].line > 0) clean((0 until heads[0].line).joinToString("\n") { lines[it] }.replace("**", "")).takeIf { it.isNotEmpty() }
            ?.let { texts.add(it); ords.add(null); titles.add(Book.heading(it)); paths.add("") }
        val open = ArrayList<Head>()
        // Per parent (null: the text itself), the last number each series of its children reached.
        val ran = HashMap<Head?, HashMap<String, Int>>()
        for ((k, h) in heads.withIndex()) {
            if (!h.leaf) {
                val sibling = if (h.series != null) open.indexOfLast { it.series == h.series && it.n == h.n - 1 } else -1
                if (sibling >= 0) while (open.size > sibling) open.removeAt(open.size - 1)
                else {
                    val before = ArrayList(open)
                    while (open.isNotEmpty() && open.last().level >= h.level) open.removeAt(open.size - 1)
                    val restarts = h.series != null && h.n == 1 && (ran[open.lastOrNull()]?.get(h.series) ?: 0) > 1
                    if (restarts && before.size > open.size) { open.clear(); open.addAll(before) }
                }
                h.series?.let { ran.getOrPut(open.lastOrNull()) { HashMap() }[it] = h.n }
            }
            val path = (open.map { it.title } + h.title).joinToString(" › ")
            if (!h.leaf) open.add(h)
            val from = h.line; val until = if (k + 1 < heads.size) heads[k + 1].line else lines.size
            val body = clean((from + 1 until until).joinToString("\n") { lines[it] }.replace("**", ""))
            val section = if (ATX.matches(lines[from])) (if (body.isEmpty()) "" else h.title + "\n\n" + body)
                else clean(lines[from].replace("**", "") + "\n" + body)
            if (section.isEmpty()) continue
            texts.add(section); titles.add(h.title); paths.add(path)
            ords.add(number.find(h.title)?.takeIf { it.range.first == 0 }?.value?.split('.')?.map { it.toLong() }?.toLongArray())
        }
        return Sections(emptyList(), texts, ords, genre(texts, texts.size), titles, paths)
    }

    private val ATX = Regex("^(#{1,6})[ \\t]+(\\S.*?)[ \\t]*#*[ \\t]*$")
    private val RUN_IN = Regex("^\\*\\*(.+?)\\*\\*([:.]?)")
    /** A division label opening a heading: a word and its number, roman numeral or letter (`APPENDIX 4`, `Part IV`, `Exhibit A`). */
    private val LABEL = Regex("^([A-Za-z]+)\\s+(\\d+|[IVXLCDM]+|[A-Z])(?=$|[\\s.:,)\\-–—])")
    /** A label's number: digits, a letter's place in the alphabet (`Exhibit C`), or an uppercase roman numeral (`Part IV`, `Part X`). */
    private fun ordinal(s: String): Int = s.toIntOrNull() ?: if (s.length == 1 && s[0] !in "IVX") s[0] - 'A' + 1 else FrontMatter.roman(s)
    /** Declared headings past a text's first line below which its structure is judged by shape instead. */
    const val MIN_DECLARED = 3
    /** Longest unnumbered bold lead read as a run-in head, in words. */
    const val RUN_IN_WORDS = 12

    fun clean(s: String) = s.replace(Regex("(\\w)-\n(\\w)"), "$1$2").replace(Regex("\\s+"), " ").trim()
}
