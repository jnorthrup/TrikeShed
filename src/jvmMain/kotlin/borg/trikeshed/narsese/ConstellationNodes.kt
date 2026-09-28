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
class ConstellationNodes(stateDir: File, private val blackboard: ConfixBlackboard, private val bank: KifKnowledgeBase? = null) {
    private val wiki = File(stateDir, "wiki")
    private val root = File(stateDir, "constellations").apply { mkdirs() }
    private val books = File(root, "books").apply { mkdirs() }
    private val live = HashMap<String, Constellation>()

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

    private fun load(name: String): Book? {
        val f = bookFile(name).takeIf { it.isFile } ?: return null
        val stamp = f.lastModified() * 31 + f.length()
        synchronized(loaded) { loaded[name]?.takeIf { it.first == stamp }?.let { return it.second } }
        return read(f)?.also { b -> synchronized(loaded) { loaded[name] = stamp to b } }
    }

    private fun read(f: File): Book? {
        val m = reifyMap(f.readText())
        fun str(v: Any?) = v?.toString()?.takeIf { it.isNotEmpty() }
        return Book(
            m["name"].toString(), m["work"].toString(), m["date"].toString(),
            (m["headings"] as List<*>).map { it.toString() },
            (m["statements"] as List<*>).map { s ->
                s as Map<*, *>
                NormStatement(s["bearer"].toString(), Modality.entries.first { it.key == s["modality"] },
                    s["action"].toString(), str(s["object"]), str(s["condition"]), (s["bearerClass"] as? Number)?.toInt() ?: -1)
            },
            (m["support"] as List<*>).map { l -> (l as List<*>).map { (it as Number).toInt() }.toIntArray() },
            (m["facts"] as? List<*>)?.map { it.toString() }?.toSet() ?: emptySet(),
            (m["cites"] as? List<*>)?.map { p -> (p as List<*>).let { (it[0] as Number).toInt() to (it[1] as Number).toInt() } } ?: emptyList(),
        )
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
        // Pointcuts into the activity ring: the book is a trunk under the run, each section a leaf that
        // begins at its parse and ends with what it yielded, so /curator and /api/lcnc/trail show the reading.
        val trail = borg.trikeshed.lcnc.LcncTrail.live
        val run = trail.lastRun()
        val bookNode = listOf("book.curate", name)
        val secNodes = IntArray(sections.size) { -1 }
        val secKeys = sectionKeys(sections.map(Book::heading))
        val clauses = CoreNlpRuntime().use { nlp -> sections.mapIndexed { k, sec ->
            if (k % 25 == 0) System.err.println("[CURATE] $name: section $k/${sections.size}, ${(System.currentTimeMillis() - began) / 1000}s")
            val at = trail.node(bookNode[0], listOf(bookNode[1]), secKeys[k], "book.section")
            secNodes[k] = at
            trail.emit(run, borg.trikeshed.lcnc.LcncTrail.Kind.BEGIN, at)
            val t0 = System.currentTimeMillis()
            val doc = nlp.analyze(sec)
            val found = NormClauses.extract(doc, generic)
            trail.emit(run, borg.trikeshed.lcnc.LcncTrail.Kind.END, at,
                "${found.size} statements, ${doc.sentences.a} sentences, ${sec.length} chars, ${System.currentTimeMillis() - t0}ms · ${k + 1}/${sections.size}")
            // The section's other nouns are the context a bearer's sense is read in.
            val nouns = doc.sentences.values().flatMap { s -> s.tokens.values().filter { it.tag.startsWith("NN") }.map { it.lemma.lowercase() } }.distinct()
            for (c in found) typed[c] = bearerClass(c, nouns, believed)
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
                ring(trail, run, sNode, st, atom(st.bearer), atom(st.obj))
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
            ?: b.headings.indexOfFirst { it.startsWith(section) }.takeIf { it >= 0 }
            ?: return mapOf("error" to "no section '$section' in '$bookName'")
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
            val pos = same.filter { b.statements[it].modality == s.modality }.sumOf { b.support[it].size }
            val neg = same.filter { b.statements[it].modality.opposes(s.modality) }.sumOf { b.support[it].size }
            val t = Nal.truthOf(EvidenceCoord(pos * Nal.UNIT.toLong(), neg * Nal.UNIT.toLong()))
            return linkedMapOf("id" to s.id, "sentence" to s.sentence, "bearer" to s.bearer, "force" to s.modality.key,
                "action" to s.action, "object" to s.obj, "condition" to s.condition,
                "class" to if (s.bearerClass >= 0) sumo.className(SumoClassId(s.bearerClass)) else null,
                "sections" to b.support[i].size, "f" to t.frequency, "c" to t.confidence,
                "opposed" to same.filter { b.statements[it].modality.opposes(s.modality) }.flatMap { o -> b.support[o].take(4).map { b.headings.getOrElse(it) { "#$it" } } })
        }
        // Per sentence, everything the parse registered: each noun a concept typed by SUMO (with its
        // nearest ancestors), each named entity, each predicate, and the statements read from it.
        val sentences = text?.let { body ->
            val doc = CoreNlpRuntime().use { it.analyze(body) }
            val bySentence = NormClauses.extract(doc).groupBy { it.sentence }
            doc.sentences.values().map { s ->
                val said = body.substring(s.begin, s.end).replace(Regex("\\s+"), " ").trim()
                val toks = s.tokens.values()
                val concepts = toks.filter { it.tag.startsWith("NN") }.map { t ->
                    val lemma = t.lemma.lowercase()
                    val id = SumoCorpus.nounClassId(lemma).takeIf { it >= 0 } ?: SumoCorpus.nounClassId(lemma.removeSuffix("s"))
                    val lineage = if (id >= 0) SumoCorpus.closure(id).toIntArray().filter { it != id }.sortedDescending().take(3)
                        .map { sumo.className(SumoClassId(it)) } else emptyList()
                    linkedMapOf("word" to t.word, "lemma" to lemma, "class" to if (id >= 0) sumo.className(SumoClassId(id)) else null, "is" to lineage)
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
        }.orEmpty()
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
        )
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
        for ((k, heading) in keys.withIndex()) {
            val at = trail.node(CURATE, listOf(b.name), heading, "book.section")
            trail.emit(run, borg.trikeshed.lcnc.LcncTrail.Kind.END, at, "${bySection[k].size} statements · ${k + 1}/${b.headings.size}")
            for ((j, i) in bySection[k].withIndex()) {
                val st = b.statements[i]
                val sNode = trail.node(CURATE, listOf(b.name, heading), "s$j", "book.statement")
                trail.emit(run, borg.trikeshed.lcnc.LcncTrail.Kind.END, sNode,
                    listOfNotNull(st.bearer, st.modality.key, st.action.replace('_', ' '), st.obj, st.condition).joinToString(" · "))
                ring(trail, run, sNode, st, atom(st.bearer), atom(st.obj))
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
    private fun sectionKeys(headings: List<String>): List<String> {
        val seen = HashSet<String>()
        return headings.mapIndexed { k, h -> h.take(48).let { if (seen.add(it)) it else "$it #$k" } }
    }

    /**
     * A statement's concepts into the activity ring. Concepts are one pool across every book (root
     * [POOL], one column per SUMO class): the same concept read in two books is one node, so its edges
     * reach into both and common concepts from uncommon sources meet in one place.
     */
    private fun ring(trail: borg.trikeshed.lcnc.LcncTrail, run: Int, statement: Int, st: NormStatement, bearer: String?, obj: String?) {
        val sumo = SumoCorpus.classifier
        fun hub(lemma: String, cls: Int): Int =
            trail.node(POOL, listOf(if (cls >= 0) sumo.className(SumoClassId(cls)) else "Unclassified"), lemma, "concept")
        bearer?.let { b -> trail.link(run, statement, hub(b, if (st.bearerClass >= 0) st.bearerClass else SumoCorpus.nounClassId(b)), "subject") }
        obj?.let { o -> trail.link(run, statement, hub(o, SumoCorpus.nounClassId(o)), st.modality.key + " " + st.action.replace('_', ' ')) }
    }

    /**
     * The bearer's SUMO class: the sense of its head noun its [neighbors] and the [believed] classes
     * support most (preferred sense when both are silent), else its named-entity type.
     */
    private fun bearerClass(c: NormClause, neighbors: Collection<String> = emptyList(), believed: RoaringSeries = RoaringSeries.EMPTY): Int {
        val h = c.head
        SumoCorpus.nounSense(h, neighbors, believed).takeIf { it >= 0 }?.let { return it }
        SumoCorpus.nounSense(h.removeSuffix("s"), neighbors, believed).takeIf { it >= 0 }?.let { return it }
        return NER_CLASS[c.ner]?.let { SumoCorpus.classifier.classId(it)?.value } ?: -1
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
        fun registerLanguageNodes(ctx: borg.trikeshed.module.ModuleContext, bank: borg.trikeshed.kif.KifKnowledgeBase) {
            SkillCurateNode.register(ctx.lcncRunners, bank)
            NlRulesNode.register(ctx.lcncRunners, bank)
            SkillOverlapNode.register(ctx.lcncRunners, bank)
            NormClausesNode.register(ctx.lcncRunners, bank)
            val nodes = ConstellationNodes(ctx.stateDir, ctx.blackboard, bank).also { it.register(ctx.lcncRunners) }
            // The ring is in memory: saved constellations stand in it again without waiting for a join.
            Thread({ runCatching { nodes.restore() }.onFailure { System.err.println("[OROBOROS] constellation restore failed: $it") } },
                "constellation-restore").apply { isDaemon = true }.start()
            // One curated section read whole: `?book=<name>&section=<ordinal or heading>`.
            ctx.routes.claim("language", "/api/curation/section") { method, path, _, _ ->
                if (method != "GET") return@claim borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
                val q = borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
                val out = nodes.section(q["book"].orEmpty(), q["section"].orEmpty())
                borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(if (out.containsKey("error")) 404 else 200, jsonOf(out))
            }
        }

        const val CURATE = "book.curate"
        /** Conflict bridges drawn per join. */
        const val CONFLICT_LINKS = 600
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
