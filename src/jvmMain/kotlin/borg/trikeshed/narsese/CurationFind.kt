package borg.trikeshed.narsese

import borg.trikeshed.lcnc.*
import borg.trikeshed.parse.*
import kotlin.math.*

/**
 * The curator's search over the curated leaves, n deep: book names (depth 1), section headings and texts (2), the
 * sentences of the sections' readings and the statements read from them (3), and their concepts by lemma and SUMO
 * class (4). A leaf is found when every query word (lowercase, at least [WORD] characters) begins one of its words; its
 * score sums, over those words, the share of its words each begins (tf) times the word's idf over the leaves read, so
 * the narrowest leaf stating the query stands first. Readings open best section first, at most [READINGS] of them and
 * until [CANDIDATES] leaves are found in them; nothing is parsed. Each row carries the ring key the curator page goes
 * to: a book's first section's, a leaf's section's, a concept's own where the ring holds it.
 *
 * Jev orders what was found ([rank]) once [probe] has put it a state of known answer and had both answers back as
 * known; [gate] holds that verdict.
 */
class CurationFind(val nodes: ConstellationNodes) {
    /** The startup question's verdict: `ready`, `reason`, the `yes` and `no` nouls, `ms` and `model`. */
    @Volatile var gate: Map<String, Any?> = mapOf("ready" to false, "reason" to "probing")

    /**
     * A leaf read: its kind, book, section ordinal (-1 for a book), heading, text, the ring key of its section and of its
     * concept, per query word how many of its words that word begins (its word count last), and where the first begins.
     */
    class Leaf(val kind: String, val book: String, val section: Int, val heading: String?, val text: String,
               val ring: String, val concept: String?, val tf: IntArray, val at: Int)

    /** Jev over [PROBE], one question each way, asked directly: ready when the true one holds and the false one does not. */
    suspend fun probe(key: suspend () -> String?) {
        val t0 = System.currentTimeMillis()
        val verdict: Map<String, Any?> = try {
            val k = key()
            if (k == null) mapOf("ready" to false, "reason" to "JEV_API_KEY is not set") else {
                val reply = Jev.ask(k, mapOf("page" to PROBE), mapOf(
                    "yes" to Jev.noul("Does `page` say the banks are closed on March 8, 1933?"),
                    "no" to Jev.noul("Does `page` say the banks are open on March 7, 1933?")))
                val answers = reply["answers"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
                val yes = Jev.noulOf(answers, "yes"); val no = Jev.noulOf(answers, "no")
                mapOf("ready" to (yes >= ConstellationNodes.HOLDS && no <= 1 - ConstellationNodes.HOLDS),
                    "reason" to "yes ${(yes * 100).toInt()}% · no ${(no * 100).toInt()}%", "yes" to yes, "no" to no, "model" to reply["model"])
            }
        } catch (e: Exception) { mapOf("ready" to false, "reason" to (e.message ?: e.toString())) }
        gate = verdict + ("ms" to System.currentTimeMillis() - t0)
    }

    /**
     * Rows for [q] over [book], else [constellation]'s members, else every constellation's, [depth] deep (1..4): the best
     * [limit] by score, with how many leaves were read and found and how many readings were opened.
     */
    fun find(q: String, constellation: String?, book: String?, depth: Int, limit: Int): Map<String, Any?> {
        val words = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= WORD }.distinct()
        val names = when {
            !book.isNullOrBlank() -> listOf(book)
            !constellation.isNullOrBlank() -> nodes.members(constellation)
            else -> nodes.root.listFiles { f -> f.isFile && f.name.endsWith(".members") }.orEmpty().sortedBy { it.name }
                .flatMap { nodes.members(it.name.removeSuffix(".members")) }.distinct()
        }
        val df = IntArray(words.size); var read = 0
        val found = ArrayList<Leaf>(); val deeper = ArrayList<Leaf>()
        fun leaf(kind: String, b: String, k: Int, heading: String?, text: String, ring: String, concept: String? = null): Leaf {
            val tf = IntArray(words.size + 1)
            val l = Leaf(kind, b, k, heading, text, ring, concept, tf, begins(text, words, tf))
            read++
            for (w in words.indices) if (tf[w] > 0) df[w]++
            if (words.indices.all { tf[it] > 0 }) found.add(l)
            return l
        }
        fun idf() = DoubleArray(words.size) { 1 + ln(read.toDouble() / maxOf(1, df[it])) }
        fun score(l: Leaf, idf: DoubleArray) = words.indices.sumOf { l.tf[it] * idf[it] } / maxOf(1, l.tf[words.size])
        if (words.isNotEmpty()) for (name in names) {
            val b = nodes.load(name)?.takeIf { it.headings.isNotEmpty() } ?: continue
            val keys = nodes.sectionKeys(b.headings)
            fun ring(k: Int) = "${ConstellationNodes.CURATE}/${b.name}/${keys[k]}"
            leaf("book", name, -1, null, name, ring(0))
            if (depth < 2) continue
            val texts = nodes.texts(name)
            for (k in b.headings.indices) {
                val s = leaf("section", name, k, b.headings[k], texts.getOrNull(k) ?: b.headings[k], ring(k))
                // A concept is found by its class as well as its lemma, so at depth 4 a section stating no query word is read too.
                if (depth >= 4 || depth == 3 && s.at >= 0) deeper.add(s)
            }
        }
        val shallow = found.size; var opened = 0
        val pooled = HashSet<String>()
        val first = idf()
        for (s in deeper.sortedByDescending { score(it, first) }) {
            if (opened == READINGS || found.size - shallow >= CANDIDATES) break
            val f = nodes.readingFile(s.book, s.section).takeIf { it.isFile } ?: continue
            opened++
            val sentences = reify(f.readBytes()) as? List<*> ?: continue
            for (x in sentences) {
                val m = x as? Map<*, *> ?: continue
                m["text"]?.let { leaf("sentence", s.book, s.section, s.heading, it.toString(), s.ring) }
                for (st in (m["statements"] as? List<*>).orEmpty()) (st as? Map<*, *>)?.let { t ->
                    leaf("statement", s.book, s.section, s.heading, listOf("bearer", "force", "action", "object", "condition")
                        .mapNotNull { t[it]?.toString() }.joinToString(" · ").replace('_', ' '), s.ring)
                }
                if (depth >= 4) for (c0 in (m["concepts"] as? List<*>).orEmpty()) {
                    val c = c0 as? Map<*, *> ?: continue
                    val lemma = c["lemma"]?.toString() ?: continue
                    // The class the concept pool files it under: the overlay's where the book was crafted, else the lexicon's.
                    val cls = nodes.conceptClass(s.book, lemma) ?: c["class"]?.toString() ?: "Unclassified"
                    val key = "${ConstellationNodes.POOL}/$cls/$lemma"
                    if (pooled.add(key)) leaf("concept", s.book, s.section, s.heading, "$lemma · $cls", s.ring, key)
                }
            }
        }
        val idf = idf()
        val rows = found.map { it to score(it, idf) }.sortedByDescending { it.second }.take(limit).map { (l, weight) ->
            linkedMapOf("kind" to l.kind, "book" to l.book, "section" to l.section.takeIf { it >= 0 }, "heading" to l.heading,
                "text" to snippet(l.text, l.at), "ring" to (l.concept?.takeIf { LcncTrail.live.known(it) } ?: l.ring),
                "score" to (weight * 1000).roundToLong() / 1000.0)
        }
        return linkedMapOf("q" to q, "words" to words, "depth" to depth, "books" to names.size, "read" to read,
            "found" to found.size, "readings" to opened, "rows" to rows)
    }

    /**
     * The first [RANK_ASKED] of [rows] in Jev's order, each with `jev`, its noul that the row bears on [q] (ties keep their
     * order), then the rest as they stood. One request asks them all through the answer store, so the same rows ranked
     * again under the same [q] ask nothing.
     */
    suspend fun rank(key: String, q: String, rows: List<Map<*, *>>): Map<String, Any?> {
        val head = rows.take(RANK_ASKED)
        val ids = head.indices.map { "R" + it.toString().padStart(2, '0') }
        val state = mapOf("query" to q, "rows" to ids.indices.associate { ids[it] to head[it]["text"]?.toString().orEmpty().take(TEXT_CHARS) })
        val reply = nodes.asked(key, state, ids.associateWith { id ->
            Jev.noul("Does `rows.$id` bear on `query`: state, define or apply what it asks?")
        })
        val answers = reply["answers"] as? Map<*, *> ?: return mapOf("error" to (reply["error"] ?: "Jev returned no answers"))
        val ranked: List<Map<*, *>> = head.indices.map { LinkedHashMap<Any?, Any?>(head[it]).apply { put("jev", Jev.noulOf(answers, ids[it])) } }
            .sortedByDescending { it["jev"] as Double }
        return linkedMapOf("q" to q, "rows" to ranked + rows.drop(RANK_ASKED), "ranked" to true, "asked" to reply["asked"],
            "tokens" to ((reply["usage"] as? Map<*, *>)?.get("input_tokens") ?: 0), "model" to reply["model"])
    }

    companion object {
        /** Rows Jev orders per request. */
        const val RANK_ASKED = 24
        /** Leaves found in readings, and readings opened, at most per search. */
        const val CANDIDATES = 3_000
        const val READINGS = 200
        /** The characters of a leaf a row shows and Jev reads. */
        const val TEXT_CHARS = 240
        /** The shortest query word. */
        const val WORD = 3
        /** The startup question's state: closed on the 8th holds, open on the 7th does not. */
        const val PROBE = "The proclamation closes every bank from March 6 to March 9, 1933."

        /**
         * Counts into [tf], per query word of [words], the words of [text] it begins, and into its last slot the words of
         * [text]. A word is a run of letters and digits; a query word begins it at its start or at a hump (a capital after
         * a small letter), so a SUMO class name is found by any of its words. The offset of the first begun, or -1.
         */
        fun begins(text: String, words: List<String>, tf: IntArray): Int {
            var first = -1; var i = 0; val n = text.length
            while (i < n) {
                if (!text[i].isLetterOrDigit()) { i++; continue }
                val start = i
                while (i < n && text[i].isLetterOrDigit()) i++
                tf[words.size]++
                for (w in words.indices) {
                    val len = words[w].length
                    var h = start
                    while (h + len <= i) {
                        if ((h == start || text[h].isUpperCase() && text[h - 1].isLowerCase()) && text.regionMatches(h, words[w], 0, len, ignoreCase = true)) {
                            tf[w]++; if (first < 0) first = h; break
                        }
                        h++
                    }
                }
            }
            return first
        }

        /** At most [TEXT_CHARS] of [text] on one line, from a little before [at] when that lies past the opening. */
        fun snippet(text: String, at: Int): String {
            val from = if (at < TEXT_CHARS / 2) 0 else at - TEXT_CHARS / 4
            return text.substring(from, minOf(text.length, from + TEXT_CHARS)).replace(Regex("\\s+"), " ").trim()
        }
    }
}
