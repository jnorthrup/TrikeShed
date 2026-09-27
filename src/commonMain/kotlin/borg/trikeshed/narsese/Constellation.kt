package borg.trikeshed.narsese

import borg.trikeshed.collections.bits.IntAccumulator
import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.lib.packInts

/** Deontic force of a norm: obligation, permission, prohibition, or a presumption stated in the present tense. */
enum class Modality {
    MUST, MAY, MUST_NOT, PRESUMED;

    val key: String get() = name.lowercase().replace('_', '-')

    /** MUST_NOT opposes every affirmative force. */
    fun opposes(other: Modality): Boolean = (this == MUST_NOT) != (other == MUST_NOT)

    companion object {
        fun of(c: NormClause): Modality = when {
            !c.affirmative -> MUST_NOT
            c.modal == "generic" || c.modal == "would" -> PRESUMED
            c.modal == "may" || c.modal == "can" -> MAY
            else -> MUST
        }
    }
}

/**
 * One normalized norm. Its [key] is its identity in every book: the same bearer, force, action,
 * object and condition stated anywhere is the same statement. [proposition] drops the force, so two
 * statements sharing it with opposing force are a conflict.
 */
data class NormStatement(
    val bearer: String,
    val modality: Modality,
    val action: String,
    val obj: String?,
    val condition: String?,
    /** Preorder SUMO class id the bearer resolves to, or -1; a typing of the bearer, not part of its identity. */
    val bearerClass: Int = -1,
) {
    val proposition: String get() = listOf(bearer, action, obj.orEmpty(), condition.orEmpty()).joinToString("|")
    val key: String get() = modality.key + "|" + proposition

    /** Stable address: FNV-1a 64 of [key], hex. */
    val id: String get() {
        var h = -0x340d631b7bdddcdbL
        for (ch in key) { h = h xor ch.code.toLong(); h *= 0x100000001b3L }
        return h.toULong().toString(16).padStart(16, '0')
    }

    /** The predicate term as the rule engine names it: action and object. */
    val predicate: String get() = listOfNotNull(action, obj).joinToString("_") { it.replace(' ', '_') }

    /** The proposition without its bearer: what a class of bearers is said to do. */
    val predication: String get() = listOf(action, obj.orEmpty(), condition.orEmpty()).joinToString("|")

    /** The rule's consequent keeps the force: `must_pay_debt` and `may_pay_debt` are different law. */
    val consequent: String get() = modality.key.replace('-', '_') + "_" + predicate

    /** What must hold for the norm to apply: the condition without its mark ("rent be due"). */
    val premise: String? get() = condition?.substringAfter(' ')

    /** An `unless` condition: the norm applies while its premise is absent. */
    val unless: Boolean get() = condition?.startsWith("unless ") == true

    /** The rule's antecedent: the bearer, conjoined with the condition when one opens the clause. */
    val antecedent: String get() = condition?.let { "(&&,$bearer,${it.replace(' ', '_')})" } ?: bearer

    /**
     * The statement as an English sentence the parser reads back to this statement: the return edge
     * from atoms to text. A statement whose sentence parses to a different key is round-trip drift.
     */
    val sentence: String get() {
        val force = when (modality) { Modality.MUST -> "must"; Modality.MAY -> "may"; Modality.MUST_NOT -> "must not"; Modality.PRESUMED -> "will" }
        val verb = action.replace('_', ' ')
        return listOfNotNull("The $bearer", force, verb, obj?.let { "the $it" }, condition?.replace(Regex("\\bbe\\b"), "is")).joinToString(" ") + "."
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "bearer" to bearer, "modality" to modality.key, "action" to action,
        "object" to obj, "condition" to condition, "bearerClass" to bearerClass,
    )

    companion object {
        fun of(c: NormClause, classOf: (NormClause) -> Int = { -1 }) =
            NormStatement(c.subject, Modality.of(c), c.verb, c.obj, c.condition, classOf(c))
    }
}

/**
 * One edition, curated: section headings in order, its distinct statements, and for each statement
 * the ordinals of the sections that state it. Frozen once built.
 */
class Book(
    val name: String,
    val work: String,
    val date: String,
    val headings: List<String>,
    val statements: List<NormStatement>,
    val support: List<IntArray>,
    /** Facts the book states, in premise form: what answers another book's conditions. */
    val facts: Set<String> = emptySet(),
    /** Section → section pointers found in its prose (the citation tupler), as (from, to) ordinals. */
    val cites: List<Pair<Int, Int>> = emptyList(),
) {
    init { require(statements.size == support.size) }

    companion object {
        /** A section's heading: its opening line, where the section tupler cut it, bounded for display. */
        fun heading(section: String): String = section.take(HEADING).substringBefore(". ", section.take(HEADING)).trim()

        const val HEADING = 120

        fun of(name: String, work: String, date: String, sections: List<String>, clauses: List<List<NormClause>>,
               classOf: (NormClause) -> Int = { -1 }, facts: Set<String> = emptySet(), cites: List<Pair<Int, Int>> = emptyList()): Book {
            val index = LinkedHashMap<String, Int>()
            val statements = ArrayList<NormStatement>()
            val sectionsOf = ArrayList<IntAccumulator>()
            for ((ordinal, found) in clauses.withIndex()) for (c in found) {
                val s = NormStatement.of(c, classOf)
                val at = index.getOrPut(s.key) { statements.add(s); sectionsOf.add(IntAccumulator(4)); statements.size - 1 }
                sectionsOf[at].add(ordinal)
            }
            return Book(name, work, date, sections.map(::heading), statements, sectionsOf.map { it.toRoaring().toIntArray() }, facts, cites)
        }
    }
}

/**
 * Books joined by what they state. Section ids are global: `book ordinal shl 20 or section ordinal`,
 * so every support set across all books is one [RoaringSeries]. Joining a book touches only the
 * statements it states; no other book is recomputed.
 */
class Constellation(val name: String) {
    val books = ArrayList<Book>()
    val statements = ArrayList<NormStatement>()
    private val ids = HashMap<String, Int>()
    private val byProposition = HashMap<String, MutableList<Int>>()
    /** Statement id → global sections stating it. */
    val support = ArrayList<RoaringSeries>()
    /** Book ordinal → statement ids it states. */
    val statementsOf = ArrayList<RoaringSeries>()
    /** "a~b" → edition alignment of book a to book b. */
    val alignments = LinkedHashMap<String, Alignment>()
    /** SUMO class id → statements whose bearer is typed by it: the alpha memory. */
    val bearing = HashMap<Int, RoaringSeries>()

    /** Premise → statements conditioned on it: the beta memory a join probes with the facts that hold. */
    val premised = HashMap<String, RoaringSeries>()
    /** Facts stated by any joined book: the join's standing working memory. */
    val facts = LinkedHashSet<String>()

    /** Premises no joined book states as a fact: the open questions. */
    fun open(): Map<String, RoaringSeries> = premised.filterKeys { it !in facts }

    /** Statements with no condition: they pass the join unconditionally. */
    var unconditioned = RoaringSeries.EMPTY
        private set

    /** Statements whose condition is `unless`: they fire on the premise's absence. */
    var unless = RoaringSeries.EMPTY
        private set

    /**
     * NAL evidence per proposition: every section stating it is one source, affirmative force
     * positive and MUST_NOT negative, so opposing statements revise one belief and a section never
     * counts twice. Key: packInts(proposition ordinal, 0).
     */
    val propositions = EvidenceLedger()
    private val propositionIds = HashMap<String, Int>()

    /**
     * The same evidence keyed by what the bearer's SUMO class does: packInts(class id, predication
     * ordinal). What SUMO deduction reads: a class's norms inherit down is-a to its subclasses.
     */
    val predications = EvidenceLedger()
    private val predicationIds = HashMap<String, Int>()
    val predicationNames = ArrayList<String>()
    private val predicationStatements = HashMap<Long, IntAccumulator>()

    private fun predicationId(p: String): Int = predicationIds.getOrPut(p) { predicationNames.size.also { predicationNames.add(p) } }

    fun bookOrdinal(name: String): Int = books.indexOfFirst { it.name == name }

    companion object {
        /** SUMO subclass/instance edges are axiomatic: near-certain premises for deduction. */
        val IS_A = TruthCoord(1f, 0.99f)
    }

    /**
     * Statements that hold for a bearer of the class whose self+ancestor ids are [mask]: those typed
     * by the class or anything it inherits from. One OR per typed ancestor.
     */
    fun applicable(mask: RoaringSeries): RoaringSeries {
        var out = RoaringSeries.EMPTY
        mask.forEach { c -> bearing[c]?.let { out = out or it } }
        return out
    }

    /** The join's result: statements that fire, and per missing premise the statements waiting on it. */
    class Fired(val fires: RoaringSeries, val pending: Map<String, RoaringSeries>)

    /**
     * Rete join: the alpha match for the class whose self+ancestor ids are [mask], AND the beta
     * memory of the [facts] that hold. Unconditioned statements fire; a conditioned one fires when its
     * premise is a fact (an `unless` one when it is not), else it waits on its premise.
     */
    fun fire(mask: RoaringSeries, facts: Set<String>): Fired {
        val alpha = applicable(mask)
        var fires = alpha and unconditioned
        val pending = LinkedHashMap<String, RoaringSeries>()
        for ((p, ids) in premised) {
            val hit = alpha and ids
            if (hit.isEmpty()) continue
            if (p in facts) fires = fires or hit.andNot(unless)
            else {
                fires = fires or (hit and unless)
                val waiting = hit.andNot(unless)
                if (!waiting.isEmpty()) pending[p] = waiting
            }
        }
        return Fired(fires, pending)
    }

    fun join(book: Book): Int {
        require(bookOrdinal(book.name) < 0) { "book '${book.name}' already in constellation '$name'" }
        val b = books.size
        require(b < 4096) { "constellation '$name' is full" }
        books.add(book)
        val mine = IntAccumulator(book.statements.size)
        for ((i, s) in book.statements.withIndex()) {
            val id = ids.getOrPut(s.key) {
                statements.add(s); support.add(RoaringSeries.EMPTY)
                byProposition.getOrPut(s.proposition) { ArrayList() }.add(statements.size - 1)
                val one = RoaringSeries.of(listOf(statements.size - 1))
                if (s.bearerClass >= 0) bearing[s.bearerClass] = (bearing[s.bearerClass] ?: RoaringSeries.EMPTY) or one
                s.premise?.let { p -> premised[p] = (premised[p] ?: RoaringSeries.EMPTY) or one } ?: run { unconditioned = unconditioned or one }
                if (s.unless) unless = unless or one
                statements.size - 1
            }
            val sections = book.support[i].map { (b shl 20) or it }
            support[id] = support[id] or RoaringSeries.of(sections)
            mine.add(id)
            val sign = if (s.modality == Modality.MUST_NOT) EvidenceCoord(0, Nal.UNIT) else EvidenceCoord(Nal.UNIT, 0)
            val p = propositionIds.getOrPut(s.proposition) { propositionIds.size }
            for (g in sections) propositions.observe(packInts(p, 0), g, sign)
            if (s.bearerClass >= 0) {
                val key = packInts(s.bearerClass, predicationId(s.predication))
                for (g in sections) predications.observe(key, g, sign)
                predicationStatements.getOrPut(key) { IntAccumulator(2) }.add(id)
            }
        }
        statementsOf.add(mine.toRoaring())
        facts.addAll(book.facts)
        return b
    }

    fun booksStating(id: Int): IntArray = support[id].toIntArray().map { it ushr 20 }.distinct().toIntArray()

    /** Statement ids stating the same proposition with opposing force. */
    fun opponents(id: Int): List<Int> = byProposition[statements[id].proposition].orEmpty()
        .filter { it != id && statements[it].modality.opposes(statements[id].modality) }

    /**
     * NAL truth of statement [id], read from the proposition's ledger slot: sections stating its force
     * are positive, sections stating the opposed force negative. A MUST_NOT statement reads the
     * proposition's evidence with the signs swapped.
     */
    fun truth(id: Int): TruthCoord {
        val s = statements[id]
        val e = propositions.evidence(propositions.slot(packInts(propositionIds.getValue(s.proposition), 0)))
        return Nal.truthOf(if (s.modality == Modality.MUST_NOT) EvidenceCoord(e.negative, e.positive) else e)
    }

    /** A norm a class holds: its own evidence, or what it inherits from an ancestor class by deduction, revised. */
    class Held(val predication: Int, val via: Int, val statements: IntArray, val deduced: EvidenceCoord?, val direct: EvidenceCoord?, val belief: EvidenceCoord)

    /**
     * Norms class [cls] holds, given its self+ancestor ids [lineage]. A norm stated of an ancestor class
     * is deduced down SUMO is-a (an axiomatic premise, [IS_A]); when [cls] has its own
     * evidence for the same predication and the two bases are disjoint they revise, so a specific
     * exception pulls an inherited default down; overlapping bases keep the direct evidence alone.
     */
    fun held(cls: Int, lineage: RoaringSeries): List<Held> {
        val out = LinkedHashMap<Int, Held>()
        lineage.forEach { a ->
            val stated = bearing[a] ?: return@forEach
            val seen = HashSet<Int>()
            stated.forEach { id -> seen.add(predicationIds.getValue(statements[id].predication)) }
            for (p in seen) {
                val from = predications.slot(packInts(a, p)).takeIf { it >= 0 } ?: continue
                val ids = predicationStatements[packInts(a, p)]?.toRoaring()?.toIntArray() ?: IntArray(0)
                val directSlot = predications.slot(packInts(cls, p))
                val direct = if (directSlot < 0) null else predications.evidence(directSlot)
                val held = if (a == cls) Held(p, a, ids, null, direct, direct!!) else {
                    val deduced = Nal.deduce(Nal.truthOf(predications.evidence(from)), IS_A)
                    val belief = when {
                        direct == null -> deduced
                        predications.basis(directSlot).intersects(predications.basis(from)) -> direct
                        else -> revise(direct, deduced)
                    }
                    Held(p, a, ids, deduced, direct, belief)
                }
                // Per predication: a revision (own evidence with an inheritance) beats own evidence alone,
                // which beats a bare inheritance; ties go to the more confident.
                fun rank(h: Held) = when { h.direct != null && h.deduced != null -> 2; h.direct != null -> 1; else -> 0 }
                val prior = out[p]
                if (prior == null || rank(held) > rank(prior) || rank(held) == rank(prior) &&
                        Nal.truthOf(held.belief).confidence > Nal.truthOf(prior.belief).confidence) out[p] = held
            }
        }
        return out.values.toList()
    }

    /** Statements two or more books state. */
    fun restated(): List<Int> = statements.indices.filter { booksStating(it).size >= 2 }

    /** Opposing statement pairs (lower id first). */
    fun conflicts(): List<Pair<Int, Int>> = statements.indices.flatMap { a -> opponents(a).filter { it > a }.map { a to it } }

    data class Link(val shared: Int, val jaccard: Float, val conflicts: Int)

    fun link(a: Int, b: Int, conflicts: List<Pair<Int, Int>> = conflicts()): Link {
        val sa = statementsOf[a]; val sb = statementsOf[b]
        val shared = (sa and sb).cardinality
        val union = (sa or sb).cardinality
        val opposed = conflicts.count { (x, y) ->
            (sa.contains(x) && sb.contains(y)) || (sa.contains(y) && sb.contains(x))
        }
        return Link(shared, if (union == 0) 0f else shared.toFloat() / union, opposed)
    }

    /**
     * Rules the constellation admits: statements two or more books state. An opposed statement is
     * admitted only when every opponent's latest supporting book is older than its own.
     */
    fun admissible(): List<Int> = restated().filter { id ->
        val latest = booksStating(id).maxOf { books[it].date }
        opponents(id).all { o -> booksStating(o).maxOf { books[it].date } < latest }
    }

    // ── edition alignment ────────────────────────────────────────────────────

    enum class Change { KEPT, RENUMBERED, REVISED, DROPPED, ADDED }

    data class Aligned(val from: Int, val to: Int, val change: Change, val shared: Int)

    class Alignment(val a: Int, val b: Int, val sections: List<Aligned>) {
        val counts: Map<Change, Int> get() = Change.entries.associateWith { c -> sections.count { it.change == c } }
    }

    private fun sectionNumber(heading: String): String = Regex("""§\s*([\dA-Za-z.]+?)\.?(\s|$)""").find(heading)?.groupValues?.get(1) ?: ""

    private fun titleTokens(heading: String): Set<String> =
        heading.substringAfter(sectionNumber(heading)).lowercase().split(Regex("""[^a-z]+""")).filter { it.length > 2 }.toSet()

    /** Pair each section of book [a] with the section of [b] sharing most statements; heading overlap breaks ties. */
    fun align(a: Int, b: Int): Alignment {
        val ba = books[a]; val bb = books[b]
        fun statementsBySection(book: Int): List<IntAccumulator> {
            val out = List(books[book].headings.size) { IntAccumulator(4) }
            statementsOf[book].forEach { id -> support[id].forEach { g -> if (g ushr 20 == book) out[g and 0xFFFFF].add(id) } }
            return out
        }
        val sa = statementsBySection(a).map { it.toRoaring() }
        val sb = statementsBySection(b).map { it.toRoaring() }
        val tb = bb.headings.map(::titleTokens)
        val byToken = HashMap<String, MutableList<Int>>()
        tb.forEachIndexed { i, ts -> ts.forEach { byToken.getOrPut(it) { ArrayList() }.add(i) } }
        val out = ArrayList<Aligned>()
        val claimed = BooleanArray(bb.headings.size)
        for (i in ba.headings.indices) {
            val overlap = HashMap<Int, Int>()
            sa[i].forEach { id -> support[id].forEach { g -> if (g ushr 20 == b) { val j = g and 0xFFFFF; overlap[j] = (overlap[j] ?: 0) + 1 } } }
            val ta = titleTokens(ba.headings[i])
            val candidates = overlap.keys + ta.flatMap { byToken[it].orEmpty() }
            fun headingScore(j: Int): Float {
                val u = (ta + tb[j]).size
                return if (u == 0) 0f else (ta intersect tb[j]).size.toFloat() / u
            }
            val best = candidates.maxByOrNull { j -> (overlap[j] ?: 0) + 0.5f * headingScore(j) }
            val shared = best?.let { overlap[it] } ?: 0
            val change = when {
                best == null || (shared == 0 && headingScore(best) < 0.5f) -> Change.DROPPED
                shared > 0 && sa[i].cardinality == shared && sb[best].cardinality == shared ->
                    if (sectionNumber(ba.headings[i]) == sectionNumber(bb.headings[best])) Change.KEPT else Change.RENUMBERED
                else -> Change.REVISED
            }
            if (change == Change.DROPPED) out.add(Aligned(i, -1, change, 0))
            else { claimed[best!!] = true; out.add(Aligned(i, best, change, shared)) }
        }
        for (j in bb.headings.indices) if (!claimed[j]) out.add(Aligned(-1, j, Change.ADDED, 0))
        return Alignment(a, b, out).also { alignments["${ba.name}~${bb.name}"] = it }
    }
}
