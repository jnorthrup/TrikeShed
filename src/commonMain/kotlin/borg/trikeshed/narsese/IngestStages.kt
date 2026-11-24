package borg.trikeshed.narsese

import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpToken
import borg.trikeshed.rdf.QuadStore
import borg.trikeshed.rdf.RdfTerm
import borg.trikeshed.rdf.RdfVocab
import kotlin.math.round

/** A bearer's class from a clause, the nouns of its section and the believed classes; -1 when none. */
typealias BearerTyper = (NormClause, Set<String>, Set<Int>) -> Int

/**
 * A passage and the claims a review of it must find, each read by the same stages and compared stage by stage. A
 * claim is a sentence: it goes through the section cut, the parser, [NormClauses] and [NormStatement] exactly as
 * the passage does, so what a stage must yield for the claim to be found is what that stage yields for the claim's
 * own words. Nothing here is taken from the passage's reading as truth.
 *
 * The stages, in the order a text passes them:
 * - [TOKEN]: the passage holds every content lemma of the claim (noun, adjective, number, and any verb but `be`).
 * - [SECTION]: one section of the passage's cut holds them all, so the cut did not split the claim.
 * - [SENTENCE]: one sentence holds them all. That sentence, the one sharing most with the claim, is the one
 *   [DEPENDENCY] reads.
 * - [DEPENDENCY]: it holds every dependency the claim's parse draws between two content words; a claim of several
 *   content words whose parse draws none does not pass.
 * - [PARTY]: every party the claim defines (a personal pronoun bound to a quoted term) is defined by the passage too.
 *   A claim that defines no party passes this stage with nothing wanted.
 * - [CLAUSE]: the claim reads to a clause, and the passage to one with the same subject, verb and object.
 * - [BEARER]: a bearer the typer classes in the claim is classed the same wherever the passage names it; a bearer
 *   the passage does not name is [STATEMENT]'s to miss.
 * - [STATEMENT]: the passage states each of the claim's statements under the same key, force included.
 * - [JOIN]: joined as two books, the passage and the claim restate each of the claim's statements; `truth` is the
 *   belief the join holds for each one it carries.
 * - [QUAD]: the join's Turtle, loaded into a store of this stage's own, holds each restated claim statement as a norm.
 *
 * A claim is blamed on the first stage that does not pass. Every claim is then read again under each variant of
 * what a run can change without code (the claim's own subjects taken as generic, each other parser, and the named
 * constellation's believed classes taken away), and a variant under which the claim gets further is named beside it.
 * Nothing is written anywhere.
 */
object IngestStages {
    const val TYPE = "ingest.stages"
    const val NAME = "preset-ingest-stages"
    const val TOKEN = "token"
    const val SECTION = "section"
    const val SENTENCE = "sentence"
    const val DEPENDENCY = "dependency"
    const val PARTY = "party"
    const val CLAUSE = "clause"
    const val BEARER = "bearer"
    const val STATEMENT = "statement"
    const val JOIN = "join"
    const val QUAD = "quad"
    val STAGES = listOf(TOKEN, SECTION, SENTENCE, DEPENDENCY, PARTY, CLAUSE, BEARER, STATEMENT, JOIN, QUAD)

    /** A text as the stages leave it: its sections, each one's parse, each one's clauses and typed statements, and the [parties] it was read with. */
    class Reading(val sections: List<String>, val documents: List<NlpDocument>, val clauses: List<List<NormClause>>,
                  typed: (NormClause) -> Int = { -1 }, val parties: Map<String, PartyAliases.Party> = emptyMap()) {
        val statements: List<NormStatement> = clauses.flatten().map { NormStatement.of(it, typed) }.distinctBy { it.key }
    }

    /** One parsed sentence as the first two stages compare it: its content lemmas and the dependencies between them. */
    class Sentence(val text: String, val lemmas: Set<String>, val edges: Set<String>)

    private fun content(t: NlpToken) =
        t.tag.startsWith("NN") || t.tag.startsWith("JJ") || t.tag == "CD" || t.tag.startsWith("VB") && t.lemma.lowercase() != "be"

    fun sentences(doc: NlpDocument): List<Sentence> = doc.sentences.values().map { s ->
        val words = s.tokens.values().filter(::content).associateBy { it.index }
        Sentence(doc.text.substring(s.begin, s.end).replace(Regex("\\s+"), " ").trim(), words.values.map { it.lemma.lowercase() }.toSet(),
            s.dependencies.values().mapNotNull { d ->
                val governor = words[d.governor] ?: return@mapNotNull null
                val dependent = words[d.dependent] ?: return@mapNotNull null
                governor.lemma.lowercase() + " " + d.relation.lowercase() + " " + dependent.lemma.lowercase()
            }.toSet())
    }

    private fun nouns(docs: List<NlpDocument>): Set<String> {
        val out = HashSet<String>()
        for (d in docs) for (s in d.sentences.values()) for (t in s.tokens.values()) if (t.tag.startsWith("NN")) out.add(t.lemma.lowercase())
        return out
    }

    private fun clause(c: NormClause): Map<String, Any?> = linkedMapOf("subject" to c.subject, "modal" to c.modal, "affirmative" to c.affirmative,
        "verb" to c.verb, "object" to c.obj, "oblique" to c.oblique, "condition" to c.condition)

    /**
     * Each stage's verdict on [claim] against [source]: `pass`, and what the stage found or lacked. Beside those, what a
     * person needs to be told why: the passage sentences that hold the claim's clause (`clause.sentences`), the pronouns
     * a claim read to no clause names and no party binds (`clause.unbound`), each bearer's category by [kind]'s name in
     * the claim and in the passage (`bearer.kinds`, when [kind] names it), and the passage sentences stating the
     * opposite of the claim's rule (`statement.contrary`).
     */
    fun judge(source: Reading, claim: Reading, turtleOf: (Constellation) -> String, kind: (Int) -> String = { "" }): Map<String, Map<String, Any?>> {
        val wanted = claim.documents.flatMap(::sentences)
        val lemmas = wanted.flatMap { it.lemmas }.toSet()
        val edges = wanted.flatMap { it.edges }.toSet()
        val best = source.documents.flatMap(::sentences).maxWithOrNull(compareBy({ s -> lemmas.count { it in s.lemmas } }, { s -> edges.count { it in s.edges } }))
        val lacking = lemmas - source.documents.flatMap(::sentences).flatMap { it.lemmas }.toSet()
        val apart = lemmas - best?.lemmas.orEmpty()
        val undrawn = edges - best?.edges.orEmpty()
        val sectionLemmas = source.documents.map { d -> sentences(d).flatMap { it.lemmas }.toSet() }
        val holding = sectionLemmas.indices.maxByOrNull { i -> lemmas.count { it in sectionLemmas[i] } }?.let { sectionLemmas[it] }.orEmpty()
        val split = lemmas - holding
        val parties = claim.documents.flatMap { PartyAliases.of(it).values }.map { it.noun }.toSet()
        val heldParties = source.documents.flatMap { PartyAliases.of(it).values }.map { it.noun }.toSet()
        val unparted = parties - heldParties
        val clauses = claim.clauses.flatten()
        val found = source.clauses.flatten()
        val unread = clauses.filter { c -> found.none { it.subject == c.subject && it.verb == c.verb && it.obj == c.obj } }
        val held = source.statements.groupBy({ it.bearer }, { it.bearerClass })
        val classed = claim.statements.filter { it.bearerClass >= 0 }
        val misclassed = classed.filter { c -> held[c.bearer]?.let { c.bearerClass !in it } == true }
        val stated = source.statements.associateBy { it.key }
        val unstated = claim.statements.filter { it.key !in stated }
        val opposed = claim.statements.filter { c -> source.statements.any { it.proposition == c.proposition && it.modality.opposes(c.modality) } }
        // what a person is told why by: where the passage holds the clause or says the opposite, who no party binds, what categories are named
        val stating = found.filter { f -> clauses.any { it.subject == f.subject && it.verb == f.verb && it.obj == f.obj } }.map { it.sentence }.distinct()
        val contrary = found.filter { f -> NormStatement.of(f).let { s -> claim.statements.any { it.proposition == s.proposition && it.modality.opposes(s.modality) } } }
            .map { it.sentence }.distinct()
        val unbound = if (clauses.isNotEmpty()) emptyList() else claim.documents.flatMap { d -> d.sentences.values() }.flatMap { s ->
            val tokens = s.tokens.values().associateBy { it.index }
            s.dependencies.values().filter { it.relation.lowercase().startsWith("nsubj") }.mapNotNull { tokens[it.dependent] }
                .filter { it.tag.startsWith("PRP") && it.lemma.lowercase() !in claim.parties }.map { it.word.lowercase() }
        }.distinct()
        val kinds = classed.distinctBy { it.bearer to it.bearerClass }.map { c ->
            val others = held[c.bearer].orEmpty().distinct().filter { it != c.bearerClass && it >= 0 }
            linkedMapOf("word" to c.bearer, "claim" to kind(c.bearerClass), "passage" to others.map(kind).filter { it.isNotEmpty() }.joinToString(" or ").ifEmpty { null })
        }.takeIf { k -> k.isNotEmpty() && k.all { (it["claim"] as String).isNotEmpty() } }
        val joined = Constellation(TYPE)
        joined.join(Book.of("passage", "passage", "", source.sections, source.clauses))
        joined.join(Book.of("claim", "claim", "", claim.sections, claim.clauses))
        val restated = joined.restated().map { joined.statements[it].key }.toSet()
        val stored = runCatching { QuadStore().load(turtleOf(joined)) }
        val normOf = "${RdfVocab.FORGE}constellation/${joined.name}/norm/"
        val missing = if (stored.isSuccess) claim.statements.filter { s ->
            !stored.getOrThrow().contains(RdfTerm.Iri(normOf + s.id).toTurtle(), RdfVocab.rdf("type").toTurtle(), RdfVocab.forge("Norm").toTurtle())
        } else claim.statements
        return linkedMapOf(
            TOKEN to linkedMapOf("pass" to (lemmas.isNotEmpty() && lacking.isEmpty()), "wanted" to lemmas.sorted(), "lacking" to lacking.sorted()),
            SECTION to linkedMapOf("pass" to (lemmas.isNotEmpty() && split.isEmpty()), "wanted" to lemmas.sorted(), "lacking" to split.sorted()),
            SENTENCE to linkedMapOf("pass" to (lemmas.isNotEmpty() && apart.isEmpty()), "wanted" to lemmas.sorted(), "lacking" to apart.sorted(), "sentence" to best?.text),
            DEPENDENCY to linkedMapOf("pass" to (best != null && undrawn.isEmpty() && (edges.isNotEmpty() || lemmas.size < 2)), "wanted" to edges.sorted(), "lacking" to undrawn.sorted()),
            PARTY to linkedMapOf("pass" to unparted.isEmpty(), "wanted" to parties.sorted(), "lacking" to unparted.sorted()),
            CLAUSE to linkedMapOf("pass" to (clauses.isNotEmpty() && unread.isEmpty()), "claim" to clauses.map(::clause), "lacking" to unread.map(::clause),
                "sentences" to stating, "unbound" to unbound),
            BEARER to (linkedMapOf<String, Any?>("pass" to misclassed.isEmpty(), "wanted" to classed.map { "${it.bearer}=${it.bearerClass}" },
                "lacking" to misclassed.map { "${it.bearer}=${held.getValue(it.bearer).distinct().joinToString("/")}" }).also { m -> kinds?.let { m["kinds"] = it } }),
            STATEMENT to linkedMapOf("pass" to (claim.statements.isNotEmpty() && unstated.isEmpty()), "claim" to claim.statements.map { it.key },
                "lacking" to unstated.map { it.key }, "opposed" to opposed.map { it.key }, "contrary" to contrary),
            JOIN to linkedMapOf("pass" to (claim.statements.isNotEmpty() && claim.statements.all { it.key in restated }), "restated" to restated.sorted(),
                "conflicts" to joined.conflicts().map { (a, b) -> listOf(joined.statements[a].key, joined.statements[b].key) },
                "truth" to claim.statements.mapNotNull { c -> joined.statements.indexOfFirst { it.key == c.key }.takeIf { it >= 0 }?.let { id ->
                    joined.truth(id).let { linkedMapOf("statement" to c.key, "frequency" to it.frequency, "confidence" to it.confidence) } } }),
            QUAD to linkedMapOf("pass" to (claim.statements.isNotEmpty() && missing.isEmpty()), "wanted" to claim.statements.map { it.key },
                "lacking" to missing.map { it.key }, "error" to stored.exceptionOrNull()?.message),
        )
    }

    /** The first stage of [stages] that does not pass, or null when all do. */
    fun blame(stages: Map<String, Map<String, Any?>>): String? = STAGES.firstOrNull { stages.getValue(it)["pass"] != true }

    /**
     * [text] and each of [expectations] through the stages. [parsers] names the parsers [read] offers, the first the
     * one a result is read under; [read] parses a batch of texts under one of them. [believed] are the classes a named
     * constellation believes; [typer] classes a bearer from them; [turtleOf] is the join's Turtle. The answer is `board`
     * (one row a claim: its stages, the stage blamed, the blame under each variant and the variants that got it
     * further), `blame` (claims by blamed stage) and `passage` (what the passage itself read to).
     */
    suspend fun run(text: String, expectations: List<Expectation>, generic: Set<String>, parsers: List<String>,
                    read: suspend (String, List<String>) -> List<NlpDocument>, believed: Set<Int>, typer: BearerTyper,
                    turtleOf: (Constellation) -> String, kind: (Int) -> String = { "" }): Map<String, Any?> {
        require(text.isNotBlank()) { "$TYPE: no passage" }
        require(parsers.isNotEmpty()) { "$TYPE: no parser" }
        val sections = SectionShapes.sections(text).texts
        val claimed = expectations.map { SectionShapes.sections(it.claim).texts }
        val texts = sections + claimed.flatten()
        val parsed = parsers.associateWith { parser -> read(parser, texts).also { require(it.size == texts.size) { "$TYPE: $parser read ${it.size} of ${texts.size} texts" } } }

        fun reading(sectionTexts: List<String>, docs: List<NlpDocument>, subjects: Set<String>, known: Set<Int>,
                    defined: Map<String, PartyAliases.Party> = emptyMap()): Reading {
            val nouned = nouns(docs)
            // A party a section defines stands for the sections after it, as curation reads them.
            val parties = LinkedHashMap(defined)
            val clauses = docs.map { d -> for ((alias, party) in PartyAliases.of(d)) if (alias !in parties) parties[alias] = party; NormClauses.extract(d, subjects, parties) }
            return Reading(sectionTexts, docs, clauses, { c -> typer(c, nouned, known) }, parties)
        }
        fun passage(parser: String, subjects: Set<String>, known: Set<Int>) =
            reading(sections, parsed.getValue(parser).take(sections.size), subjects, known)
        // A claim is about the passage: the parties the passage defines ("you", "we") are the claim's too.
        fun claim(parser: String, subjects: Set<String>, known: Set<Int>, i: Int): Reading {
            val from = sections.size + claimed.take(i).sumOf { it.size }
            return reading(claimed[i], parsed.getValue(parser).subList(from, from + claimed[i].size), subjects, known, passage(parser, subjects, known).parties)
        }

        // What each claim's parse calls a subject: the words the generic variant lets a present tense state a rule about.
        val subjects = expectations.indices.flatMap { i -> claim(parsers[0], generic, believed, i).documents }.flatMap { d -> d.sentences.values() }.flatMap { s ->
            val tokens = s.tokens.values().associateBy { it.index }
            s.dependencies.values().filter { it.relation.lowercase().startsWith("nsubj") }.mapNotNull { tokens[it.dependent] }.filter { it.tag.startsWith("NN") }.map { it.lemma.lowercase() }
        }.toSet() - generic
        // A variant names the parser, the generic subjects and the believed classes it reads under.
        val variants = LinkedHashMap<String, Triple<String, Set<String>, Set<Int>>>()
        if (subjects.isNotEmpty()) variants["generic: " + subjects.sorted().joinToString(", ")] = Triple(parsers[0], generic + subjects, believed)
        for (parser in parsers.drop(1)) variants["parser: $parser"] = Triple(parser, generic, believed)
        if (believed.isNotEmpty()) variants["constellation: none"] = Triple(parsers[0], generic, emptySet())

        val source = passage(parsers[0], generic, believed)
        val stated = expectations.indices.map { claim(parsers[0], generic, believed, it) }
        val truths = expectations.indices.filter { expectations[it].author == Expectation.PERSON }.flatMap { stated[it].statements }
        val rank = { stage: String? -> stage?.let(STAGES::indexOf) ?: STAGES.size }
        val board = expectations.mapIndexed { i, expected ->
            val stages = judge(source, stated[i], turtleOf, kind)
            val blamed = blame(stages)
            val under = variants.mapValues { (_, v) -> blame(judge(passage(v.first, v.second, v.third), claim(v.first, v.second, v.third, i), turtleOf)) }
            val further = under.filterValues { rank(it) > rank(blamed) }.keys.toList()
            val row = linkedMapOf<String, Any?>("author" to expected.author, "standing" to expected.standing, "claim" to expected.claim,
                "blame" to blamed, "stages" to stages, "variants" to under.mapValues { it.value ?: "none" }, "further" to further)
            // A model's claim is a candidate: where a person's claims read to statements, it is held against them.
            if (expected.author == Expectation.MODEL && truths.isNotEmpty()) row["against"] = stated[i].statements.map { s ->
                when {
                    truths.any { it.key == s.key } -> "restates a person's claim"
                    truths.any { it.proposition == s.proposition && it.modality.opposes(s.modality) } -> "opposes a person's claim"
                    else -> "no person states it"
                }
            }
            row
        }
        val blamed = LinkedHashMap<String, MutableList<String>>()
        for (row in board) blamed.getOrPut(row["blame"] as String? ?: "none") { ArrayList() }.add(row["claim"] as String)
        return linkedMapOf("board" to board, "blame" to blamed, "passage" to linkedMapOf("parser" to parsers[0], "generic" to generic.sorted(), "sections" to sections.size,
            "sentences" to source.documents.sumOf { it.sentences.size }, "clauses" to source.clauses.flatten().map(::clause), "statements" to source.statements.map { it.key }))
    }

    private fun items(value: String?) = value.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /**
     * The node over [read], a host's parsers by name. [typer] and [turtleOf] are the host's: the bearer typing and the
     * join's Turtle. [believedOf] gives a named constellation's believed classes, read when the node names one; [kind]
     * names a class the typer gives, for the bearer stage to say which categories differ.
     */
    fun registry(read: suspend (String, List<String>) -> List<NlpDocument>, typer: BearerTyper, believedOf: (String) -> Set<Int>,
                  turtleOf: (Constellation) -> String, kind: (Int) -> String = { "" }): Map<String, LcncNodeRunner> = mapOf(
        TYPE to LcncNodeRunner { node, inputs ->
            val text = (inputs["text"] ?: inputs["text?"])?.toString().orEmpty()
            val expected = try { Expectation.rows(inputs["expectations"] ?: inputs["expectations?"] ?: node.params["expectations"]) }
                catch (refused: IllegalArgumentException) { throw IllegalArgumentException("$TYPE: ${refused.message}") }
            require(expected.isNotEmpty()) { "$TYPE: no expectation to look for" }
            val named = node.params["constellation"].orEmpty().trim()
            run(text, expected, items(node.params["generic"]).toSet(), items(node.params["parsers"] ?: PARSERS), read,
                if (named.isEmpty()) emptySet() else believedOf(named), typer, turtleOf, kind)
        })

    /** The parsers a bare node reads under: the corpus parser, then the reduced one. */
    const val PARSERS = "full-v1, syntax-v1"

    /** Each stage by the name a person reads it under. */
    val PLAIN = _m[TOKEN j "words", SECTION j "section", SENTENCE j "sentence", DEPENDENCY j "grammar", PARTY j "parties",
        CLAUSE j "clause", BEARER j "category", STATEMENT j "rule", JOIN j "read together", QUAD j "record"]

    fun plain(stage: String): String = PLAIN[stage] ?: stage

    /** [items] as a person lists them: "a", "a and b", "a, b and c". */
    fun listed(items: List<String>): String = if (items.size < 2) items.joinToString("") else items.dropLast(1).joinToString(", ") + " and " + items.last()

    /** The words of [text], each with where it starts. */
    fun words(text: String): List<Join<String, Int>> = Regex("[A-Za-z][A-Za-z'’-]*").findAll(text).map { it.value j it.range.first }.toList()

    /**
     * [lemmas] as [claim] writes them, in the claim's order: a lemma is the first word of the claim it begins
     * ("support" is "supported"), lower-cased; a lemma no word of the claim begins stays as it is, after the others.
     */
    fun written(lemmas: List<*>, claim: String): List<String> {
        val ws = words(claim)
        return lemmas.map { it.toString() }.map { l -> ws.indexOfFirst { it.a.lowercase().startsWith(l.lowercase()) }.let { k -> (if (k < 0) l else ws[k].a.lowercase()) j (if (k < 0) Int.MAX_VALUE else k) } }
            .sortedBy { it.b }.map { it.a }.distinct()
    }

    /** [lemmas] as [claim] writes them, quoted and listed. */
    fun quoted(lemmas: List<*>, claim: String) = listed(written(lemmas, claim).map { "“$it”" })

    /** A clause the stages read, as a sentence part: "title shall remain with you", "bailment does involve change in possession". */
    fun spoken(clause: Map<*, *>): String {
        val modal = clause["modal"]?.toString().orEmpty()
        val yes = clause["affirmative"] != false
        val force = when {
            modal == "generic" -> if (yes) "does" else "does not"
            modal == "cannot" || yes -> modal
            else -> "$modal not"
        }
        return listOfNotNull(clause["subject"], force, clause["verb"]?.toString()?.replace('_', ' '), clause["object"], clause["oblique"],
            clause["condition"]?.let { "($it)" }).joinToString(" ") { it.toString() }.trim()
    }

    /** A statement key (`force|subject|verb|object|condition`) as a sentence part: "coinbase must pay interest". */
    fun spoken(key: String): String {
        val p = key.split('|')
        val force = when (p[0]) { "presumed" -> "does"; "must-not" -> "must not"; "may-not" -> "may not"; else -> p[0] }
        return listOf(p.getOrElse(1) { "" }, force, p.getOrElse(2) { "" }.replace('_', ' '), p.getOrElse(3) { "" }, p.getOrElse(4) { "" }.let { if (it.isEmpty()) "" else "($it)" })
            .filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** A category's name with its words apart: "LegalAgent" is "Legal Agent". */
    fun spaced(name: Any?): String = name.toString().replace(Regex("([a-z])([A-Z])"), "\$1 \$2")

    /** [claim] with its force turned over: "may grant" is "may NOT grant", "shall not transfer" is "shall transfer"; null when it has no modal word. */
    fun opposite(claim: String): String? {
        val ws = words(claim)
        val k = ws.indexOfFirst { it.a.lowercase() in _s["shall", "must", "may", "will", "should", "can", "cannot"] }
        if (k < 0) return null
        val next = ws.getOrNull(k + 1)
        if (next != null && next.a.lowercase() == "not") return claim.substring(0, next.b) + claim.substring(next.b + next.a.length).trimStart()
        val at = ws[k].b + ws[k].a.length
        return claim.substring(0, at) + " NOT" + claim.substring(at)
    }

    /**
     * One sentence: what [stage] looked for in the passage for [claim], by its [evidence], and why the ball went on
     * or was caught there. Built from the evidence alone; where the evidence names no reason, the sentence says what
     * the stage did not find and no more.
     */
    fun reason(stage: String, evidence: Map<*, *>, claim: String): String {
        val pass = evidence["pass"] == true
        fun list(key: String) = (evidence[key] as? List<*>).orEmpty()
        val lacking = list("lacking")
        return when (stage) {
            TOKEN -> if (pass) "The passage has every word of the claim: ${listed(written(list("wanted"), claim))}."
                else if (lacking.isEmpty()) "The claim has no word the machine can look for."
                else "The passage never uses the word${if (lacking.size > 1) "s" else ""} ${quoted(lacking, claim)}."
            SECTION -> if (pass) "One section of the passage holds all of these words."
                else "No one section holds them all: the section holding the most lacks ${quoted(lacking, claim)}."
            SENTENCE -> {
                val s = evidence["sentence"]?.toString()
                if (pass) "One sentence holds them all: “$s”"
                else if (s == null) "No sentence of the passage holds any of these words."
                else "No one sentence holds them all; the closest lacks ${quoted(lacking, claim)}: “$s”"
            }
            DEPENDENCY -> {
                fun joined(edges: List<*>) = listed(edges.map { e ->
                    val p = e.toString().split(' ')
                    "${written(_l[p.last()], claim)[0]} to ${written(_l[p.first()], claim)[0]}"
                })
                val wanted = list("wanted")
                if (pass && wanted.isEmpty()) "The claim has one word to look for, so there is nothing to join."
                else if (pass) "That sentence joins the words as the claim does: ${joined(wanted)}."
                else if (lacking.isNotEmpty()) "That sentence does not join ${joined(lacking)} as the claim does."
                else "The claim's grammar joins none of its words to another, so the machine has nothing to look for."
            }
            PARTY -> if (pass && list("wanted").isEmpty()) "The claim defines no party of its own."
                else if (pass) "The passage defines the same parties: ${listed(list("wanted").map { it.toString() })}."
                else "The claim defines ${listed(lacking.map { "“$it”" })} as a party; the passage does not."
            CLAUSE -> {
                val read = list("claim").filterIsInstance<Map<*, *>>()
                if (pass) "The passage has the same clause: ${listed(read.map(::spoken))}."
                else if (lacking.isNotEmpty()) "The passage has no clause in which ${lacking.filterIsInstance<Map<*, *>>().joinToString(" or ", transform = ::spoken)}."
                else (evidence["unbound"] as? List<*>)?.takeIf { it.isNotEmpty() }?.let { "The machine read no clause from the claim: it does not know who ${listed(it.map { w -> "“$w”" })} is." }
                    ?: "The machine read no clause from the claim, no one who shall, must or may do something, so it has nothing to look for."
            }
            BEARER -> {
                val named = list("kinds").filterIsInstance<Map<*, *>>()
                val wanted = list("wanted").map { it.toString().substringBefore('=') }.distinct()
                if (pass && wanted.isEmpty()) "Nothing the claim names has a category to compare."
                else if (pass) named.firstOrNull()?.let { "The passage puts “${it["word"]}” in the category ${spaced(it["claim"])}, as the claim does." }
                    ?: "The passage puts ${listed(wanted.map { "“$it”" })} in the same category as the claim does."
                else named.firstOrNull { it["passage"] != null }?.let { "The claim puts “${it["word"]}” in the category ${spaced(it["claim"])}; the passage puts it in ${spaced(it["passage"])}." }
                    ?: "The claim and the passage put ${listed(lacking.map { "“${it.toString().substringBefore('=')}”" }.distinct())} in two different categories."
            }
            STATEMENT -> {
                val rules = list("claim").map { it.toString() }
                val opposed = list("opposed").map { it.toString() }
                if (pass) "The passage states the claim's rule: ${spoken(rules.first())}."
                else if (opposed.isNotEmpty()) "The passage says the opposite: " + ((evidence["contrary"] as? List<*>)?.firstOrNull()?.let { "“$it”" }
                    ?: (opposite(claim) ?: spoken(opposed.first())).let { if (it.endsWith(".")) it else "$it." })
                else if (lacking.isNotEmpty()) "The passage does not state that ${spoken(lacking.first().toString())}."
                else "The machine read no rule from the claim, so there is none to find in the passage."
            }
            JOIN -> {
                val truth = list("truth").filterIsInstance<Map<*, *>>().firstOrNull()
                fun f(v: Any?) = round(((v as? Number)?.toDouble() ?: 0.0) * 100).toInt().let { "${it / 100}.${(it % 100).toString().padStart(2, '0')}" }
                if (pass) "Read together, the passage restates the claim's rule" + (truth?.let { "; the machine believes it with frequency ${f(it["frequency"])} and confidence ${f(it["confidence"])}." } ?: ".")
                else "Read together, the passage does not restate the claim's rule."
            }
            QUAD -> if (pass) "The record keeps the claim's rule as a norm."
                else evidence["error"]?.let { "The record could not be written: $it." } ?: "The record does not keep the claim's rule as a norm."
            else -> if (pass) "The $stage stage found the claim." else "The $stage stage did not find the claim."
        }
    }

    fun contract() = LcncPortContract(TYPE, "a passage and what a review must find → each claim's verdict stage by stage, the stage blamed, and the variants that get it further",
        _l["text", "expectations?"], _l["board", "blame", "passage"],
        inputKinds = _m["text" j "text", "expectations" j "json"],
        outputKinds = _m["board" j "json", "blame" j "json", "passage" j "json"],
        params = _m[
            "generic" j LcncPortContract.LcncParamSpec(ph = "subjects whose present tense states a rule"),
            "parsers" j LcncPortContract.LcncParamSpec(v = PARSERS, ph = "parser profiles; a result is read under the first"),
            "constellation" j LcncPortContract.LcncParamSpec(optional = true, ph = "a named constellation whose believed classes type the bearers"),
            "expectations" j LcncPortContract.LcncParamSpec(cols = _l["author", "claim"], optional = true)],
        isEffect = true)

    val info = LcncPresetInfo(NAME, "Pachinko: drop a passage through the ingest",
        "Reads a passage of a book and each thing a review must find by the same steps, and shows the first step at which each one is lost.",
        "One line of an Anna's Archive metadata file for a book the Internet Archive serves openly, the words the passage starts and ends with, at least one expectation, and a network connection.",
        "One row an expectation: whether each step found it, the step blamed when it did not, and whether a different setting gets it further.",
        "Paste the index line, set the passage's first and last words on the passage node, and list the expectations on the review node.")

    fun build(): LcncProgram = LcncProgram(NAME, s_[
        LcncNode("instructions", "note", _m["text" j "Paste one line of an Anna's Archive ${BookReview.COLLECTION} metadata file into the record box. On the review node list what a review must find, one sentence a row, with its author: a person's row is the truth, a model's a candidate. On the passage node give the words the passage starts with and the words that follow its end. Run. The stages node reads the passage and every expectation by the same steps (words, sections, grammar, parties, clause, bearer, statement, join, quads) and blames each expectation on the first step that loses it. A row's variants show whether another setting gets it further. Nothing is written to the library."], x = 40.0, y = 20.0),
        LcncNode("record", "text.value", _m["value" j ""], x = 40.0, y = 250.0),
        LcncNode("review", BookReview.TYPE, x = 390.0, y = 250.0),
        LcncNode("passage", BookReview.PASSAGE, x = 740.0, y = 250.0),
        LcncNode("stages", TYPE, x = 1090.0, y = 250.0),
        LcncNode("board", "display", x = 1440.0, y = 60.0),
        LcncNode("blame", "display", x = 1440.0, y = 260.0),
        LcncNode("reading", "display", x = 1440.0, y = 460.0),
        LcncNode("range", "display", x = 1090.0, y = 520.0),
        LcncNode("withheld", "display", x = 740.0, y = 520.0),
    ], s_[LcncWire("record", "value", "review", "record?"), LcncWire("review", "prerequisites", "passage", "prerequisites"),
        LcncWire("passage", "text", "stages", "text"), LcncWire("review", "expectations", "stages", "expectations?"),
        LcncWire("stages", "board", "board", "x"), LcncWire("stages", "blame", "blame", "x"), LcncWire("stages", "passage", "reading", "x"),
        LcncWire("passage", "range", "range", "x"), LcncWire("review", "withheld", "withheld", "x")],
        view = LcncView(x = 20.0, y = 20.0, zoom = 0.5), seq = 10)
}
