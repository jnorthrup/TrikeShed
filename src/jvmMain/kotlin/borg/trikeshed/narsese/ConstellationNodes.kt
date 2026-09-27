package borg.trikeshed.narsese

import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.ontology.SumoClassId
import borg.trikeshed.ontology.SumoCorpus
import borg.trikeshed.parse.json.JsonSupport
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
     * so `book.curate` with heading `^§ ` curates the wiki like any book and a constellation can join
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

    private fun bookFile(name: String) = File(books, name.replace(Regex("[^A-Za-z0-9._-]+"), "-") + ".json")

    private fun save(b: Book) = bookFile(b.name).writeText(JsonSupport.stringify(mapOf(
        "name" to b.name, "work" to b.work, "date" to b.date, "headings" to b.headings,
        "statements" to b.statements.map { it.toMap() },
        "support" to b.support.map { it.toList() },
        "facts" to b.facts.toList(),
    )))

    private fun load(name: String): Book? {
        val f = bookFile(name).takeIf { it.isFile } ?: return null
        val m = JsonSupport.parseMap(f.readText())
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
        )
    }

    private fun constellation(name: String): Constellation = live.getOrPut(name) {
        Constellation(name).also { c ->
            File(root, "$name.members").takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }
                ?.forEach { b -> load(b)?.let(c::join) }
        }
    }

    private val curate = LcncNodeRunner { node, inputs ->
        val text = (inputs["text"] ?: inputs["text?"]) as? String ?: ""
        val name = node.params["book"]?.takeIf { it.isNotBlank() } ?: error("book.curate: name the book")
        val heading = node.params["heading"]?.takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.MULTILINE) }
        val generic = (node.params["generic"] ?: "").split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val sections = NormClausesNode.sections(text, heading)
        // What is already believed: the classes a named constellation's bearers resolved to, with their ancestors.
        val believed = node.params["constellation"]?.takeIf { it.isNotBlank() }?.let { n ->
            constellation(n).bearing.keys.fold(RoaringSeries.EMPTY) { acc, c -> acc or SumoCorpus.closure(c) }
        } ?: RoaringSeries.EMPTY
        val typed = java.util.IdentityHashMap<NormClause, Int>()
        val facts = LinkedHashSet<String>()
        val clauses = CoreNlpRuntime().use { nlp -> sections.map { sec ->
            val doc = nlp.analyze(sec)
            val found = NormClauses.extract(doc, generic)
            // The section's other nouns are the context a bearer's sense is read in.
            val nouns = doc.sentences.values().flatMap { s -> s.tokens.values().filter { it.tag.startsWith("NN") }.map { it.lemma.lowercase() } }.distinct()
            for (c in found) typed[c] = bearerClass(c, nouns, believed)
            facts.addAll(NormClauses.facts(doc))
            found
        } }
        val book = Book.of(name, node.params["work"]?.takeIf { it.isNotBlank() } ?: name, node.params["date"] ?: "", sections, clauses, { typed[it] ?: bearerClass(it) }, facts)
        val senses = book.statements.filter { it.bearerClass >= 0 }.associate { it.bearer to SumoCorpus.classifier.className(SumoClassId(it.bearerClass)) }
        save(book)
        mapOf("book" to name, "sections" to sections.size, "clauses" to clauses.sumOf { it.size }, "statements" to book.statements.size,
            "senses" to senses, "facts" to facts.size)
    }

    private val join = LcncNodeRunner { node, inputs ->
        val name = node.params["constellation"]?.takeIf { it.isNotBlank() } ?: error("constellation.join: name the constellation")
        val bookName = (inputs["book"] ?: inputs["book?"])?.toString() ?: node.params["book"] ?: error("constellation.join: no book")
        val book = load(bookName) ?: error("constellation.join: book '$bookName' is not curated")
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
        fun read(t: String) = NormClausesNode.sections(t, null).flatMap { NormClauses.extract(nlp.analyze(it)) }.map { NormStatement.of(it, ::bearerClass) }
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
            ConstellationNodes(ctx.stateDir, ctx.blackboard, bank).register(ctx.lcncRunners)
        }

        const val CURATE = "book.curate"
        const val JOIN = "constellation.join"
        const val RENDER = "constellation.render"
        const val WIKI_READ = "wiki.read"
        const val ASK = "constellation.ask"
        const val SETTLE = "constellation.settle"
        const val SELECT = "rdf.select"
        const val BRIEF = "constellation.brief"
        const val LANGUAGE = "constellation"
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
