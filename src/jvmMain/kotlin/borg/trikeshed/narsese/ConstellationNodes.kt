package borg.trikeshed.narsese

import borg.trikeshed.parse.reify
import borg.trikeshed.parse.reifyMap
import borg.trikeshed.parse.jsonOf

import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.ontology.SumoClassId
import borg.trikeshed.ontology.SumoCorpus
import borg.trikeshed.lib.packInts
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import borg.trikeshed.lib.toSeries
import borg.trikeshed.rdf.RdfGraph
import borg.trikeshed.rdf.RdfQuad
import borg.trikeshed.rdf.RdfTerm
import borg.trikeshed.rdf.RdfVocab
import borg.trikeshed.rdf.TurtleRdf
import java.io.File

/**
 * `book.curate` and `constellation.join`: a book's text becomes a frozen [Book] (sections, statements,
 * support), and books join a named [Constellation] whose summaries land on the blackboard under
 * `constellation/<name>/…`. Books freeze to `<stateDir>/constellations/books/<book>.json`; a
 * constellation is its member list, `<stateDir>/constellations/<name>.members`.
 */
class ConstellationNodes(stateDir: File, private val blackboard: ConfixBlackboard, private val bank: KifKnowledgeBase? = null,
                         /** The daemon's live rete: myelinated sense rules are admitted into it as well as into [senseRete]. */
                         private val liveRete: CausalityReteElement? = null) {
    private val wiki = File(stateDir, "wiki")
    val root = File(stateDir, "constellations").apply { mkdirs() }
    private val books = File(root, "books").apply { mkdirs() }
    private val live = HashMap<String, Constellation>()

    /**
     * What words denote where: the sense rows Jev's judgments of CoreNLP's nouns fill, kept beside the books. The
     * per-class memory they replace (`senses.bag`) is not read; the rows are primed from the Jev tables themselves.
     */
    private val senseFile = File(root, "senses.rows")
    @Volatile private var mem: SenseMemory? = null
    private val memLock = Any()
    /** Bumped whenever the sense memory changes, so a section read before it is read again. */
    @Volatile private var senseEpoch = 0L

    /**
     * The sense memory as last saved, else primed from every Jev table already answered. Books loaded while it primes
     * are typed without it, so they are loaded again once it stands.
     */
    private fun memory(): SenseMemory = if (!SENSES_ENABLED) SenseMemory() else mem ?: synchronized(memLock) {
        // Loaded or primed, the memory is put through one push-pull pass: the rules always answer to the beliefs as they stand.
        mem ?: (if (senseFile.isFile) senseFile.bufferedReader().useLines { SenseMemory.read(it) } else SenseMemory().also { prime(it); save(it) })
            .also { myelinate(it) }
            .also { mem = it; replant(); senseEpoch++; synchronized(loaded) { loaded.clear() }; live.clear() }
    }
    private val forge = borg.trikeshed.common.Path(stateDir.absolutePath)
    /** Sense beliefs myelinated into eternal rules, as the rules ledger holds them across restarts. */
    private val senseRules = ArrayList(if (SENSES_ENABLED) NarsDurableLedger.readRules(forge).filter { it.provenanceCid == SENSES } else emptyList())
    /** The local tree over SUMO's: the myelinated productions and the sense memory, rebuilt whenever either changes. */
    @Volatile private var tree = ConceptTree(senseRules.toList(), null)

    /** The overlay as of now: rebuilt from [senseRules] and the sense memory. */
    private fun replant() { tree = ConceptTree(senseRules.toList(), mem) }

    fun register(runners: MutableMap<String, LcncNodeRunner>) {
        runners[CURATE] = curate
        runners[JOIN] = join
        runners[RENDER] = render
        runners[WIKI_READ] = wikiRead
        runners[ASK] = ask
        runners[SETTLE] = settle
        runners[SELECT] = select
        runners[BRIEF] = brief
    }

    /**
     * `constellation.brief`: the frontier as an outcome summary for the model-driven wiki passes
     * (`wiki.propose`'s `summary`): conflicts with the sections stating each side, open premises as
     * questions with the norms waiting on them, and restated norms with their truth. Deterministic:
     * the same constellation yields the same text, so its cid identifies the state it describes.
     */
    private val brief = LcncNodeRunner { node, _ ->
        val name = node.params["constellation"]?.takeIf { it.isNotBlank() } ?: error("constellation.brief: name the constellation")
        val c = constellation(name)
        fun sections(id: Int) = c.support[id].toIntArray().take(SECTIONS_PER_NORM)
            .joinToString("; ") { g -> sectionRef(c, g).let { "${it["book"]}: ${it["section"]}" } }
        val conflicts = c.conflicts()
        val open = c.open()
        val restated = c.restated().sortedByDescending { c.support[it].cardinality }
        val text = buildString {
            appendLine("Constellation '$name': ${c.books.size} books (${c.books.joinToString { it.name }}), ${c.statements.size} norms.")
            if (conflicts.isNotEmpty()) {
                appendLine(); appendLine("Conflicts (opposed force on one proposition):")
                for ((x, y) in conflicts.take(SAMPLE)) {
                    appendLine("- ${c.statements[x].sentence} [${sections(x)}]")
                    appendLine("  vs ${c.statements[y].sentence} [${sections(y)}]")
                }
            }
            if (open.isNotEmpty()) {
                appendLine(); appendLine("Open premises (no joined book states them):")
                for ((p, ids) in open.entries.sortedByDescending { it.value.cardinality }.take(SAMPLE))
                    appendLine("- ${question(p)} gates: " + ids.toIntArray().joinToString("; ") { c.statements[it].sentence })
            }
            if (restated.isNotEmpty()) {
                appendLine(); appendLine("Restated norms (stated in more than one section):")
                for (id in restated.take(SAMPLE)) {
                    val t = c.truth(id)
                    appendLine("- ${c.statements[id].sentence} f=${"%.2f".format(t.frequency)} c=${"%.2f".format(t.confidence)} [${sections(id)}]")
                }
            }
        }
        // `page=true` publishes the brief as the wiki pattern page the proposer maps a skill to.
        val page = if (node.params["page"] == "true") "patterns/constellation-$name.md".also { rel ->
            File(wiki, rel).apply { parentFile.mkdirs() }.writeText("# Constellation $name\n\n$text")
        } else null
        mapOf("summary" to text, "cid" to borg.trikeshed.job.ContentId.of(text.encodeToByteArray()).value, "page" to page)
    }

    /** `rdf.select`: a SPARQL basic graph pattern over Turtle; rows of variable → term (Turtle form, literals bare). */
    private val select = LcncNodeRunner { node, inputs ->
        val turtle = ((inputs["turtle"] ?: inputs["turtle?"])?.toString() ?: node.params["turtle"]).orEmpty()
        val sparql = ((inputs["sparql"] ?: inputs["sparql?"])?.toString() ?: node.params["sparql"])?.takeIf { it.isNotBlank() }
            ?: error("rdf.select: no sparql")
        val rows = TurtleRdf.select(TurtleRdf.parse(turtle), sparql).map { r ->
            r.entries.associate { (k, v) -> k.removePrefix("?") to ((v as? RdfTerm.Literal)?.lexical ?: v.toTurtle()) }
        }
        mapOf("rows" to rows, "count" to rows.size)
    }

    /**
     * `constellation.settle`: board items in (`board.get`'s json), `kanban.move` commands out: each
     * premise card of this constellation whose premise a joined book now states as a fact moves to
     * done, at the card's current revision. Idempotent: a done card yields no command.
     */
    private val settle = LcncNodeRunner { node, inputs ->
        val name = node.params["constellation"]?.takeIf { it.isNotBlank() } ?: error("constellation.settle: name the constellation")
        val board = (inputs["board"] ?: inputs["board?"]) as? Map<*, *> ?: error("constellation.settle: wire board.get's json into board")
        val c = constellation(name)
        val prefix = "constellation/$name/premise/"
        val answered = c.premised.keys.filter { it in c.facts }.map { prefix + it.replace(' ', '_') }.toSet()
        val moves = (board["items"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
            .filter { it["id"]?.toString() in answered && it["status"] != "done" && it["status"] != "archived" }
            .map { item ->
                val rev = (item["revision"] as Number).toLong()
                mapOf("jobId" to item["id"].toString(), "toColumn" to "done", "expectedRevision" to rev,
                    "idempotencyKey" to "${item["id"]}#answered#$rev")
            }
        mapOf("moves" to moves, "count" to moves.size)
    }

    /**
     * `constellation.ask`: the norms a SUMO class holds in a constellation: stated of it, or stated of
     * an ancestor and deduced down is-a, revised with its own evidence. Tells `(normHeld Class
     * "predication" Via "f" "c")` per held norm.
     */
    private val ask = LcncNodeRunner { node, inputs ->
        val name = node.params["constellation"]?.takeIf { it.isNotBlank() } ?: error("constellation.ask: name the constellation")
        val cls = ((inputs["class"] ?: inputs["class?"])?.toString() ?: node.params["class"])?.takeIf { it.isNotBlank() }
            ?: error("constellation.ask: name the class")
        val sumo = SumoCorpus.classifier
        val id = sumo.classId(cls)?.value ?: SumoCorpus.nounClassId(cls.lowercase()).takeIf { it >= 0 }
            ?: error("constellation.ask: '$cls' is neither a SUMO class nor a mapped noun")
        val c = constellation(name)
        val held = c.held(id, SumoCorpus.closure(id)).sortedByDescending { Nal.truthOf(it.belief).confidence }
        // The join: facts read by the same parser as the norms, in premise form.
        val factText = ((inputs["facts"] ?: inputs["facts?"])?.toString() ?: node.params["facts"]).orEmpty()
        val facts = if (factText.isBlank()) emptySet() else NormClauses.facts(CoreNlpRuntime().use { it.analyze(factText) })
        val fired = c.fire(SumoCorpus.closure(id), c.facts + facts)
        fun addresses(ids: RoaringSeries) = ids.toIntArray().map { "constellation/$name/norm/${c.statements[it].id}" }
        fun q(s: String) = "\"" + s.replace("\"", "\\\"") + "\""
        val className = sumo.className(SumoClassId(id))
        val rows = held.map { h ->
            val t = Nal.truthOf(h.belief)
            val via = sumo.className(SumoClassId(h.via))
            bank?.assertKif("(normHeld $className ${q(c.predicationNames[h.predication])} $via ${q("%.2f".format(t.frequency))} ${q("%.2f".format(t.confidence))})")
            mapOf("predication" to c.predicationNames[h.predication], "via" to via,
                "frequency" to t.frequency, "confidence" to t.confidence,
                "deduced" to h.deduced?.let { Nal.truthOf(it).let { d -> "%.2f/%.2f".format(d.frequency, d.confidence) } },
                "direct" to h.direct?.let { Nal.truthOf(it).let { d -> "%.2f/%.2f".format(d.frequency, d.confidence) } },
                "norms" to h.statements.map { "constellation/$name/norm/${c.statements[it].id}" })
        }
        mapOf("class" to className, "held" to rows, "count" to rows.size, "facts" to facts.toList(),
            "fires" to addresses(fired.fires), "pending" to fired.pending.mapValues { addresses(it.value) })
    }

    /**
     * `wiki.read`: the wiki's pages back as one text, each page opened by a `§ <path>. ` heading line,
     * a recurring shape `book.curate` finds as its sections, so it curates the wiki like any book and a constellation can join
     * it. `pages` lists the page paths with their content ids, so a caller sees which pages changed.
     */
    private val wikiRead = LcncNodeRunner { node, _ ->
        val under = node.params["dir"]?.takeIf { it.isNotBlank() }?.let { File(wiki, it) } ?: wiki
        require(under.canonicalPath.startsWith(wiki.canonicalPath)) { "wiki.read: ${node.params["dir"]} is outside the wiki" }
        val files = under.walkTopDown().filter { it.isFile && it.name.endsWith(".md") }.sortedBy { it.path }.toList()
        val pages = files.map { f ->
            val rel = f.relativeTo(wiki).path
            val body = f.readText()
            Triple(rel, body, borg.trikeshed.job.ContentId.of(body.encodeToByteArray()).value)
        }
        mapOf(
            "text" to pages.joinToString("\n\n") { (rel, body, _) -> "§ $rel. ${body.trim()}" },
            "pages" to pages.map { (rel, _, cid) -> mapOf("page" to rel, "cid" to cid) },
            "count" to pages.size,
        )
    }

    /**
     * True when [book] is curated from [source] (the content id of the text it was read from) and is a member
     * of [constellation]: what an intake needs no second reading for. A book read from other text — the source
     * re-extracted, its streams re-cut — is not held, and its next curation replaces it on join.
     */
    fun holds(constellation: String, book: String, source: String): Boolean = bookFile(book).isFile &&
        sourceFile(book).takeIf { it.isFile }?.readText()?.trim() == source &&
        File(root, "$constellation.members").takeIf { it.isFile }?.useLines { ls -> ls.any { it == book } } == true

    /**
     * [book]'s source was removed: it leaves [constellation]'s members, its saved reading is deleted, and the
     * constellation is rebuilt from the members that remain the next time it is read.
     */
    fun leave(constellation: String, book: String) {
        File(root, "$constellation.members").takeIf { it.isFile }?.let { f ->
            f.writeText(f.readLines().filter { it.isNotBlank() && it != book }.joinToString("") { it + "\n" })
        }
        for (f in listOf(bookFile(book), sourceFile(book), File(books, "${safe(book)}.sections.jsonl"))) f.delete()
        File(books, "${safe(book)}.readings").deleteRecursively()
        File(books, "${safe(book)}.jev").deleteRecursively()
        synchronized(loaded) { loaded.remove(book) }
        live.remove(constellation)
    }

    /** The content id of the text a book was curated from, beside the book. */
    private fun sourceFile(name: String) = File(books, safe(name) + ".source")

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]+"), "-")
    private fun bookFile(name: String) = File(books, safe(name) + ".json")

    /** The section texts beside a book, one JSON string per line in section order: what a section reading shows. */
    private fun saveSections(name: String, sections: List<String>) =
        File(books, "${safe(name)}.sections.jsonl").writeText(sections.joinToString("\n") { jsonOf(it) })

    private fun save(b: Book) = bookFile(b.name).writeText(jsonOf(mapOf(
        "name" to b.name, "work" to b.work, "date" to b.date, "headings" to b.headings,
        "statements" to b.statements.map { it.toMap() },
        "support" to b.support.map { it.toList() },
        "facts" to b.facts.toList(),
        "cites" to b.cites.map { listOf(it.first, it.second) },
    )))

    /** Books as last read, keyed by name; a book re-reads only when its file changes (length and mtime). */
    private val loaded = HashMap<String, Pair<Long, Book>>()

    fun load(name: String): Book? {
        val f = bookFile(name).takeIf { it.isFile } ?: return null
        val stamp = f.lastModified() * 31 + f.length()
        synchronized(loaded) { loaded[name]?.takeIf { it.first == stamp }?.let { return it.second } }
        return read(f)?.also { b -> synchronized(loaded) { loaded[name] = stamp to b } }
    }

    private fun read(f: File): Book? {
        val m = reifyMap(f.readText())
        fun str(v: Any?) = v?.toString()?.takeIf { it.isNotEmpty() }
        // A bearer the sense rete or NARS types where the book was crafted takes that class over the one curation froze.
        val at = mem?.let { located(m["name"].toString()).at }
        val statements = (m["statements"] as List<*>).map { s ->
            s as Map<*, *>
            val bearer = s["bearer"].toString(); val head = bearer.substringAfterLast(' ')
            val learned = at?.let { a -> (typed(head, a) ?: typed(head.removeSuffix("s"), a))?.let { SumoCorpus.classifier.classId(it.cls)?.value } }
            NormStatement(bearer, Modality.entries.first { it.key == s["modality"] },
                s["action"].toString(), str(s["object"]), str(s["condition"]), learned ?: (s["bearerClass"] as? Number)?.toInt() ?: -1)
        }
        val (judged, nouls) = fidelity(m["name"].toString(), statements)
        return Book(
            m["name"].toString(), m["work"].toString(), m["date"].toString(),
            (m["headings"] as List<*>).map { it.toString() },
            statements,
            (m["support"] as List<*>).map { l -> (l as List<*>).map { (it as Number).toInt() }.toIntArray() },
            (m["facts"] as? List<*>)?.map { it.toString() }?.toSet() ?: emptySet(),
            (m["cites"] as? List<*>)?.map { p -> (p as List<*>).let { (it[0] as Number).toInt() to (it[1] as Number).toInt() } } ?: emptyList(),
            judged, nouls, established(m["name"].toString()),
        )
    }

    /** The premises a book's Jev tables found its pages state as fact. */
    private fun established(name: String): Set<String> = tablesOf(name).flatMap { (_, t) ->
        (t["establishes"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("premise")?.toString() }
    }.toSet()

    /** Jev's table for section [k] of a book, beside its readings. */
    private fun tableFile(name: String, k: Int) = File(books, "${safe(name)}.jev/$k.json")

    /**
     * Section [k]'s Jev table, reified once per version of its file: fidelity, establishes, locating a book, priming
     * NARS, the section view, the ring's replay, scoring and the oracle all read the same parse.
     */
    private fun tableOf(name: String, k: Int): Map<*, *>? = tableOf(tableFile(name, k))

    private fun tableOf(f: File): Map<*, *>? {
        if (!f.isFile) return null
        val stamp = f.lastModified() * 31 + f.length()
        synchronized(reified) { reified[f.path]?.takeIf { it.first == stamp }?.let { return it.second } }
        val t = reifyMap(f.readText())
        synchronized(reified) { reified[f.path] = stamp to t }
        return t
    }

    /** Every Jev table of a book, by section ordinal. */
    private fun tablesOf(name: String): List<Pair<Int, Map<*, *>>> =
        File(books, "${safe(name)}.jev").listFiles { x -> x.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> f.name.removeSuffix(".json").toIntOrNull()?.let { k -> tableOf(f)?.let { k to it } } }

    /** Reified Jev tables by path, each with the stamp of the file version it was read from; at most [PARSED] kept. */
    private val reified = object : LinkedHashMap<String, Pair<Long, Map<*, *>>>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Map<*, *>>>) = size > PARSED * 8
    }

    private val answering = Any()
    private fun cid(s: String) = borg.trikeshed.job.ContentId.of(s.encodeToByteArray()).hex

    /**
     * Jev over [state], each question asked once: an answer stands under the content ids of its state and its question,
     * one file per state, and only questions with no standing answer go out, together in one request. A state that
     * changes (a truth revised, a premise consumed into the facts) is a new state, so its questions are asked anew.
     * The reply holds every answer, standing or new; `usage` is what went out, `asked` how many questions did.
     */
    suspend fun asked(key: String, state: Map<String, Any?>, questions: Map<String, Map<String, Any?>>): Map<String, Any?> {
        val f = File(root, "jev/${cid(jsonOf(state))}.json")
        val ids = questions.mapValues { (_, q) -> cid(jsonOf(q)) }
        fun standing(): Map<String, Any?> = synchronized(answering) { f.takeIf { it.isFile }?.let { reifyMap(it.readText()) } ?: emptyMap() }
        val had = standing()
        val missing = questions.filterKeys { ids.getValue(it) !in had }
        val reply = if (missing.isEmpty()) emptyMap() else Jev.ask(key, state, missing)
        if (missing.isNotEmpty()) {
            val got = reply["answers"] as? Map<*, *> ?: return reply
            synchronized(answering) {
                val all = LinkedHashMap<String, Any?>(standing())
                for (id in missing.keys) got[id]?.let { all[ids.getValue(id)] = it }
                f.parentFile.mkdirs(); f.writeText(jsonOf(all))
            }
        }
        val now = standing()
        return mapOf("model" to reply["model"], "usage" to (reply["usage"] ?: mapOf("input_tokens" to 0, "output_tokens" to 0)),
            "answers" to questions.mapValues { (q, _) -> now[ids.getValue(q)] }, "asked" to missing.size)
    }

    /**
     * Every (statement, section) noul a book's Jev tables hold, as the book's `judged`/`nouls` columns: ascending
     * packInts(statement, section), and a pair judged in two windows of its section keeps the higher noul.
     */
    private fun fidelity(name: String, statements: List<NormStatement>): Pair<LongArray, FloatArray> {
        val byKey = statements.withIndex().associate { it.value.key to it.index }
        val best = HashMap<Long, Float>()
        for ((k, t) in tablesOf(name)) {
            for (row in (t["tuples"] as? List<*>).orEmpty()) {
                val r = row as? Map<*, *> ?: continue
                val i = byKey[r["key"].toString()] ?: continue
                val n = (r["noul"] as? Number)?.toFloat() ?: continue
                val at = packInts(i, k)
                best[at] = maxOf(best[at] ?: 0f, n)
            }
        }
        val keys = best.keys.sorted().toLongArray()
        return keys to FloatArray(keys.size) { best.getValue(keys[it]) }
    }

    /** A statement as CoreNLP read it into a section reading, keyed as the book keys it: force|bearer|action|object|condition. */
    private fun keyOf(st: Map<*, *>) = listOf(st["force"], st["bearer"], st["action"], st["object"] ?: "", st["condition"] ?: "").joinToString("|")

    /** The books a constellation joins, in join order. */
    fun members(constellation: String): List<String> =
        File(root, "$constellation.members").takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.distinct().orEmpty()

    /** A section's reading: as curation saved it, else parsed once now and saved beside the book. */
    private fun reading(name: String, k: Int, body: String): List<Map<*, *>> {
        val f = readingFile(name, k)
        return f.takeIf { it.isFile }?.let { (reify(it.readText()) as? List<*>)?.map { s -> s as Map<*, *> } }
            ?: sentences(body, CoreNlpRuntime().use { it.analyze(body) }).also { f.parentFile.mkdirs(); f.writeText(jsonOf(it)) }
    }

    /**
     * Jev's table for section [k] of [b], asked over the section's reading in windows of whole sentences. Per window:
     * whether the text is legible print rather than OCR garble; per tuple CoreNLP read there, whether the page states
     * that norm as read; per span CoreNLP tagged DATE, whether it names a date at all and whether it stands out of the
     * work's time. Once per section: whether it begins and ends whole. Code adds the exact check, a year past the book's
     * own date. The table is the page's judgment of its tuples: a page that does not state what was read from it is
     * the parse's failure, measured. Each window also carries the rete's [open] premises, speculatively: those the
     * page states as fact are the table's `establishes`, and join the facts. A noun the lexicon gives two or more SUMO
     * senses is asked once per section, in the window it first appears in: which sense its sentence uses, each sense
     * described by its WordNet gloss. The lexicon's preferred sense is one option; Jev's choice discounts it.
     */
    private suspend fun table(key: String, b: Book, k: Int, body: String, read: List<Map<*, *>>, open: List<Pair<String, NormStatement>>,
                              prior: List<Pair<String, Double>>, gate: kotlinx.coroutines.sync.Semaphore,
                              at: Locality, posited: Map<String, List<String>>, byKey: Map<String, Int>): Map<String, Any?> {
        val era = Regex("\\b(1[5-9]|20)\\d\\d\\b").find(b.date)?.value?.toInt()
        val windows = ArrayList<List<Map<*, *>>>()
        var w = ArrayList<Map<*, *>>(); var chars = 0; var tuples = 0
        for (s in read) {
            val n = (s["text"] as? String).orEmpty().length; val t = (s["statements"] as? List<*>).orEmpty().size
            if (w.isNotEmpty() && (chars + n > WINDOW_CHARS || tuples + t > TUPLES_ASKED)) { windows.add(w); w = ArrayList(); chars = 0; tuples = 0 }
            w.add(s); chars += n; tuples += t
        }
        if (w.isNotEmpty()) windows.add(w)
        fun id(c: Char, i: Int) = c + i.toString().padStart(2, '0')
        val rows = ArrayList<Map<String, Any?>>(); val dated = ArrayList<Map<String, Any?>>(); val senses = ArrayList<Map<String, Any?>>()
        val legible = ArrayList<Double>(); val errors = ArrayList<String>(); val establishes = LinkedHashMap<String, Double>(prior.toMap())
        var whole: Double? = null; var tokens = 0L; var fresh = 0; var requests = 0
        class Sense(val lemma: String, val read: String?, val sentence: String, val options: List<String>, val posited: List<String>, val audit: Boolean)
        class Asked(val tuples: List<Pair<String, Map<*, *>>>, val dates: List<Pair<String, String>>, val premises: List<Pair<String, NormStatement>>,
                    val senses: List<Sense>, val reply: Map<String, Any?>)
        // A word is asked once per window: each window is its own source of evidence for NARS, as one sense per
        // section capped a word at a few dozen judgments where a production needs one in fifty against it.
        val sensesOf = windows.mapIndexed { x, win ->
            val out = ArrayList<Sense>()
            if (!SENSES_ENABLED) return@mapIndexed out
            val sensed = HashSet<String>()
            for (s in win) for (c in (s["concepts"] as? List<*>).orEmpty()) {
                val m = c as? Map<*, *> ?: continue
                val lemma = m["lemma"]?.toString() ?: continue
                if (out.size >= SENSES_ASKED || lemma in sensed || lemma.length < 3 || !lemma.all { it in 'a'..'z' }) continue
                val read = m["class"]?.toString()
                // CoreNLP's lexicon proposes senses; NARS adds the classes it posits for a word the lexicon cannot type or Jev refused.
                val lexical = senseOptions(lemma)
                val more = posited[lemma].orEmpty().filter { it !in lexical }
                val options = lexical + more
                if (options.size < 2 && (options.isEmpty() || read != null)) continue
                // A sense the rete holds where this book was crafted is not asked again, but for an occasional audit.
                val audit = rule(lemma, at) != null
                if (audit && cid("$lemma\u0000${b.name}\u0000$k\u0000$x").take(4).toInt(16) % AUDIT != 0) continue
                sensed.add(lemma); out.add(Sense(lemma, read, s["text"].toString(), options, more, audit))
            }
            out
        }
        val replies = kotlinx.coroutines.coroutineScope { windows.withIndex().map { (x, win) -> async {
            val page = win.joinToString(" ") { it["text"].toString() }
            val tuplesOf = win.flatMap { s -> (s["statements"] as? List<*>).orEmpty().map { s["text"].toString() to (it as Map<*, *>) } }
                .filter { (_, st) -> keyOf(st) in byKey }.distinctBy { keyOf(it.second) }.take(TUPLES_ASKED)
            val datesOf = win.mapNotNull { s -> ((s["entities"] as? Map<*, *>)?.get("DATE") as? List<*>)?.takeIf { it.isNotEmpty() }
                ?.let { s["text"].toString() to it.joinToString(" ") } }.take(DATES_ASKED)
            val state = linkedMapOf<String, Any?>("work" to b.work, "page" to page)
            if (x == 0 && windows.size > 1) state["section_end"] = body.takeLast(END_CHARS)
            state["tuples"] = tuplesOf.withIndex().associate { (i, p) -> id('T', i) to mapOf("bearer" to p.second["bearer"], "force" to p.second["force"],
                "action" to p.second["action"].toString().replace('_', ' '), "object" to p.second["object"], "condition" to p.second["condition"], "sentence" to p.first) }
            state["dates"] = datesOf.withIndex().associate { (i, d) -> id('D', i) to mapOf("sentence" to d.first, "tagged" to d.second) }
            val q = LinkedHashMap<String, Map<String, Any?>>()
            q["legible"] = Jev.noul("Is `page` legible printed language, rather than OCR garble: words broken or run together, stray symbols, columns interleaved?")
            if (x == 0) q["whole"] = Jev.noul(if (windows.size > 1) "Does this section of `work`, which opens with `page` and closes with `section_end`, begin and end at natural boundaries (a heading, entry, paragraph or sentence) rather than mid-sentence?"
                else "Does `page`, one section of `work`, begin and end at natural boundaries (a heading, entry, paragraph or sentence) rather than mid-sentence?")
            for (i in tuplesOf.indices) q["t$i"] = Jev.noul("Does `page` state the norm `tuples.${id('T', i)}`: that bearer, bound with that force, to that action, object and condition?",
                "The page states this norm as read", "The page does not state it, or states it of another bearer, force, action or condition")
            for (i in datesOf.indices) {
                val d = "`dates.${id('D', i)}.tagged` in `dates.${id('D', i)}.sentence`"
                q["d$i"] = Jev.noul("Do the words $d name a calendar date, year or period, rather than a number of another kind (a proclamation, statute, section, page or sum)?")
                q["l$i"] = Jev.noul("Do the words $d place it later than the rest of `page` and `work` belong to, so the time named is out of its time?")
            }
            // The open premises whose every word the page carries, each in its own question rather than the state:
            // the page's standing answers survive the open set changing.
            val lower = page.lowercase()
            val premisesOf = open.filter { (p, _) -> askable(p) && p.split(' ').filter { it.length > 2 && it != "not" }.let { ws -> ws.isNotEmpty() && ws.all { lower.contains(it.dropLast(1)) } } }
                .take(PREMISES_TABLED)
            for ((i, p) in premisesOf.withIndex()) q["p$i"] = Jev.noul(mapOf("premise" to question(p.first), "norm" to p.second.sentence,
                "question" to "Does `page` state as fact what `premise` asks, so that `norm` applies?"),
                "The page states that the premise holds", "The page does not state it, or only names it as a condition")
            // Each sense in its own question, the word and its sentence beside it: the standing answer outlives the window.
            // Jev may refuse every sense offered, so the options never confine it to what CoreNLP and NARS expect.
            for ((i, s) in sensesOf[x].withIndex()) q["s$i"] = Jev.choice(mapOf("word" to s.lemma, "sentence" to s.sentence,
                "question" to "In which sense does `sentence` use the word `word`?"),
                s.options.associateWith { SumoCorpus.nounGloss(s.lemma, it) ?: SumoCorpus.nounGloss(s.lemma.removeSuffix("s"), it) ?: SumoCorpus.classGloss(it) } +
                    (NONE to "none of the other senses: the word is used here in a sense not listed"))
            Asked(tuplesOf, datesOf, premisesOf, sensesOf[x], gate.withPermit { runCatching { asked(key, state, q) }.getOrElse { e -> mapOf("error" to (e.message ?: e.toString())) } })
        } }.awaitAll() }
        for ((x, a) in replies.withIndex()) {
            val tuplesOf = a.tuples; val datesOf = a.dates; val reply = a.reply
            val answers = reply["answers"] as? Map<*, *>
            if (answers == null) { errors.add(reply["error"]?.toString() ?: "no answers"); continue }
            tokens += ((reply["usage"] as? Map<*, *>)?.get("input_tokens") as? Number)?.toLong() ?: 0L
            fresh += (reply["asked"] as? Number)?.toInt() ?: 0
            if (((reply["asked"] as? Number)?.toInt() ?: 0) > 0) requests++
            legible.add(Jev.noulOf(answers, "legible"))
            if (x == 0) whole = Jev.noulOf(answers, "whole")
            for ((i, p) in tuplesOf.withIndex()) rows.add(linkedMapOf("key" to keyOf(p.second), "sentence" to b.statements[byKey.getValue(keyOf(p.second))].sentence,
                "window" to x, "noul" to Jev.noulOf(answers, "t$i")))
            for ((i, d) in datesOf.withIndex()) {
                val years = Regex("\\b\\d{4}\\b").findAll(d.second).map { it.value.toInt() }.toList()
                dated.add(linkedMapOf("tagged" to d.second, "sentence" to d.first, "date" to Jev.noulOf(answers, "d$i"),
                    "late" to Jev.noulOf(answers, "l$i"), "after" to era?.let { e -> years.any { it > e } }))
            }
            for ((i, p) in a.premises.withIndex()) Jev.noulOf(answers, "p$i").takeIf { it >= HOLDS }?.let { n -> establishes[p.first] = maxOf(establishes[p.first] ?: 0.0, n) }
            for ((i, s) in a.senses.withIndex()) {
                val p = ((answers["s$i"] as? Map<*, *>)?.get("probabilities") as? Map<*, *>).orEmpty()
                    .mapNotNull { (o, n) -> (n as? Number)?.let { o.toString() to it.toDouble() } }.toMap()
                val chosen = p.maxByOrNull { it.value }?.key ?: continue
                val readP = s.read?.let { p[it] } ?: 0.0
                // The lexicon's sense is discounted where Jev holds it false, as a premise joins the facts where Jev holds it true.
                // The whole distribution goes to NARS: each sense offered is evidence for or against, from this sentence once.
                senses.add(linkedMapOf("lemma" to s.lemma, "sentence" to s.sentence, "read" to s.read, "sense" to chosen,
                    "p" to p.getValue(chosen), "readP" to readP, "discounts" to (chosen != s.read && chosen != NONE && readP <= 1 - HOLDS),
                    "refused" to (chosen == NONE), "posited" to s.posited, "audit" to s.audit, "probabilities" to p,
                    "source" to cid("${b.name}\u0000$k\u0000$x\u0000${s.sentence}")))
            }
        }
        val mine = b.support.indices.count { k in b.support[it] }
        return linkedMapOf("section" to k, "windows" to windows.size, "legible" to legible, "whole" to whole, "tuples" to rows,
            "unasked" to mine - rows.map { it["key"] }.distinct().size, "dates" to dated,
            "senses" to (if (SENSES_ENABLED) senses else tableOf(b.name, k)?.get("senses") ?: emptyList<Any?>()),
            "establishes" to establishes.map { (p, n) -> mapOf("premise" to p, "question" to question(p), "noul" to n) },
            "tokens" to tokens, "asked" to fresh, "requests" to requests, "errors" to errors)
    }

    /** The scoring run per book: sections tabled, of how many, Jev requests sent, questions asked, and input tokens spent. */
    val scored = java.util.concurrent.ConcurrentHashMap<String, Map<String, Any?>>()

    /**
     * Tables every section of [names] with Jev, smallest book first. Every window is put to Jev again, and only its
     * questions with no standing answer go out: a table stands until its state is consumed (a premise the corpus states
     * joins the facts), then updates. Each scored book reloads, and its constellation rejoins on the weighed evidence and
     * the premises its pages established, so the next book is asked only what is still open.
     */
    suspend fun score(key: String, names: List<String>) {
        val gate = kotlinx.coroutines.sync.Semaphore(JEV_AT_ONCE)
        val m = memory()
        synchronized(m) { m.tick() }
        for (name in names.sortedBy { texts(it).sumOf(String::length) }) {
            val b = load(name) ?: continue
            val ts = texts(name)
            val c = constellationOf(name)?.let(::constellation)
            val open = c?.open()?.entries?.sortedByDescending { it.value.cardinality }?.map { (p, ids) -> p to c.statements[ids.toIntArray().first()] }.orEmpty()
            // Where the book was crafted, and the classes NARS posits for the words its lexicon cannot type.
            val at = located(name).at
            val posited = c?.let(::posits).orEmpty()
            val tokens = java.util.concurrent.atomic.AtomicLong(); val done = java.util.concurrent.atomic.AtomicInteger()
            val requests = java.util.concurrent.atomic.AtomicInteger(); val questions = java.util.concurrent.atomic.AtomicInteger()
            val began = System.currentTimeMillis()
            fun progress(tabled: Int) = mapOf("book" to name, "tabled" to tabled, "of" to ts.size, "requests" to requests.get(),
                "questions" to questions.get(), "tokens" to tokens.get(), "open" to open.size)
            scored[name] = progress(0)
            // Readings first, one parse at a time: a book curated before readings were kept is parsed here once.
            val read = ts.indices.map { k -> reading(name, k, ts[k]) }
            // One statement index per book: built per section, every section of a dictionary held its own copy at once.
            val byKey = b.statements.withIndex().associate { it.value.key to it.index }
            kotlinx.coroutines.coroutineScope { ts.indices.map { k -> async {
                // A premise this page established joined the facts and left the open set: the rewritten table keeps it.
                val prior = (tableOf(name, k)?.get("establishes") as? List<*>).orEmpty()
                    .mapNotNull { e -> (e as? Map<*, *>)?.let { it["premise"].toString() to ((it["noul"] as? Number)?.toDouble() ?: 0.0) } }
                    .filter { askable(it.first) }
                val t = table(key, b, k, ts[k], read[k], open, prior, gate, at, posited, byKey)
                tokens.addAndGet(t["tokens"] as Long); requests.addAndGet(t["requests"] as Int); questions.addAndGet(t["asked"] as Int)
                // Whole or not at all: a page read while its table is rewritten sees the old table or the new one, never a torn file.
                val f = tableFile(name, k).apply { parentFile.mkdirs() }
                val tmp = File(f.parentFile, "${f.name}.tmp").apply { writeText(jsonOf(t)) }
                java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                // Jev's judgments of the page's words revise NARS, each sentence's judgment once.
                synchronized(m) { observe(m, name, k, at, t) }
                scored[name] = progress(done.incrementAndGet())
            } }.awaitAll() }
            // The book's tables as they stand are its judgments: its rows are read again from them, so a section judged
            // again replaces its earlier judgment rather than counting the same text twice.
            val tabled = tablesOf(name)
            val rules = synchronized(m) {
                m.replace(name)
                for ((k, t) in tabled) observe(m, name, k, at, t)
                myelinate(m).also { save(m) }
            }
            senseEpoch++
            System.err.println("[JEV] $name: ${ts.size} sections, ${requests.get()} requests, ${questions.get()} questions, ${tokens.get()} tokens, " +
                "${System.currentTimeMillis() - began}ms; senses at $at: ${m.rows} rows, ${m.judgments} judgments, ${rules.minted.size} minted, ${rules.revised.size} revised, ${rules.retracted.size} retracted")
            scored[name] = progress(ts.size) + ("ms" to System.currentTimeMillis() - began)
            synchronized(loaded) { loaded.remove(name) }
            constellationOf(name)?.let { live.remove(it) }
        }
    }

    private fun constellation(name: String): Constellation = live.getOrPut(name) {
        Constellation(name).also { c ->
            File(root, "$name.members").takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.distinct()
                ?.forEach { b -> load(b)?.let(c::join) }
        }
    }

    private val curate = LcncNodeRunner { node, inputs ->
        val text = (inputs["text"] ?: inputs["text?"]) as? String ?: ""
        // The book is the document it was read from: a wired doc's id, else the text's own content id.
        val doc = (inputs["doc"] ?: inputs["doc?"]) as? Map<*, *>
        // Conventions bind late: the document's curation notes, then the node's params, then the text itself.
        val notes = ((inputs["conventions"] ?: inputs["conventions?"]) as? Map<*, *>).orEmpty().entries
            .associate { (k, v) -> k.toString().lowercase() to v.toString() }
        fun convention(key: String) = node.params[key]?.takeIf { it.isNotBlank() } ?: notes[key]?.takeIf { it.isNotBlank() }
        val name = convention("book") ?: doc?.get("id")?.toString()
            ?: borg.trikeshed.job.ContentId.of(text.encodeToByteArray()).value.takeLast(16)
        val generic = (convention("generic") ?: "").split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val cut = SectionShapes.sections(text)
        val shape = cut.shape; val sections = cut.texts
        val cites = SectionShapes.cites(cut)
        // The notes' prose, read by the same parser: what the curator holds the work to. Its statements are
        // conventions for the reading (their bearers' classes steer senses) and the measure of conformance.
        val notesProse = ((inputs["notes"] ?: inputs["notes?"]) as? String)?.takeIf { it.isNotBlank() }
        val noted = notesProse?.let { np -> CoreNlpRuntime().use { nlp -> NormClauses.extract(nlp.analyze(np), generic) } }.orEmpty()
            .map { NormStatement.of(it, ::bearerClass) }
        // What is already believed: the classes a named constellation's bearers and the notes' bearers resolve to, with ancestors.
        val believed = (convention("constellation")?.let { n -> constellation(n).bearing.keys }.orEmpty() + noted.map { it.bearerClass }.filter { it >= 0 })
            .fold(RoaringSeries.EMPTY) { acc, c -> acc or SumoCorpus.closure(c) }
        val typed = java.util.IdentityHashMap<NormClause, Int>()
        val facts = LinkedHashSet<String>()
        val began = System.currentTimeMillis()
        // Where the work was crafted: what the sense rete and NARS have learned of its words there types its bearers.
        memory()
        val crafted = Locality.of(convention("date")?.let { Regex("\\b(1[4-9]|20)\\d\\d\\b").find(it)?.value?.toInt() }
            ?: FrontMatter.titleYear(name).takeIf { it > 0 }
            ?: FrontMatter.paged(text)?.let { f -> (FrontMatter.years(f) + FrontMatter.romanYears(f)).toList().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key }
            ?: 0, Register.CURRENT)
        // Pointcuts into the activity ring: the book is a trunk under the run, each section a leaf that
        // begins at its parse and ends with what it yielded, so /curator and /api/lcnc/trail show the reading.
        val trail = borg.trikeshed.lcnc.LcncTrail.live
        val run = trail.lastRun()
        val bookNode = listOf("book.curate", name)
        val secNodes = IntArray(sections.size) { -1 }
        val secKeys = sectionKeys(sections.map(Book::heading))
        // A book read again replaces its section readings: none from the last reading stand.
        File(books, "${safe(name)}.readings").apply { deleteRecursively(); mkdirs() }
        File(books, "${safe(name)}.jev").deleteRecursively()
        val clauses = CoreNlpRuntime().use { nlp -> sections.mapIndexed { k, sec ->
            if (k % 25 == 0) System.err.println("[CURATE] $name: section $k/${sections.size}, ${(System.currentTimeMillis() - began) / 1000}s")
            val at = trail.node(bookNode[0], listOf(bookNode[1]), secKeys[k], "book.section")
            secNodes[k] = at
            trail.emit(run, borg.trikeshed.lcnc.LcncTrail.Kind.BEGIN, at)
            val t0 = System.currentTimeMillis()
            val doc = nlp.analyze(sec)
            readingFile(name, k).writeText(jsonOf(sentences(sec, doc)))
            val found = NormClauses.extract(doc, generic)
            trail.emit(run, borg.trikeshed.lcnc.LcncTrail.Kind.END, at,
                "${found.size} statements, ${doc.sentences.a} sentences, ${sec.length} chars, ${System.currentTimeMillis() - t0}ms · ${k + 1}/${sections.size}")
            // The section's other nouns are the context a bearer's sense is read in.
            val nouns = doc.sentences.values().flatMap { s -> s.tokens.values().filter { it.tag.startsWith("NN") }.map { it.lemma.lowercase() } }.distinct()
            for (c in found) typed[c] = bearerClass(c, nouns, believed, crafted)
            // The reading into the ring: each statement a node under its section, each concept a hub in the
            // shared pool, and the predicate as links statement → bearer (subject) and → object (relation).
            // An atom is a noun the parse tagged (a lemma, alphabetic, not a stray letter): numbers, pronouns
            // and fragments of an object phrase are not concepts.
            val nounLemmas = doc.sentences.values().flatMap { s -> s.tokens.values().filter { it.tag.startsWith("NN") }.map { it.lemma.lowercase() } }.toSet()
            fun atom(phrase: String?): String? = phrase?.split(' ')?.lastOrNull { it.length > 2 && it.all(Char::isLetter) && it in nounLemmas }
            for ((j, c) in found.withIndex()) {
                val st = NormStatement.of(c) { typed[it] ?: -1 }
                val sNode = trail.node(bookNode[0], listOf(bookNode[1], secKeys[k]), "s$j", "book.statement")
                trail.emit(run, borg.trikeshed.lcnc.LcncTrail.Kind.END, sNode,
                    listOfNotNull(st.bearer, st.modality.key, st.action.replace('_', ' '), st.obj, st.condition).joinToString(" · "))
                ring(trail, run, sNode, st, atom(st.bearer), atom(st.obj), at = crafted)
            }
            facts.addAll(NormClauses.facts(doc))
            found
        } }
        // Bridges between sections: what the book's own prose cites.
        for ((from, to) in cites) if (secNodes.getOrElse(from) { -1 } >= 0 && secNodes.getOrElse(to) { -1 } >= 0)
            trail.link(run, secNodes[from], secNodes[to], "cites")
        val book = Book.of(name, convention("work") ?: name, convention("date") ?: convention("edition") ?: doc?.get("seq")?.toString() ?: "", sections, clauses, { typed[it] ?: bearerClass(it) }, facts, cites)
        val senses = book.statements.filter { it.bearerClass >= 0 }.associate { it.bearer to SumoCorpus.classifier.className(SumoClassId(it.bearerClass)) }
        save(book)
        saveSections(name, sections)
        sourceFile(name).writeText(borg.trikeshed.job.ContentId.of(text.encodeToByteArray()).value)
        // Conformance: each noted statement is held (the text states it), contradicted (the text states the
        // opposing force on its proposition), or unstated.
        val byProposition = book.statements.withIndex().groupBy({ it.value.proposition }, { it.index })
        fun where(i: Int) = book.support[i].take(3).map { book.headings.getOrElse(it) { "#$it" } }
        val conformance = noted.map { n ->
            val same = byProposition[n.proposition].orEmpty()
            val held = same.filter { book.statements[it].modality == n.modality }
            val against = same.filter { book.statements[it].modality.opposes(n.modality) }
            mapOf("note" to n.sentence, "verdict" to when { against.isNotEmpty() -> "contradicted"; held.isNotEmpty() -> "held"; else -> "unstated" },
                "sections" to (against.ifEmpty { held }).flatMap(::where))
        }
        mapOf("book" to name, "sections" to sections.size, "clauses" to clauses.sumOf { it.size }, "statements" to book.statements.size,
            "senses" to senses, "facts" to facts.size,
            "shape" to shape?.let { mapOf("key" to it.key, "lines" to it.lines.size, "counts" to it.counts.toString(), "heads" to it.heads.toString(),
                "belief" to it.belief.expectation()) },
            "cites" to cites.size, "conformance" to conformance)
    }

    /**
     * One section of a curated book, read whole: its text and shape (line kinds, Shannon bits, LZ
     * description length and normalized K-complexity), every statement it supports with force, SUMO
     * class and book-wide truth, the facts it states, the sections it cites and that cite it, and the
     * conflicts it takes part in with the opposing section. [section] is an ordinal or a heading prefix.
     */
    fun section(bookName: String, section: String): Map<String, Any?> {
        val b = load(bookName) ?: return mapOf("error" to "book '$bookName' is not curated")
        val k = section.toIntOrNull()?.takeIf { it in b.headings.indices }
            ?: sectionKeys(b.headings).indexOf(section).takeIf { it >= 0 }
            ?: b.headings.indexOfFirst { it.startsWith(section) }.takeIf { it >= 0 }
            ?: return mapOf("error" to "no section '$section' in '$bookName'")
        // A section is parsed once per reading of its book: turning pages back, or a second viewer, reads the parse kept.
        val seen = "$bookName\u0000$k\u0000${File(books, "${safe(bookName)}.source").takeIf { it.isFile }?.readText().orEmpty()}\u0000${tableFile(bookName, k).lastModified()}\u0000$senseEpoch"
        synchronized(parsed) { parsed[seen] }?.let { return it }
        return read(b, bookName, k).also { v -> synchronized(parsed) { parsed[seen] = v } }
    }

    /** Sections read whole, most recent last: at most [PARSED] kept. */
    private val parsed = object : LinkedHashMap<String, Map<String, Any?>>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, Any?>>?) = size > PARSED
    }

    private fun read(b: Book, bookName: String, k: Int): Map<String, Any?> {
        val text = File(books, "${safe(bookName)}.sections.jsonl").takeIf { it.isFile }?.useLines { ls -> ls.drop(k).firstOrNull() }
            ?.let { reify(it) as? String }
        val lines = (text ?: b.headings[k]).split(Regex("(?<=[.;:])\\s+")).toSeries()
        val metrics = borg.trikeshed.cas.textualMetrics(lines)
        val sig = borg.trikeshed.cas.kolmogorovSchemaSignature(lines, metrics)
        val mine = b.support.indices.filter { k in b.support[it] }
        val sumo = SumoCorpus.classifier
        val byProp = b.statements.indices.groupBy { b.statements[it].proposition }
        fun ev(i: Int): Map<String, Any?> {
            // Book-wide truth: each supporting section one source, opposed force negative.
            val s = b.statements[i]
            val same = byProp[s.proposition].orEmpty()
            // Each section's unit weighed by Jev's noul that it states the statement, where Jev has judged it.
            fun units(j: Int) = b.support[j].sumOf { b.weight(j, it) }
            val pos = same.filter { b.statements[it].modality == s.modality }.sumOf(::units)
            val neg = same.filter { b.statements[it].modality.opposes(s.modality) }.sumOf(::units)
            val t = Nal.truthOf(EvidenceCoord(pos, neg))
            return linkedMapOf("id" to s.id, "sentence" to s.sentence, "bearer" to s.bearer, "force" to s.modality.key,
                "action" to s.action, "object" to s.obj, "condition" to s.condition,
                "class" to if (s.bearerClass >= 0) sumo.className(SumoClassId(s.bearerClass)) else null,
                "sections" to b.support[i].size, "f" to t.frequency, "c" to t.confidence,
                "opposed" to same.filter { b.statements[it].modality.opposes(s.modality) }.flatMap { o -> b.support[o].take(4).map { b.headings.getOrElse(it) { "#$it" } } })
        }
        // The parse's reading, as curation saved it; a book curated before readings were saved is parsed once here.
        // Each concept takes the class the sense rete or NARS holds for its word where the book was crafted; where neither
        // holds one, the page's own Jev table, where it chose a sense, discounts the lexicon's preferred one in every
        // sentence of the section (one sense per discourse). The lexicon's stays as `read`.
        val jev = tableOf(bookName, k)
        val sensed = discounting(jev)
        val at = if (SENSES_ENABLED) located(bookName).at else Locality.of(0, Register.CURRENT)
        val sentences = text?.let { reading(bookName, k, it) }.orEmpty().map { s -> discount(s, sensed, at) }
        val classes = sentences.flatMap { s -> (s["concepts"] as List<*>).mapNotNull { (it as Map<*, *>)["class"] } }
            .groupingBy { it.toString() }.eachCount().entries.sortedByDescending { it.value }.map { mapOf("class" to it.key, "n" to it.value) }
        return linkedMapOf(
            "book" to b.name, "work" to b.work, "date" to b.date, "ordinal" to k, "of" to b.headings.size,
            "heading" to b.headings[k], "text" to text, "sentences" to sentences, "classes" to classes,
            "shape" to mapOf("kinds" to sig.structuralKey, "lines" to metrics.lines, "chars" to metrics.characters,
                "tokens" to metrics.tokens, "uniqueTokens" to metrics.uniqueTokens, "shannonBitsPerByte" to metrics.shannonBitsPerByte,
                "lzPhrases" to sig.lzPhraseCount, "descriptionBits" to sig.descriptionBits, "k" to sig.normalizedComplexity),
            "statements" to mine.map(::ev).sortedByDescending { (it["sections"] as Int) },
            "cites" to b.cites.filter { it.first == k }.map { (_, to) -> b.headings.getOrElse(to) { "#$to" } },
            "citedBy" to b.cites.filter { it.second == k }.map { (from, _) -> b.headings.getOrElse(from) { "#$from" } },
            "prev" to b.headings.getOrNull(k - 1), "next" to b.headings.getOrNull(k + 1),
            "questions" to questions(bookName, k),
            "jev" to jev,
        )
    }

    /** The senses a Jev [table] holds against the lexicon's preferred one, by lemma. */
    private fun discounting(table: Map<*, *>?): Map<String, Map<*, *>> = if (!SENSES_ENABLED) emptyMap() else (table?.get("senses") as? List<*>).orEmpty()
        .mapNotNull { it as? Map<*, *> }.filter { it["discounts"] == true }.associateBy { it["lemma"].toString() }

    /**
     * Sentence [s] of a reading with each concept reclassed: by the sense rete or NARS where its book was crafted ([at]),
     * else by its section's Jev table ([sensed]); its class and lineage the learned sense, the lexicon's kept as `read`,
     * and `by` what typed it.
     */
    private fun discount(s: Map<*, *>, sensed: Map<String, Map<*, *>>, at: Locality): Map<*, *> {
        val sumo = SumoCorpus.classifier
        val concepts = (s["concepts"] as? List<*>).orEmpty().map { c0 ->
            val c = c0 as? Map<*, *> ?: return@map c0
            val lemma = c["lemma"]?.toString() ?: return@map c
            typed(lemma, at)?.takeIf { it.cls != c["class"] }?.let { t ->
                val id = sumo.classId(t.cls)?.value ?: return@let null
                LinkedHashMap<Any?, Any?>(c).apply { put("class", t.cls); put("is", lineage(id)); put("read", c["class"]); put("jev", t.e); put("by", t.by) }
            }?.let { return@map it }
            val j = sensed[lemma]?.takeIf { it["read"] == c["class"] } ?: return@map c
            val id = sumo.classId(j["sense"].toString())?.value ?: return@map c
            LinkedHashMap<Any?, Any?>(c).apply { put("class", j["sense"]); put("is", lineage(id)); put("read", j["read"]); put("jev", j["p"]); put("readP", j["readP"]); put("by", "jev") }
        }
        return LinkedHashMap<Any?, Any?>(s).apply { put("concepts", concepts) }
    }

    /** Class [id]'s three nearest ancestors by name. */
    private fun lineage(id: Int): List<String> = SumoCorpus.closure(id).toIntArray().filter { it != id }.sortedDescending().take(3)
        .map { SumoCorpus.classifier.className(SumoClassId(it)) }

    /** Section [k]'s parse reading, beside its book. */
    fun readingFile(name: String, k: Int) = File(books, "${safe(name)}.readings/$k.json")

    /** The constellation [book] is a member of, or null. */
    fun constellationOf(book: String): String? = root.listFiles { f -> f.isFile && f.name.endsWith(".members") }.orEmpty()
        .firstOrNull { f -> f.useLines { ls -> ls.any { it == book } } }?.name?.removeSuffix(".members")

    /** [text] in windows of whole sentences, at most [PASSAGE_CHARS] each; a longer sentence is cut at that length. */
    private fun windows(text: String): List<String> {
        val out = ArrayList<String>(); val w = StringBuilder()
        for (s in text.split(Regex("(?<=[.;:!?])\\s+"))) for (part in s.chunked(PASSAGE_CHARS)) {
            if (w.isNotEmpty() && w.length + part.length + 1 > PASSAGE_CHARS) { out.add(w.toString()); w.setLength(0) }
            if (w.isNotEmpty()) w.append(' ')
            w.append(part)
        }
        if (w.isNotEmpty()) out.add(w.toString())
        return out
    }

    /** A book's section texts, kept while its sections file is unchanged. */
    private val texts = HashMap<String, Pair<Long, List<String>>>()
    fun texts(book: String): List<String> {
        val f = File(books, "${safe(book)}.sections.jsonl").takeIf { it.isFile } ?: return emptyList()
        val stamp = f.lastModified() * 31 + f.length()
        synchronized(texts) { texts[book]?.takeIf { it.first == stamp }?.let { return it.second } }
        val t = f.readLines().map { (borg.trikeshed.parse.reify(it) as? String).orEmpty() }
        synchronized(texts) { texts[book] = stamp to t }
        return t
    }

    /**
     * Questions the NARS state puts to section [k] of [bookName]: the open premises its norms wait on,
     * the conflicts it takes part in, and the force of the norms it states with the most support.
     */
    fun questions(bookName: String, k: Int): List<String> {
        val name = constellationOf(bookName) ?: return emptyList()
        val c = constellation(name)
        val b = c.bookOrdinal(bookName).takeIf { it >= 0 } ?: return emptyList()
        val g = (b shl 20) or k
        val mine = c.statements.indices.filter { c.support[it].contains(g) }.sortedByDescending { c.support[it].cardinality }
        val out = LinkedHashSet<String>()
        for (id in mine) c.statements[id].premise?.takeIf { it !in c.facts }?.let { out.add(question(it)) }
        for (id in mine) for (o in c.opponents(id)) {
            val s = c.statements[id]
            out.add("Must ${s.bearer} ${s.predicate.replace('_', ' ')}, or must ${s.bearer} not?")
        }
        for (id in mine.take(QUESTIONS)) c.statements[id].let { s ->
            out.add("When ${if (s.modality == Modality.MAY) "may" else "must"} ${s.bearer} ${s.predicate.replace('_', ' ')}?")
        }
        return out.take(QUESTIONS)
    }

    /**
     * Jev over the corpus a question reaches, preprocessed by the constellation's NARS state. The question
     * is parsed by CoreNLP: its nouns resolve to SUMO classes, its premise-form facts join the working memory.
     * Code scoops the candidates: the open page, the sections of every member book sharing the most rare
     * lemmas with the question, and the norms its classes hold (stated, or deduced down is-a and revised)
     * with their NAL truth and opponents. One Jev request then answers, in parallel over that state: which
     * passage answers, whether any does, which norms bear, and which open premises the question establishes;
     * the premises it establishes join the facts and the rete fires again.
     */
    suspend fun oracle(key: String, bookName: String, k: Int, ask: String): Map<String, Any?> {
        val name = constellationOf(bookName) ?: return mapOf("error" to "book '$bookName' is in no constellation")
        val c = constellation(name)
        val sumo = SumoCorpus.classifier
        val doc = CoreNlpRuntime().use { it.analyze(ask) }
        val toks = doc.sentences.values().flatMap { it.tokens.values() }
        val lemmas = toks.filter { it.tag.startsWith("NN") || it.tag.startsWith("VB") || it.tag.startsWith("JJ") }
            .map { it.lemma.lowercase() }.filter { it.length > 2 && it.all(Char::isLetter) }.toSet()
        // Each noun in the sense its neighbours and the constellation's own bearer classes support, as curation reads bearers;
        // a noun whose lexicon sense the open page's Jev table discounts takes Jev's.
        val nouns = toks.filter { it.tag.startsWith("NN") }.map { it.lemma.lowercase() }.distinct()
        val believed = c.bearing.keys.fold(RoaringSeries.EMPTY) { acc, id -> acc or SumoCorpus.closure(id) }
        val sensed = discounting(tableOf(bookName, k))
        val classes = nouns.mapNotNull { l -> (sensed[l] ?: sensed[l.removeSuffix("s")])?.let { sumo.classId(it["sense"].toString())?.value }
            ?: (SumoCorpus.nounSense(l, nouns, believed).takeIf { it >= 0 } ?: SumoCorpus.nounSense(l.removeSuffix("s"), nouns, believed)).takeIf { it >= 0 } }
            .distinct()
        val mask = classes.fold(RoaringSeries.EMPTY) { acc, id -> acc or SumoCorpus.closure(id) }
        val stated = NormClauses.facts(doc)
        // Passages: windows of whole sentences over every member section; the open page's best windows first,
        // then the windows richest in the question's rarer lemmas.
        val all = c.books.flatMapIndexed { b, bk -> texts(bk.name).flatMapIndexed { s, t -> windows(t).map { Triple(b, s, it) } } }
        fun words(t: String) = t.lowercase().split(Regex("[^a-z]+")).filter { it.length > 2 }
        val df = HashMap<String, Int>()
        val bags = all.map { (_, _, t) -> words(t).filter { w -> lemmas.any { w.startsWith(it) } }.groupingBy { it }.eachCount() }
        for (bag in bags) for (l in lemmas) if (bag.keys.any { it.startsWith(l) }) df[l] = (df[l] ?: 0) + 1
        val scores = DoubleArray(all.size) { i -> lemmas.sumOf { l ->
            val tf = bags[i].entries.sumOf { (w, n) -> if (w.startsWith(l)) n else 0 }
            if (tf == 0) 0.0 else (1 + kotlin.math.ln(tf.toDouble())) * kotlin.math.ln((all.size + 1.0) / ((df[l] ?: 0) + 1))
        } }
        val open = c.bookOrdinal(bookName)
        val byScore = all.indices.sortedByDescending { scores[it] }
        val picked = (byScore.filter { all[it].first == open && all[it].second == k }.take(OPEN_PASSAGES) + byScore.filter { scores[it] > 0 })
            .distinct().take(PASSAGES)
        fun pid(i: Int) = "P" + i.toString().padStart(2, '0')
        val passages = picked.mapIndexed { i, a -> pid(i) to mapOf("book" to c.books[all[a].first].name,
            "section" to c.books[all[a].first].headings.getOrElse(all[a].second) { "#${all[a].second}" }, "text" to all[a].third) }.toMap()
        // Norms: held by the question's classes, then those sharing its lemmas, then the open page's.
        val held = classes.flatMap { c.held(it, SumoCorpus.closure(it)) }.flatMap { it.statements.toList() }
        val worded = c.statements.indices.filter { id -> c.statements[id].let { s -> lemmas.any { l -> l in s.bearer || l in s.action || s.obj?.contains(l) == true } } }
        val paged = if (open >= 0) c.statements.indices.filter { c.support[it].contains((open shl 20) or k) } else emptyList()
        val norms = (held + worded.sortedByDescending { c.truth(it).expectation() } + paged).distinct().take(NORMS_ASKED)
        fun nid(i: Int) = "N" + i.toString().padStart(2, '0')
        val normState = norms.mapIndexed { i, id -> val s = c.statements[id]; val t = c.truth(id)
            nid(i) to mapOf("norm" to s.sentence, "frequency" to t.frequency, "confidence" to t.confidence,
                "books" to c.booksStating(id).map { c.books[it].name }, "opposedBy" to c.opponents(id).map { c.statements[it].sentence }) }.toMap()
        val premises = norms.mapNotNull { c.statements[it].premise }.filter { askable(it) && it !in c.facts && it !in stated }.distinct().take(PREMISES_ASKED)
        val questions = LinkedHashMap<String, Map<String, Any?>>()
        questions["where"] = Jev.choice("Which passage in `passages` answers `question`? Choose none when no passage answers it.",
            passages.keys.associateWith { null } + ("none" to "No passage answers the question"))
        questions["exists"] = Jev.noul("Does any passage in `passages` state or directly imply the answer to `question`?",
            "At least one passage states or directly implies the answer", "No passage addresses the question")
        normState.keys.forEach { n -> questions["bears_$n"] = Jev.noul("Does the norm `norms.$n` bear on the answer to `question`?") }
        premises.forEachIndexed { i, p -> questions["premise_$i"] = Jev.noul("Does `question` state or presuppose this: ${question(p)}") }
        val state = mapOf("question" to ask, "passages" to passages, "norms" to normState)
        val reply = asked(key, state, questions)
        val answers = reply["answers"] as? Map<*, *> ?: return mapOf("error" to "Jev returned no answers", "reply" to reply)
        val where = answers["where"] as? Map<*, *>
        val p = (where?.get("probabilities") as? Map<*, *>).orEmpty()
        val ranked = passages.entries.sortedByDescending { (p[it.key] as? Number)?.toDouble() ?: 0.0 }
            .map { (id, v) -> v + mapOf("id" to id, "p" to ((p[id] as? Number)?.toDouble() ?: 0.0)) }
        val established = premises.filterIndexed { i, _ -> Jev.noulOf(answers, "premise_$i") >= HOLDS }
        val fired = c.fire(mask, c.facts + stated + established)
        return mapOf(
            "question" to ask, "constellation" to name, "model" to reply["model"], "usage" to reply["usage"], "asked" to reply["asked"],
            "exists" to Jev.noulOf(answers, "exists"), "choice" to where?.get("choice"), "confidence" to where?.get("confidence"),
            "passages" to ranked.take(RANKED),
            "classes" to classes.map { sumo.className(SumoClassId(it)) }, "facts" to (stated + established).toList(),
            "norms" to norms.mapIndexed { i, id -> normEntry(c, id) + mapOf("sentence" to c.statements[id].sentence, "bears" to Jev.noulOf(answers, "bears_${nid(i)}")) }
                .sortedByDescending { it["bears"] as Double },
            "premises" to premises.mapIndexed { i, pr -> mapOf("premise" to pr, "question" to question(pr), "holds" to Jev.noulOf(answers, "premise_$i")) },
            "fires" to fired.fires.toIntArray().take(RANKED).map { c.statements[it].sentence },
            "pending" to fired.pending.entries.take(RANKED).associate { (pr, ids) -> question(pr) to ids.toIntArray().map { c.statements[it].sentence } },
        )
    }

    /**
     * Per sentence, everything the parse [doc] of [body] registered: each noun a concept typed by SUMO (with its
     * nearest ancestors), each named entity, each predicate, and the statements read from it.
     */
    private fun sentences(body: String, doc: borg.trikeshed.nlp.NlpDocument): List<Map<String, Any?>> {
        val sumo = SumoCorpus.classifier
        val bySentence = NormClauses.extract(doc).groupBy { it.sentence }
        return doc.sentences.values().map { s ->
                val said = body.substring(s.begin, s.end).replace(Regex("\\s+"), " ").trim()
                val toks = s.tokens.values()
                val concepts = toks.filter { it.tag.startsWith("NN") }.map { t ->
                    val lemma = t.lemma.lowercase()
                    val id = SumoCorpus.nounClassId(lemma).takeIf { it >= 0 } ?: SumoCorpus.nounClassId(lemma.removeSuffix("s"))
                    linkedMapOf("word" to t.word, "lemma" to lemma, "class" to if (id >= 0) sumo.className(SumoClassId(id)) else null, "is" to if (id >= 0) lineage(id) else emptyList())
                }.distinctBy { it["lemma"] }
                val entities = toks.filter { it.ner != "O" }.groupBy { it.ner }.mapValues { (_, ts) -> ts.map { it.word }.distinct() }
                val predicates = toks.filter { it.tag.startsWith("VB") }.map { it.lemma.lowercase() }.distinct()
                val read = bySentence[said].orEmpty().map { c ->
                    val st = NormStatement.of(c) { bearerClass(it) }
                    linkedMapOf("bearer" to st.bearer, "force" to st.modality.key, "action" to st.action, "object" to st.obj,
                        "condition" to st.condition, "class" to if (st.bearerClass >= 0) sumo.className(SumoClassId(st.bearerClass)) else null)
                }
                linkedMapOf("text" to said, "tokens" to toks.size, "concepts" to concepts, "entities" to entities,
                    "predicates" to predicates, "statements" to read)
            }
    }

    /**
     * [c] stands in the ring under [runId]: a book read before this daemon started is not in the ring yet,
     * so its saved reading (sections, statements, concepts) goes in as it stands, with no parse, and the
     * constellation's conflicts bridge the sections stating each side.
     */
    private fun stand(c: Constellation, runId: Int) {
        val trail = borg.trikeshed.lcnc.LcncTrail.live
        for (bk in c.books) if (bk.headings.isNotEmpty() && !trail.known("$CURATE/${bk.name}/${sectionKeys(bk.headings)[0]}")) replay(trail, runId, bk)
        val keysOf = c.books.map { sectionKeys(it.headings) }
        fun sectionNode(g: Int): Int? = keysOf.getOrNull(g ushr 20)?.let { ks ->
            ks.getOrNull(g and 0xFFFFF)?.let { h -> trail.node(CURATE, listOf(c.books[g ushr 20].name), h, "book.section") } }
        for ((x, y) in c.conflicts().take(CONFLICT_LINKS)) {
            val a = c.support[x].toIntArray().firstOrNull()?.let(::sectionNode) ?: continue
            val b = c.support[y].toIntArray().firstOrNull()?.let(::sectionNode) ?: continue
            if (a != b) trail.link(runId, a, b, "conflict: ${c.statements[x].predicate.replace('_', ' ')}")
        }
    }

    /** Every saved constellation stands in the ring again, as its members were last joined. */
    fun restore() {
        // The sense memory stands before the books load, so their bearers and concepts are typed by the overlay.
        if (SENSES_ENABLED) memory()
        val saved = root.listFiles { f -> f.isFile && f.name.endsWith(".members") }.orEmpty()
        for (f in saved) {
            val c = constellation(f.name.removeSuffix(".members"))
            // Editions of one work align oldest → newest, as each join aligned them.
            for (i in c.books.indices) for (j in i + 1 until c.books.size) if (c.books[i].work == c.books[j].work) {
                if (c.books[i].date <= c.books[j].date) c.align(i, j) else c.align(j, i)
            }
            if (c.books.isNotEmpty()) stand(c, borg.trikeshed.lcnc.LcncTrail.live.run("$JOIN/${c.name}"))
        }
    }

    private val join = LcncNodeRunner { node, inputs ->
        val name = node.params["constellation"]?.takeIf { it.isNotBlank() } ?: error("constellation.join: name the constellation")
        val bookName = (inputs["book"] ?: inputs["book?"])?.toString() ?: node.params["book"] ?: error("constellation.join: no book")
        val book = load(bookName) ?: error("constellation.join: book '$bookName' is not curated")
        // A member curated again is read again: the constellation is cleared and rebuilt from its
        // members' saved books, so the new reading replaces the old one in statements, truth,
        // conflicts, alignment and tells — never kept stale, never counted twice.
        if (live[name]?.bookOrdinal(bookName)?.let { it >= 0 && live[name]!!.books[it].statements != book.statements } == true) live.remove(name)
        val c = constellation(name)
        val at = c.bookOrdinal(bookName).takeIf { it >= 0 } ?: c.join(book).also {
            File(root, "$name.members").appendText(bookName + "\n")
        }
        // Editions of one work align oldest → newest.
        for (other in c.books.indices) if (other != at && c.books[other].work == book.work) {
            val (older, newer) = if (c.books[other].date <= book.date) other to at else at to other
            c.align(older, newer)
        }
        val frontier = publish(c)
        stand(c, borg.trikeshed.lcnc.LcncTrail.live.lastRun())
        val admissible = c.admissible()
        val rules = admissible.map { id ->
            val s = c.statements[id]
            mapOf("antecedent" to s.antecedent, "consequent" to s.consequent, "copula" to "==>", "discount" to c.truth(id).confidence)
        }
        bank?.let { tell(it, c, admissible) }
        mapOf("constellation" to name, "books" to c.books.size, "statements" to c.statements.size,
            "restated" to c.restated().size, "conflicts" to c.conflicts().size, "rules" to rules,
            "rdf" to rdf(c), "frontier" to frontier, "tasks" to tasks(c),
            "unresolved" to (c.conflicts().isNotEmpty() || c.open().isNotEmpty()))
    }

    /**
     * The frontier as board commands for `kanban.submit`: each conflict and each norm restated by
     * [RESTATED_TASK] or more books is a card whose id is its blackboard address, so a re-join
     * resubmits the same card and its idempotency key changes only when the statement's truth does.
     */
    private fun tasks(c: Constellation): List<Map<String, Any?>> {
        val prefix = "constellation/${c.name}"
        fun card(address: String, title: String, goal: String, truth: String, priority: Int) = mapOf(
            "jobId" to address, "title" to title, "priority" to priority, "tags" to listOf(LANGUAGE, c.name),
            "idempotencyKey" to "$address#$truth",
            "spec" to "GOAL: $goal\nMUST: Cite the sections under $address.\nSOURCE: $address",
        )
        val conflicts = c.conflicts().take(SAMPLE).map { (x, y) ->
            val a = c.statements[x]; val b = c.statements[y]
            card("$prefix/conflict/${a.id}~${b.id}", "conflict: ${a.sentence} / ${b.sentence}",
                "Decide which force holds for ${a.bearer} ${a.predicate.replace('_', ' ')}.",
                "${c.support[x].cardinality}:${c.support[y].cardinality}", 1)
        }
        val restated = c.restated().filter { c.booksStating(it).size >= RESTATED_TASK }
            .sortedByDescending { c.support[it].cardinality }.take(SAMPLE).map { id ->
                val s = c.statements[id]; val t = c.truth(id)
                card("$prefix/norm/${s.id}", "restated: ${s.sentence}", "Write the norm up for the ${c.name} wiki.",
                    "%.2f/%.2f".format(t.frequency, t.confidence), 2)
            }
        // A premise norms wait on is a question the next reading answers: "Is the rent due?".
        val premises = c.open().entries.sortedByDescending { it.value.cardinality }.take(SAMPLE).map { (p, ids) ->
            card("$prefix/premise/${p.replace(' ', '_')}", "premise: ${question(p)}",
                "Find the sections that state whether ${p.replace(" be ", " is ")}; ${ids.cardinality} norms wait on it.",
                "${ids.cardinality}", 3)
        }
        return conflicts + restated + premises
    }

    /** A premise with a subject and a predicate: a condition of one word ("if authorize") names no proposition to hold. */
    private fun askable(premise: String) = premise.split(' ').size >= 2

    /** A premise in fact form as a yes/no question: "rent be due" → "Is the rent due?". */
    private fun question(premise: String): String {
        val w = premise.split(' ')
        val be = w.indexOf("be")
        return if (be > 0) "Is the ${w.subList(0, be).joinToString(" ")} ${w.subList(be + 1, w.size).joinToString(" ")}?"
        else "Does the ${w.dropLast(1).joinToString(" ")} ${w.last()}?"
    }

    /**
     * `constellation.render`: the return edge. Statements become sentences and the sentences are read
     * back through the parser; a statement whose sentence parses to another key is drift. With `text?`
     * wired the statements are that text's; else the constellation's restated statements. A carried
     * loop over `text` reaches its fixed point when parse ∘ render is idempotent.
     */
    private val render = LcncNodeRunner { node, inputs ->
        val text = (inputs["text"] ?: inputs["text?"]) as? String
        val nlp = CoreNlpRuntime()
        fun read(t: String) = NormClausesNode.sections(t).flatMap { NormClauses.extract(nlp.analyze(it)) }.map { NormStatement.of(it, ::bearerClass) }
        val statements = if (text != null) read(text).distinctBy { it.key } else {
            val name = node.params["constellation"]?.takeIf { it.isNotBlank() } ?: error("constellation.render: name the constellation or wire text")
            constellation(name).let { c -> c.restated().map { c.statements[it] } }
        }
        val drift = statements.mapNotNull { s ->
            val back = read(s.sentence).map { it.key }
            if (s.key in back) null else mapOf("id" to s.id, "sentence" to s.sentence, "key" to s.key, "readBack" to back)
        }
        mapOf("text" to statements.joinToString("\n\n") { it.sentence }, "statements" to statements.size, "drift" to drift)
    }

    /**
     * A saved book's reading into the activity ring, as curation laid it: each section a leaf of the
     * book, each statement a line of the sections that state it, its concepts linked into the shared
     * pool. The concept atom is the last lexicon noun of the bearer and object phrases (the parse's
     * noun tags are not saved with the book).
     */
    private fun replay(trail: borg.trikeshed.lcnc.LcncTrail, run: Int, b: Book) {
        val bySection = Array(b.headings.size) { ArrayList<Int>() }
        for ((i, secs) in b.support.withIndex()) for (k in secs) if (k in bySection.indices) bySection[k].add(i)
        fun atom(phrase: String?): String? = phrase?.split(' ')?.lastOrNull { it.length > 2 && it.all(Char::isLetter) && SumoCorpus.nounClassId(it) >= 0 }
        val keys = sectionKeys(b.headings)
        val crafted = if (SENSES_ENABLED && mem != null) located(b.name).at else null
        for ((k, heading) in keys.withIndex()) {
            // The section's Jev table, where one stands, files each concept under the sense Jev holds for it.
            val sensed = discounting(tableOf(b.name, k))
            val at = trail.node(CURATE, listOf(b.name), heading, "book.section")
            trail.emit(run, borg.trikeshed.lcnc.LcncTrail.Kind.END, at, "${bySection[k].size} statements · ${k + 1}/${b.headings.size}")
            for ((j, i) in bySection[k].withIndex()) {
                val st = b.statements[i]
                val sNode = trail.node(CURATE, listOf(b.name, heading), "s$j", "book.statement")
                trail.emit(run, borg.trikeshed.lcnc.LcncTrail.Kind.END, sNode,
                    listOfNotNull(st.bearer, st.modality.key, st.action.replace('_', ' '), st.obj, st.condition).joinToString(" · "))
                ring(trail, run, sNode, st, atom(st.bearer), atom(st.obj), sensed, crafted)
            }
        }
        for ((from, to) in b.cites) if (from in keys.indices && to in keys.indices)
            trail.link(run, trail.node(CURATE, listOf(b.name), keys[from], "book.section"),
                trail.node(CURATE, listOf(b.name), keys[to], "book.section"), "cites")
    }

    /**
     * Ring keys of a book's sections: the heading bounded for display, and a heading that repeats in the
     * book (two entries both headed "REG") carries its ordinal, so distinct sections never share a node.
     */
    fun sectionKeys(headings: List<String>): List<String> {
        val seen = HashSet<String>()
        return headings.mapIndexed { k, h -> h.take(48).let { if (seen.add(it)) it else "$it #$k" } }
    }

    /**
     * A statement's concepts into the activity ring. Concepts are one pool across every book (root
     * [POOL], one column per SUMO class): the same concept read in two books is one node, so its edges
     * reach into both and common concepts from uncommon sources meet in one place. A concept is filed under the class
     * the overlay types it as where its book was crafted ([at]), else under the sense its section's Jev table holds
     * against the class it would be filed under ([sensed], by lemma), else under that class.
     */
    private fun ring(trail: borg.trikeshed.lcnc.LcncTrail, run: Int, statement: Int, st: NormStatement, bearer: String?, obj: String?,
                     sensed: Map<String, Map<*, *>> = emptyMap(), at: Locality? = null) {
        val sumo = SumoCorpus.classifier
        fun hub(lemma: String, read: Int): Int {
            val learned = at?.let { a -> (typed(lemma.lowercase(), a) ?: typed(lemma.lowercase().removeSuffix("s"), a))?.let { sumo.classId(it.cls)?.value } }
            val j = (sensed[lemma.lowercase()] ?: sensed[lemma.lowercase().removeSuffix("s")])
                ?.takeIf { read >= 0 && it["read"] == sumo.className(SumoClassId(read)) }
            val cls = learned ?: j?.let { sumo.classId(it["sense"].toString())?.value } ?: read
            return trail.node(POOL, listOf(if (cls >= 0) sumo.className(SumoClassId(cls)) else "Unclassified"), lemma, "concept")
        }
        bearer?.let { b -> trail.link(run, statement, hub(b, if (st.bearerClass >= 0) st.bearerClass else SumoCorpus.nounClassId(b)), "subject") }
        obj?.let { o -> trail.link(run, statement, hub(o, SumoCorpus.nounClassId(o)), st.modality.key + " " + st.action.replace('_', ' ')) }
    }

    /**
     * The bearer's SUMO class: the class the sense rete or NARS holds for its head noun where the book was crafted
     * ([at]); else the sense of its head noun its [neighbors] and the [believed] classes support most (preferred sense
     * when both are silent); else its named-entity type.
     */
    private fun bearerClass(c: NormClause, neighbors: Collection<String> = emptyList(), believed: RoaringSeries = RoaringSeries.EMPTY,
                            at: Locality? = null): Int {
        val h = c.head
        at?.let { a -> (typed(h, a) ?: typed(h.removeSuffix("s"), a))?.let { t -> SumoCorpus.classifier.classId(t.cls)?.value?.let { return it } } }
        SumoCorpus.nounSense(h, neighbors, believed).takeIf { it >= 0 }?.let { return it }
        SumoCorpus.nounSense(h.removeSuffix("s"), neighbors, believed).takeIf { it >= 0 }?.let { return it }
        return NER_CLASS[c.ner]?.let { SumoCorpus.classifier.classId(it)?.value } ?: -1
    }

    /** The SUMO class names of [lemma]'s noun senses, as its reading names them: the lemma's own senses, else its singular's. */
    private fun senseOptions(lemma: String): List<String> {
        val sumo = SumoCorpus.classifier
        val ids = SumoCorpus.nounSenses(lemma).takeIf { it.isNotEmpty() } ?: SumoCorpus.nounSenses(lemma.removeSuffix("s"))
        return ids.map { sumo.className(SumoClassId(it)) }.distinct()
    }

    // ── Senses: CoreNLP proposes the word and the lexicon's senses, NARS remembers per locality, Jev judges what NARS
    //    has not myelinated, and a belief at ETERNAL confidence becomes a rule the rete types the word with. ──

    /** Where a book was crafted, and what placed its year: `notes`, `front` (roman-paged front matter), `jev`, `opening` or `none`. */
    class Located(val at: Locality, val year: Int, val by: String, val unmapped: Int, val nouns: Int)

    private val located = HashMap<String, Pair<Long, Located>>()

    /**
     * Where [name] was crafted. Its year: the curation notes' date; else the year its title states
     * ([FrontMatter.titleYear]); else the commonest year, in digits or uppercase roman numerals, of its roman-paged front
     * matter (title page, imprint, preface); else the latest year its Jev tables hold a date of the work's own time; else
     * the commonest year its opening states. Its register: the share of its lowercase nouns the lexicon does not map.
     */
    fun located(name: String): Located {
        val tables = File(books, "${safe(name)}.jev")
        val stamp = bookFile(name).lastModified() * 31 + tables.lastModified()
        synchronized(located) { located[name]?.takeIf { it.first == stamp }?.let { return it.second } }
        // The book's own record, read here rather than loaded: loading a book types its bearers where it is located.
        val record = bookFile(name).takeIf { it.isFile }?.let { reifyMap(it.readText()) }
        val sections = (record?.get("headings") as? List<*>)?.size ?: 0
        fun commonest(ys: IntArray) = ys.toList().groupingBy { it }.eachCount().entries.maxWithOrNull(compareBy({ it.value }, { it.key }))?.key ?: 0
        fun stated(t: String) = FrontMatter.years(t) + FrontMatter.romanYears(t)
        val notes = record?.get("date")?.toString()?.let { Regex("\\b(1[4-9]|20)\\d\\d\\b").find(it)?.value?.toInt() } ?: 0
        val front = constellationOf(name)?.let { File(root.parentFile, "files/$it/$name.extract.md") }?.takeIf { it.isFile }?.let { f ->
            f.bufferedReader().use { r ->
                val buf = CharArray(FrontMatter.FRONT_LIMIT); var n = 0
                while (n < buf.size) { val got = r.read(buf, n, buf.size - n); if (got < 0) break; n += got }
                String(buf, 0, n)
            }
        }?.let(FrontMatter::paged)?.let { commonest(stated(it)) } ?: 0
        val jev = tablesOf(name).flatMap { (_, t) ->
            (t["dates"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
                .filter { ((it["date"] as? Number)?.toDouble() ?: 0.0) >= HOLDS && ((it["late"] as? Number)?.toDouble() ?: 1.0) <= 1 - HOLDS }
                .flatMap { FrontMatter.years(it["tagged"].toString()).toList() }
        }.maxOrNull() ?: 0
        val opening = commonest(stated(texts(name).firstOrNull().orEmpty().take(FrontMatter.FRONT_CHARS)))
        val (year, by) = listOf(notes to "notes", FrontMatter.titleYear(name) to "title", front to "front", jev to "jev", opening to "opening")
            .firstOrNull { it.first > 0 } ?: (0 to "none")
        var nouns = 0; var unmapped = 0
        // The register from a sample of the book's readings, at most REGISTER_SECTIONS of them spread evenly.
        for (k in 0 until sections step maxOf(1, sections / REGISTER_SECTIONS)) readingFile(name, k).takeIf { it.isFile }?.let { f ->
            for (s in reify(f.readText()) as? List<*> ?: emptyList<Any?>()) for (c in ((s as? Map<*, *>)?.get("concepts") as? List<*>).orEmpty()) {
                val m = c as? Map<*, *> ?: continue
                val w = m["word"]?.toString().orEmpty()
                if (w.length < 3 || !w.all { it in 'a'..'z' }) continue
                nouns++; if (m["class"] == null) unmapped++
            }
        }
        val out = Located(Locality.of(year, Register.of(unmapped, nouns)), year, by, unmapped, nouns)
        synchronized(located) { located[name] = stamp to out }
        return out
    }

    /**
     * Section [k]'s sense judgments into [m], each one judgment of its word in book [name], crafted in [at], under the
     * source its table names (a window of the section). A row without the judge's whole distribution over its menu (a
     * table kept before distributions were) is not a menu choice and is not read.
     */
    private fun observe(m: SenseMemory, name: String, k: Int, at: Locality, table: Map<*, *>): Int {
        if (!SENSES_ENABLED) return 0
        var n = 0
        for (row in (table["senses"] as? List<*>).orEmpty()) {
            val r = row as? Map<*, *> ?: continue
            val lemma = r["lemma"]?.toString() ?: continue
            val judged = (r["probabilities"] as? Map<*, *>)?.mapNotNull { (o, p) -> (p as? Number)?.let { o.toString() to it.toDouble() } }?.toMap() ?: continue
            if (m.observe(lemma, at, name, r["source"]?.toString() ?: cid("$name\u0000$k\u0000${r["sentence"]}"), judged)) n++
        }
        return n
    }

    /** Every member book's tables into [m]: what Jev already answered primes NARS. */
    private fun prime(m: SenseMemory) {
        for (f in root.listFiles { x -> x.isFile && x.name.endsWith(".members") }.orEmpty())
            for (name in f.readLines().filter { it.isNotBlank() }.distinct()) {
                val tables = tablesOf(name).takeIf { it.isNotEmpty() } ?: continue
                val at = located(name).at
                for ((k, t) in tables) observe(m, name, k, at, t)
            }
    }

    /** [m] to [senseFile], whole or not at all. */
    private fun save(m: SenseMemory) {
        if (!SENSES_ENABLED) return
        val tmp = File(root, "senses.bag.tmp").apply { writeText(m.write()) }
        java.nio.file.Files.move(tmp.toPath(), senseFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Push and pull between the sense rows and the rete. Push: where a word is read, its row ([SenseMemory.sense]) at
     * confidence T/(T + K/2) ≥ 49/50 whose leading class, none-of-these aside, holds a share ≥ 7/10 is minted an eternal
     * rule `<(&&,lemma,locality) ==> class>`, the condition sequence it was held under, with that class's evidence as
     * one NARS belief. Both tests are integer planes on the row's counts ([SenseRow.eternal], [SenseRow.holds]), so K,
     * the classes the judge was offered, sets the evidence a word needs: 24.5 judgments per class. Pull: a rule whose
     * row has moved is taken back — retracted when its class no longer leads or no longer holds, or its word is no
     * longer read there; revised when the class still leads at eternal confidence under other evidence. Every change is
     * filed in the rules ledger (a revision is a retraction followed by the revised rule) and swapped into the live rete;
     * the overlay is replanted.
     */
    private fun myelinate(m: SenseMemory): Myelinated {
        if (!SENSES_ENABLED) return Myelinated(emptyList(), emptyList(), emptyList())
        val minted = ArrayList<EternalRule>(); val revised = ArrayList<EternalRule>(); val retracted = ArrayList<EternalRule>()
        val byTerm = senseRules.associateBy { it.antecedent }
        val read = HashSet<String>()
        for ((lemma, at) in m.held()) {
            val term = condition(lemma, at)
            read.add(term)
            val row = m.sense(lemma, at) ?: continue
            val t = row.top
            val cls = if (t >= 0) m.className(row.classes[t]) else NONE
            // A production types its word without asking: only a decisive row earns one. Confidence says how much
            // evidence there is for K classes; the share says whether it agrees. A row at 98% confidence split 51/49 stays.
            val holds = t >= 0 && row.holds(t)
            val decisive = holds && row.eternal()
            val e = if (t >= 0) row.evidence(t) else EvidenceCoord.EMPTY
            val old = byTerm[term]
            when {
                old == null -> if (decisive) minted.add(EternalRule(term, cls, NalCopula.IMPLICATION, e, SENSES))
                !holds || old.consequent != cls -> retracted.add(old)
                decisive && old.evidence != e -> { retracted.add(old); revised.add(EternalRule(term, cls, NalCopula.IMPLICATION, e, SENSES)) }
            }
        }
        // A rule whose word is no longer read where it holds (its book was placed elsewhere, its row forgotten) is taken back.
        for (r in senseRules) if (r.antecedent !in read) retracted.add(r)
        if (minted.isEmpty() && retracted.isEmpty()) return Myelinated(minted, revised, retracted)
        val gone = retracted.map { it.antecedent }.toHashSet()
        for (r in retracted) NarsDurableLedger.appendRetraction(forge, r)
        for (r in minted + revised) NarsDurableLedger.appendRule(forge, r)
        senseRules.removeAll { it.antecedent in gone }
        senseRules.addAll(minted + revised)
        liveRete?.retract { it.provenanceCid == SENSES && it.antecedent in gone }
        liveRete?.admit((minted + revised).toSeries())
        replant()
        return Myelinated(minted, revised, retracted.filter { r -> revised.none { it.antecedent == r.antecedent } })
    }

    /** One push-pull pass: rules minted, rules revised in place, rules retracted outright. */
    class Myelinated(val minted: List<EternalRule>, val revised: List<EternalRule>, val retracted: List<EternalRule>)

    /** A sense belief's condition: the word read in a locality. */
    private fun condition(lemma: String, at: Locality) = ConceptTree.condition(lemma, at)

    /** The myelinated rule typing [lemma] in [at], or null. */
    private fun rule(lemma: String, at: Locality): EternalRule? = if (!SENSES_ENABLED) null else tree.production(lemma, at)

    /** The class [lemma] denotes in [at]; null when neither a rule nor a belief holds one, and the lexicon's sense stands. */
    private fun typed(lemma: String, at: Locality): ConceptTree.Typed? = if (!SENSES_ENABLED) null else tree.typed(lemma, at)

    /** The class the overlay types [lemma] as where [book] was crafted, by name; null when it types it nowhere there. */
    fun conceptClass(book: String, lemma: String): String? =
        if (!SENSES_ENABLED || mem == null) null else located(book).at.let { at -> (typed(lemma, at) ?: typed(lemma.removeSuffix("s"), at))?.cls }

    /** True when Jev holds, somewhere the word was read, that none of the senses offered for [lemma] is the one it is used in. */
    private fun refused(lemma: String): Boolean = (mem?.let { m -> synchronized(m) { m.contexts(lemma) } }).orEmpty().any { (_, r) ->
        r.none >= 0 && r.holds(r.none)
    }

    /**
     * Classes posited by NAL abduction for the nouns of [c] the lexicon cannot type, or whose offered senses Jev refused:
     * a head that bears the verbs typed bearers bear is posited to be of their classes. Per verb, `<head --> [v]>` and
     * `<class --> [v]>` abduce `<head --> class>`; each shared verb's abduction revises the posit, a typed bearer's verb
     * counts for its class and every class above it, and classes too broad to say anything ([POSIT_INFORMATION]) are
     * not posited. Ranked by positive evidence, at most [POSITS] per head.
     */
    private fun posits(c: Constellation): Map<String, List<String>> {
        if (!SENSES_ENABLED) return emptyMap()
        val sumo = SumoCorpus.classifier
        val byVerb = HashMap<String, HashMap<Int, Int>>(); val classCount = HashMap<Int, Int>()
        val headVerb = HashMap<String, HashMap<String, Int>>(); val headCount = HashMap<String, Int>()
        for (s in c.statements) {
            if (s.bearerClass >= 0) SumoCorpus.closure(s.bearerClass).forEach { a ->
                byVerb.getOrPut(s.action) { HashMap() }.merge(a, 1, Int::plus); classCount.merge(a, 1, Int::plus)
            }
            val head = s.bearer.substringAfterLast(' ')
            if (head.length < 3 || !head.all { it in 'a'..'z' }) continue
            if (senseOptions(head).isNotEmpty() && !refused(head)) continue
            headVerb.getOrPut(head) { HashMap() }.merge(s.action, 1, Int::plus); headCount.merge(head, 1, Int::plus)
        }
        val out = HashMap<String, List<String>>()
        for ((head, verbs) in headVerb) {
            val n = headCount.getValue(head)
            val posit = HashMap<Int, EvidenceCoord>()
            for ((v, nv) in verbs) {
                val own = Nal.truthOf(EvidenceCoord(nv * Nal.UNIT, (n - nv) * Nal.UNIT))
                for ((cls, ncv) in byVerb[v].orEmpty()) {
                    if (SumoCorpus.informationOf(cls) < POSIT_INFORMATION) continue
                    val theirs = Nal.truthOf(EvidenceCoord(ncv * Nal.UNIT, (classCount.getValue(cls) - ncv) * Nal.UNIT))
                    posit[cls] = revise(posit[cls] ?: EvidenceCoord.EMPTY, Nal.abduce(theirs, own))
                }
            }
            posit.entries.sortedByDescending { it.value.positive }.take(POSITS).map { sumo.className(SumoClassId(it.key)) }
                .takeIf { it.isNotEmpty() }?.let { out[head] = it }
        }
        return out
    }

    /** The sense memory as a report: per [lemma] (or the rows nearest myelination when none), its rows where [book] was crafted. */
    fun senses(lemma: String?, book: String?): Map<String, Any?> {
        if (!SENSES_ENABLED) return mapOf("enabled" to false)
        val at = book?.takeIf { it.isNotBlank() }?.let { located(it) }
        fun truth(e: EvidenceCoord) = Nal.truthOf(e).let { mapOf("f" to it.frequency, "c" to it.confidence, "e" to it.expectation()) }
        val books = root.listFiles { x -> x.isFile && x.name.endsWith(".members") }.orEmpty().flatMap { it.readLines() }.filter { it.isNotBlank() }.distinct()
            .associateWith { located(it).let { l -> mapOf("at" to l.at.term, "year" to l.year, "by" to l.by, "unmapped" to l.unmapped, "nouns" to l.nouns) } }
        val memory = memory()
        return synchronized(memory) {
            // A row as read: its K classes, T judgments and confidence T/(T + K/2), and per class its share, most first.
            fun row(r: SenseRow) = mapOf("K" to r.width, "T" to r.mass.toDouble() / Nal.UNIT, "c" to r.confidence, "eternal" to r.eternal(),
                "classes" to (0 until r.width).sortedByDescending { r.share[it] }.map { i ->
                    mapOf("class" to memory.className(r.classes[i]), "share" to r.share[i], "holds" to r.holds(i), "offered" to r.everywhere(i)) })
            if (lemma.isNullOrBlank()) {
                val near = memory.held().mapNotNull { (l, a) -> memory.sense(l, a)?.let { r -> Triple(condition(l, a), r, r.top) } }
                    .filter { it.third >= 0 && it.second.holds(it.third) }.sortedByDescending { it.second.confidence }
                val overlay = tree
                mapOf("rows" to memory.rows, "judgments" to memory.judgments, "cells" to memory.size, "contexts" to memory.contexts, "rules" to senseRules.size,
                    "at" to listOf(0.98, 0.9, 0.7, 0.5).associate { t -> "c≥$t" to near.count { it.second.confidence >= t } },
                    "nearest" to near.take(SAMPLE).map { (term, r, t) -> mapOf("condition" to term, "class" to memory.className(r.classes[t]),
                        "share" to r.share[t], "K" to r.width, "T" to r.mass.toDouble() / Nal.UNIT, "c" to r.confidence) },
                    "myelinated" to senseRules.takeLast(SAMPLE).map { mapOf("condition" to it.antecedent, "class" to it.consequent) + truth(it.evidence) },
                    // Productions agreeing across localities whose rows cannot be told apart: one locality-free production could stand for them all.
                    "factors" to overlay.factors().sortedByDescending { it.localities.size }.take(SAMPLE).map { f ->
                        mapOf("lemma" to f.lemma, "class" to f.cls, "localities" to f.localities.map { it.term }) + truth(f.evidence) },
                    // Productions of one word disagreeing across localities whose rows differ: its sense moved with time or English.
                    "drift" to overlay.drift().take(SAMPLE).map { d ->
                        mapOf("lemma" to d.lemma, "classes" to d.classes.map { (a, cls) -> mapOf("at" to a.term, "class" to cls) }) },
                    "books" to books)
            } else mapOf("lemma" to lemma, "at" to at?.at?.term, "typed" to at?.let { typed(lemma, it.at) }?.let { mapOf("class" to it.cls, "e" to it.e, "by" to it.by) },
                // The super tree's closure the word carries where the book was crafted: the overlay's class and every class above it.
                "is" to at?.let { a -> tree.closure(lemma, a.at).toIntArray().sortedByDescending { SumoCorpus.informationOf(it) }.take(6).map { ConceptTree.name(it) } },
                "sense" to at?.let { memory.sense(lemma, it.at)?.let(::row) },
                "contexts" to memory.contexts(lemma).map { (a, r) ->
                    mapOf("at" to a.term, "judgments" to r.judgments, "rule" to rule(lemma, a)?.consequent) + row(r)
                },
                "books" to books)
        }
    }

    /**
     * Typed atoms in the bank, replaced per constellation: `(norm Id Modality Class "predicate" "f" "c")`
     * with the bearer as a SUMO class symbol, and `(normSource Id "book" section)` per supporting section.
     */
    private fun tell(bank: KifKnowledgeBase, c: Constellation, ids: List<Int>) {
        val sumo = SumoCorpus.classifier
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val atom = "${c.name.replace(Regex("[^A-Za-z0-9]+"), "")}Norm"
        val prior = told.getOrPut(c.name) { ArrayList() }
        val next = ArrayList<String>()
        for (id in ids) {
            val s = c.statements[id]; val t = c.truth(id)
            val cls = if (s.bearerClass >= 0) sumo.className(SumoClassId(s.bearerClass)) else "Entity"
            val sym = atom + s.id
            next.add("(norm $sym ${s.modality.name} $cls ${q(s.predicate)} ${q("%.2f".format(t.frequency))} ${q("%.2f".format(t.confidence))})")
            c.support[id].toIntArray().take(SECTIONS_PER_NORM).forEach { g ->
                next.add("(normSource $sym ${q(c.books[g ushr 20].name)} ${g and 0xFFFFF})")
            }
        }
        // The books' own cross-references, section to section: what the prose points at.
        for (b in c.books) for ((from, to) in b.cites) next.add("(cites ${q(b.name)} $from $to)")
        bank.replace(prior.map { borg.trikeshed.kif.KifExpr.parse(it) }, next.map { borg.trikeshed.kif.KifExpr.parse(it) })
        prior.clear(); prior.addAll(next)
    }

    private val told = HashMap<String, MutableList<String>>()

    /**
     * RDF concept atoms as Turtle: each restated statement typed by its bearer's SUMO class, its force,
     * predicate and condition, one quad per supporting section with the section as the named graph.
     */
    private fun rdf(c: Constellation): String {
        val sumo = SumoCorpus.classifier
        val ns = "${RdfVocab.FORGE}constellation/${c.name}/"
        fun p(local: String) = RdfVocab.forge(local)
        val quads = ArrayList<RdfQuad>()
        for (id in c.restated().take(NORMS)) {
            val s = c.statements[id]; val t = c.truth(id)
            val n = RdfTerm.Iri(ns + "norm/" + s.id)
            fun add(pr: RdfTerm.Iri, o: RdfTerm, g: RdfTerm.Iri? = null) { quads.add(RdfQuad(n, pr, o, g)) }
            add(RdfVocab.rdf("type"), p("Norm"))
            if (s.bearerClass >= 0) add(p("bearerClass"), RdfVocab.sumo(sumo.className(SumoClassId(s.bearerClass))))
            add(p("bearer"), RdfTerm.literal(s.bearer))
            add(p("modality"), p(s.modality.name))
            add(p("predicate"), RdfTerm.literal(s.predicate))
            s.condition?.let { add(p("condition"), RdfTerm.literal(it)) }
            add(p("frequency"), RdfTerm.Literal(t.frequency.toString(), RdfVocab.XSD + "float"))
            add(p("confidence"), RdfTerm.Literal(t.confidence.toString(), RdfVocab.XSD + "float"))
            c.opponents(id).forEach { o -> add(p("opposedBy"), RdfTerm.Iri(ns + "norm/" + c.statements[o].id)) }
            c.support[id].toIntArray().take(SECTIONS_PER_NORM).forEach { g ->
                add(p("statedIn"), RdfTerm.literal(c.books[g ushr 20].name),
                    RdfTerm.Iri(ns + "section/" + c.books[g ushr 20].name.replace(' ', '_') + "/" + (g and 0xFFFFF)))
            }
        }
        return TurtleRdf.emit(RdfGraph(emptyList(), quads))
    }

    private fun sectionRef(c: Constellation, g: Int): Map<String, Any?> {
        val b = c.books[g ushr 20]
        return mapOf("book" to b.name, "section" to b.headings.getOrElse(g and 0xFFFFF) { "#${g and 0xFFFFF}" })
    }

    private fun normEntry(c: Constellation, id: Int): Map<String, Any?> {
        val t = c.truth(id)
        return c.statements[id].toMap() + mapOf(
            "books" to c.booksStating(id).map { c.books[it].name },
            "frequency" to t.frequency, "confidence" to t.confidence,
            "sections" to c.support[id].toIntArray().take(SECTIONS_PER_NORM).map { sectionRef(c, it) },
            "sectionCount" to c.support[id].cardinality,
            "opposedBy" to c.opponents(id).map { c.statements[it].id },
        )
    }

    /**
     * Summaries and addresses only; bounded per kind so a large constellation cannot flood the board.
     * Returns the frontier: statements new since the last publish, restated norms with no SUMO
     * typing, and conflicts, as blackboard addresses. An empty frontier is the fixed point.
     */
    private fun publish(c: Constellation): Map<String, Any?> {
        val prefix = "constellation/${c.name}"
        val before = (blackboard.get("$prefix/frontier") as? Map<*, *>)?.get("statements") as? Number
        blackboard.snapshot().values.keys.filter { it.startsWith("$prefix/") }.forEach { blackboard.remove(it) }
        val restated = c.restated().sortedByDescending { c.support[it].cardinality }
        val conflicts = c.conflicts()
        val bearers = c.statements.indices.groupBy { c.statements[it].bearer }.entries.sortedByDescending { it.value.size }
        blackboard.put(prefix, mapOf(
            "books" to c.books.map { mapOf("name" to it.name, "work" to it.work, "date" to it.date) },
            "statements" to c.statements.size, "restated" to restated.size, "conflicts" to conflicts.size,
            "admissible" to c.admissible().size,
            "topBearers" to bearers.take(20).map { mapOf("bearer" to it.key, "statements" to it.value.size) },
        ), LANGUAGE)
        for ((i, b) in c.books.withIndex()) {
            val mine = c.statementsOf[i].toIntArray()
            blackboard.put("$prefix/book/${b.name}", mapOf(
                "work" to b.work, "date" to b.date, "sections" to b.headings.size, "statements" to mine.size,
                "byModality" to Modality.entries.associate { m -> m.key to mine.count { c.statements[it].modality == m } },
                "topBearers" to mine.groupBy { c.statements[it].bearer }.entries.sortedByDescending { it.value.size }.take(15)
                    .map { mapOf("bearer" to it.key, "statements" to it.value.size) },
            ), LANGUAGE)
        }
        for (a in c.books.indices) for (b in a + 1 until c.books.size) {
            val l = c.link(a, b, conflicts)
            if (l.shared == 0 && l.conflicts == 0) continue
            val key = "${c.books[a].name}~${c.books[b].name}"
            val aligned = c.alignments[key]
            blackboard.put("$prefix/link/$key", mapOf(
                "shared" to l.shared, "jaccard" to l.jaccard, "conflicts" to l.conflicts,
                "edition" to aligned?.counts?.mapKeys { it.key.name.lowercase() },
                "sample" to (c.statementsOf[a] and c.statementsOf[b]).toIntArray()
                    .sortedByDescending { c.support[it].cardinality }.take(SAMPLE).map { c.statements[it].id },
                "changes" to aligned?.sections?.filter { it.change != Constellation.Change.KEPT }?.take(SAMPLE)?.map { s ->
                    mapOf("change" to s.change.name.lowercase(),
                        "from" to c.books[a].headings.getOrNull(s.from), "to" to c.books[b].headings.getOrNull(s.to), "shared" to s.shared)
                },
            ), LANGUAGE)
        }
        for (id in restated.take(NORMS)) blackboard.put("$prefix/norm/${c.statements[id].id}", normEntry(c, id), LANGUAGE)
        // The join's open questions: each premise and the norms waiting on it, asked as a sentence.
        for ((p, ids) in c.open().entries.sortedByDescending { it.value.cardinality }.take(NORMS))
            blackboard.put("$prefix/premise/${p.replace(' ', '_')}", mapOf(
                "premise" to p, "question" to question(p),
                "norms" to ids.toIntArray().map { "$prefix/norm/${c.statements[it].id}" },
            ), LANGUAGE)
        for ((x, y) in conflicts.take(NORMS)) blackboard.put("$prefix/conflict/${c.statements[x].id}~${c.statements[y].id}",
            mapOf("a" to normEntry(c, x), "b" to normEntry(c, y)), LANGUAGE)
        for ((bearer, ids) in bearers.take(BEARERS)) blackboard.put("$prefix/bearer/${bearer.replace(' ', '_')}",
            mapOf("norms" to ids.sortedByDescending { c.support[it].cardinality }.take(SAMPLE).map { id ->
                c.statements[id].toMap() + mapOf("books" to c.booksStating(id).size, "sections" to c.support[id].cardinality)
            }, "statements" to ids.size), LANGUAGE)
        val sumo = SumoCorpus.classifier
        for ((cls, ids) in c.bearing.entries.sortedByDescending { it.value.cardinality }.take(BEARERS)) {
            // Norms held down SUMO: stated of the class, or of an ancestor and deduced through is-a.
            val held = c.held(cls, SumoCorpus.closure(cls))
            blackboard.put("$prefix/class/${sumo.className(SumoClassId(cls))}", mapOf(
                "statements" to ids.cardinality, "held" to held.size,
                "norms" to held.sortedByDescending { Nal.truthOf(it.belief).confidence }.take(SAMPLE).map { h ->
                    val t = Nal.truthOf(h.belief)
                    mapOf("predication" to c.predicationNames[h.predication], "via" to sumo.className(SumoClassId(h.via)),
                        "frequency" to t.frequency, "confidence" to t.confidence,
                        "norms" to h.statements.map { "$prefix/norm/${c.statements[it].id}" })
                },
            ), LANGUAGE)
        }
        val untyped = restated.filter { c.statements[it].bearerClass < 0 }
        val frontier = mapOf(
            "statements" to c.statements.size,
            "new" to c.statements.size - (before?.toInt() ?: 0),
            "untyped" to untyped.take(SAMPLE).map { c.statements[it].bearer }.distinct(),
            "conflicts" to conflicts.take(SAMPLE).map { (x, y) -> "$prefix/conflict/${c.statements[x].id}~${c.statements[y].id}" },
            "restated" to restated.take(SAMPLE).map { "$prefix/norm/${c.statements[it].id}" },
            "open" to c.open().keys.take(SAMPLE).map { "$prefix/premise/${it.replace(' ', '_')}" },
            "answered" to c.premised.keys.filter { it in c.facts }.take(SAMPLE),
        )
        blackboard.put("$prefix/frontier", frontier, LANGUAGE)
        return frontier
    }

    companion object {
        /** The language lane's LCNC nodes, registered in one call so the daemon's boot method stays under the JVM method limit. */
        fun registerLanguageNodes(ctx: borg.trikeshed.module.ModuleContext, bank: borg.trikeshed.kif.KifKnowledgeBase,
                                  liveRete: CausalityReteElement? = null): ConstellationNodes {
            SkillCurateNode.register(ctx.lcncRunners, bank)
            NlRulesNode.register(ctx.lcncRunners, bank)
            SkillOverlapNode.register(ctx.lcncRunners, bank)
            NormClausesNode.register(ctx.lcncRunners, bank)
            val nodes = ConstellationNodes(ctx.stateDir, ctx.blackboard, bank, liveRete).also { it.register(ctx.lcncRunners) }
            // The ring is in memory: saved constellations stand in it again without waiting for a join.
            Thread({ runCatching { nodes.restore() }.onFailure { System.err.println("[OROBOROS] constellation restore failed: $it") } },
                "constellation-restore").apply { isDaemon = true }.start()
            // The sense memory: `?lemma=<noun>&book=<name>` reads a word's beliefs per locality and how it is typed where
            // the book was crafted; bare, the memory's size, the beliefs nearest myelination and the rules myelinated.
            ctx.routes.claim("language", "/api/curation/senses") { method, path, _, _ ->
                if (method != "GET") return@claim borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
                val q = borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
                borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(200, jsonOf(nodes.senses(q["lemma"], q["book"])))
            }
            // One curated section read whole: `?book=<name>&section=<ordinal or heading>`.
            ctx.routes.claim("language", "/api/curation/section") { method, path, _, _ ->
                if (method != "GET") return@claim borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
                val q = borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
                val out = nodes.section(q["book"].orEmpty(), q["section"].orEmpty())
                borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(if (out.containsKey("error")) 404 else 200, jsonOf(out))
            }
            // Jev rides the HTX reactor in the module's context; its key resolves through the operator KeyMux.
            borg.trikeshed.userspace.nio.channels.spi.EgressAllowlist.allowUrl(Jev.BASE)
            val jev = ctx.muxContext.minusKey(kotlinx.coroutines.Job)
            suspend fun jevKey(): String? = kotlinx.coroutines.withContext(jev) {
                kotlinx.coroutines.currentCoroutineContext()[borg.trikeshed.userspace.reactor.MuxReactorElement]?.keyMux()?.get("JEV_API_KEY")
            }
            // Jev over the corpus the open page's constellation reaches: `{book, section, question}` in, the oracle's reading out.
            ctx.routes.claim("language", "/api/curation/jev") { method, _, text, _ ->
                if (method != "POST") return@claim borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
                val q = borg.trikeshed.parse.reifyMap(text.substringAfter("\r\n\r\n", text))
                val out = kotlinx.coroutines.withContext(jev) {
                    val key = jevKey() ?: return@withContext mapOf("error" to "JEV_API_KEY is not set")
                    runCatching { nodes.oracle(key, q["book"].toString(), (q["section"] as? Number)?.toInt() ?: 0, q["question"].toString()) }
                        .getOrElse { mapOf("error" to (it.message ?: it.toString())) }
                }
                borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(if (out.containsKey("error")) 502 else 200, jsonOf(out))
            }
            // Jev's tables over every section of a constellation's books, or of `books`: POST `{constellation}` starts
            // the run in the background; GET reads each book's progress (sections tabled, of how many, tokens).
            ctx.routes.claim("language", "/api/curation/jev/score") { method, _, text, _ ->
                when (method) {
                    "GET" -> borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(200, jsonOf(nodes.scored.values.toList()))
                    "POST" -> {
                        val q = borg.trikeshed.parse.reifyMap(text.substringAfter("\r\n\r\n", text))
                        val names = (q["books"] as? List<*>)?.map { it.toString() } ?: nodes.members(q["constellation"].toString())
                        val key = jevKey()
                        when {
                            names.isEmpty() -> borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(404, jsonOf(mapOf("error" to "no books to score")))
                            key == null -> borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(502, jsonOf(mapOf("error" to "JEV_API_KEY is not set")))
                            else -> {
                                ctx.scope.launch(jev + kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching { nodes.score(key, names) }.onFailure { System.err.println("[JEV] scoring failed: $it") }
                                }
                                borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(202, jsonOf(mapOf("books" to names)))
                            }
                        }
                    }
                    else -> borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
                }
            }
            // The curator's search: `{q, constellation?, book?, depth, limit}` over the curated leaves, n deep. Jev orders
            // what it found once the startup question came back as known: GET the verdict, POST `{q, rows}` to rank.
            val find = CurationFind(nodes)
            ctx.scope.launch(jev + kotlinx.coroutines.Dispatchers.IO) { find.probe { jevKey() } }
            ctx.routes.claim("language", "/api/curation/find") { method, _, text, _ ->
                if (method != "POST") return@claim borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
                val q = borg.trikeshed.parse.reifyMap(text.substringAfter("\r\n\r\n", text))
                val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    find.find(q["q"]?.toString().orEmpty(), q["constellation"]?.toString(), q["book"]?.toString(),
                        ((q["depth"] as? Number)?.toInt() ?: 3).coerceIn(1, 4), ((q["limit"] as? Number)?.toInt() ?: 60).coerceIn(1, 500))
                }
                borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(200, jsonOf(out))
            }
            ctx.routes.claim("language", "/api/curation/rank") { method, _, text, _ ->
                when (method) {
                    "GET" -> borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(200, jsonOf(find.gate))
                    "POST" -> {
                        val gate = find.gate
                        if (gate["ready"] != true) return@claim borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(503, jsonOf(gate))
                        val q = borg.trikeshed.parse.reifyMap(text.substringAfter("\r\n\r\n", text))
                        val out = kotlinx.coroutines.withContext(jev) {
                            val key = jevKey() ?: return@withContext mapOf("error" to "JEV_API_KEY is not set")
                            runCatching { find.rank(key, q["q"]?.toString().orEmpty(), (q["rows"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }) }
                                .getOrElse { mapOf("error" to (it.message ?: it.toString())) }
                        }
                        borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(if (out.containsKey("error")) 502 else 200, jsonOf(out))
                    }
                    else -> borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
                }
            }
            return nodes
        }

        const val CURATE = "book.curate"
        /** Conflict bridges drawn per join. */
        const val CONFLICT_LINKS = 600
        /** Sections read whole kept for the next reader. */
        const val PARSED = 256
        /** Questions a page offers; passages, norms and premises one oracle request carries. */
        const val QUESTIONS = 6
        const val PASSAGES = 12
        const val PASSAGE_CHARS = 2400
        /** Windows of the open page an oracle request always carries. */
        const val OPEN_PASSAGES = 2
        const val NORMS_ASKED = 24
        const val PREMISES_ASKED = 8
        const val RANKED = 8
        /** The noul at which a premise the question presupposes joins the facts. */
        const val HOLDS = 0.7
        /** A Jev table's window: whole sentences up to this many characters, at most this many tuples and dates asked. */
        const val WINDOW_CHARS = 6_000
        const val TUPLES_ASKED = 40
        const val DATES_ASKED = 12
        /** Ambiguous nouns a Jev table window asks the sense of. */
        const val SENSES_ASKED = 96
        /** The confidence at which a sense belief is myelinated into an eternal rule: one in fifty. */
        const val ETERNAL = 0.98f
        /** One myelinated sense in this many is put to Jev again, an audit of the rule. */
        const val AUDIT = 50
        /** The option a sense question offers for a use none of the listed senses names. */
        const val NONE = ConceptTree.NONE
        /** Provenance of the eternal rules the sense memory myelinates. */
        const val SENSES = "constellation-senses"
        /** Sense rules are minted, revised and retracted (see [myelinate]), so the lane runs. */
        const val SENSES_ENABLED = true
        /** Classes abduction posits per untyped noun, and the least information content a posited class carries. */
        const val POSITS = 4
        const val POSIT_INFORMATION = 2.5f
        /** Readings a book's register is measured over, spread evenly across its sections. */
        const val REGISTER_SECTIONS = 64
        /** Open premises one table window carries. */
        const val PREMISES_TABLED = 24
        /** The close of a long section shown beside its opening, for the whole-section question. */
        const val END_CHARS = 600
        /** Jev requests in flight at once, across every section a run tables. */
        const val JEV_AT_ONCE = 48
        const val JOIN = "constellation.join"
        const val RENDER = "constellation.render"
        const val WIKI_READ = "wiki.read"
        const val ASK = "constellation.ask"
        const val SETTLE = "constellation.settle"
        const val SELECT = "rdf.select"
        const val BRIEF = "constellation.brief"
        const val LANGUAGE = "constellation"
        /** The activity ring's root for the concept pool every curated book reads into. */
        const val POOL = "concepts"
        const val NORMS = 200
        const val BEARERS = 50
        const val SAMPLE = 25
        const val SECTIONS_PER_NORM = 12
        /** Books a norm must be stated in before it becomes a wiki task. */
        const val RESTATED_TASK = 3
        /** CoreNLP entity type → SUMO class, for bearers the noun lexicon misses (proper names). */
        val NER_CLASS = mapOf("PERSON" to "Human", "ORGANIZATION" to "Organization", "LOCATION" to "GeographicArea",
            "CITY" to "City", "COUNTRY" to "Nation", "STATE_OR_PROVINCE" to "StateOrProvince", "NATIONALITY" to "GroupOfPeople")
    }
}
