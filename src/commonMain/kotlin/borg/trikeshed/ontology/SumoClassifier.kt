package borg.trikeshed.ontology

import borg.trikeshed.collections.associative.FunnelHashIndex
import borg.trikeshed.collections.associative.LinearHashMap
import borg.trikeshed.collections.bits.ClosureIndex
import borg.trikeshed.collections.bits.IntAccumulator
import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.kif.KifExpr
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.α
import kotlin.jvm.JvmInline

enum class SumoMask { ANCESTORS, DESCENDANTS, INSTANCES, DISJOINT }

/**
 * The projected taxonomy of a SUO-KIF corpus, int-coded: term names in first-seen order,
 * flat `(a, b)` term id pairs for subclass, instance and disjoint edges, domain and range
 * slots as parallel columns, and `counts` = forms, taxonomyForms, unprojectedTaxonomyForms, rules.
 * [SumoClassifier.of] builds the same classifier from it as from the forms it was projected from.
 */
class SumoTaxonomy(
    val names: Array<String>,
    val isClass: BooleanArray,
    val subclassEdges: IntArray,
    val instanceEdges: IntArray,
    val disjointPairs: IntArray,
    val domainKeys: Array<String>,
    val domainClass: IntArray,
    val domainSubclass: BooleanArray,
    val rangeKeys: Array<String>,
    val rangeClass: IntArray,
    val rangeSubclass: BooleanArray,
    val counts: IntArray,
)

@JvmInline value class SumoClassId(val value: Int)

/**
 * SUMO as a classifier that is bitset in shape.
 *
 * Built from SUO-KIF forms — the pinned Merge + Mid-level corpus (15,551 forms,
 * 3,685 indexed taxonomy terms, 2,504 classes), the separate full corpus, or any subset.
 * Only top-level atomic taxonomy declarations enter the index. Rules, other
 * predicates, and taxonomy forms with functional expressions are counted in
 * [stats] but are not inferred or flattened into subclass/instance edges.
 * Everything the classifier
 * answers is a bit test over Roaring sets keyed by DFS-preorder class ids
 * ([ClosureIndex]):
 *
 *  - `subclass`/`instance` closure — [isA], [classesOf], [subclassesOf];
 *  - `domain`/`domainSubclass`/`range`/`rangeSubclass` — argument and result
 *    type constraints, checked as membership ([domainOk], [rangeOf]);
 *  - `disjoint`/`partition`/`disjointDecomposition` — one inherited disjoint
 *    mask per class ([disjoint]);
 *  - a literal's place in the Number subtree — [numberClassesOf], so a numeric
 *    argument slot is checked the same way as a term slot ([domainOkLiteral]).
 *
 * `isA` is SUMO's union reading: `Human` is-a `Organism` as a class and is-a
 * `Abstract` as a term (every class is an instance of `Class` ⊂ `SetOrClass`);
 * [subclassOf] is the class reading alone.
 *
 * Deliberately NOT here (owner's ruling, 2026-09-04): ordering (`greaterThan`…),
 * arithmetic (`MultiplicationFn`…) and `MeasureFn` unit algebra. They are not
 * hierarchical booleans; the plane's numbers live in Rete interests and NAL.
 */
class SumoClassifier private constructor(
    private val names: Array<String>,
    /** Frozen term name → term id (its index in [names]). */
    private val termIndex: FunnelHashIndex<String>,
    /** term → class index, or -1 when the term is not a class */
    private val classOfTerm: IntArray,
    /** class index → term */
    private val termOfClass: IntArray,
    private val closure: ClosureIndex,
    /** term → preorder-id set of every class it is an instance of (closed upward) */
    private val instanceTypes: Array<RoaringSeries>,
    private val declaredDisjoint: Array<IntArray>,
    private val domains: LinearHashMap<String, Int>,
    private val domainSubclass: LinearHashMap<String, Boolean>,
    private val ranges: LinearHashMap<String, Int>,
    private val rangeSubclass: LinearHashMap<String, Boolean>,
    val stats: Map<String, Int>,
) {
    val termCount: Int get() = names.size
    val classCount: Int get() = termOfClass.size

    /** Class IDs are the classifier's own preorder IDs, not the KIF bank's IDs. */
    fun classId(term: String): SumoClassId? = classIndex(term).takeIf { it >= 0 }?.let { SumoClassId(closure.id(it)) }

    fun className(id: SumoClassId): String = names[termOfClass[closure.node(id.value)]]

    fun mask(term: String, kind: SumoMask): RoaringSeries {
        val t = termId(term)
        if (t < 0) return RoaringSeries.EMPTY
        if (kind == SumoMask.INSTANCES) return instanceTypes[t]
        val c = classOfTerm[t]
        if (c < 0) return RoaringSeries.EMPTY
        return when (kind) {
            SumoMask.ANCESTORS -> closure.ancestorIds(c)
            SumoMask.DESCENDANTS -> closure.descendantIds(c)
            SumoMask.DISJOINT -> disjointMask(c)
            SumoMask.INSTANCES -> instanceTypes[t]
        }
    }

    val domainSlots: Series2<String, String>
        get() {
            // Capture array references locally: the inlined projection's JVM class
            // must not reach through to private fields on SumoClassifier.
            val names = names
            val termOfClass = termOfClass
            return domains.entries() α { it.a j names[termOfClass[it.b]] }
        }

    val rangeSlots: Series2<String, String>
        get() {
            val names = names
            val termOfClass = termOfClass
            return ranges.entries() α { it.a j names[termOfClass[it.b]] }
        }

    fun domainIsSubclass(predicate: String, argIdx: Int): Boolean = domainSubclass["$predicate/$argIdx"] == true

    /** Every term in pool order. */
    val terms: Series<String> get() = names.size j { i: Int -> names[i] }

    fun termId(name: String): Int = termIndex.get(name) ?: -1
    fun isClass(name: String): Boolean = termId(name).let { it >= 0 && classOfTerm[it] >= 0 }

    private fun classIndex(name: String): Int = termId(name).let { if (it < 0) -1 else classOfTerm[it] }

    /** Preorder ids of every class [term] belongs to: its superclasses when it is a class, its types when it is an instance, both when it is both. */
    private fun classIdsOf(termId: Int): RoaringSeries {
        val c = classOfTerm[termId]
        val asClass = if (c >= 0) closure.selfAndAncestorIds(c) else RoaringSeries.EMPTY
        return asClass or instanceTypes[termId]
    }

    /**
     * Is [term] a [cls]? For a class this is the subclass closure (reflexive);
     * for an instance it is the instance-of closure; a term that is both (a
     * class that is also an instance of something) gets both.
     */
    fun isA(term: String, cls: String): Boolean {
        val t = termId(term); val c = classIndex(cls)
        if (t < 0 || c < 0) return false
        return classIdsOf(t).contains(closure.id(c))
    }

    /** The class reading alone: is class [sub] a (reflexive, transitive) subclass of [sup]? */
    fun subclassOf(sub: String, sup: String): Boolean {
        val a = classIndex(sub); val b = classIndex(sup)
        return a >= 0 && b >= 0 && closure.isA(a, b)
    }

    /** Names of every class [term] belongs to, root-most first. */
    fun classesOf(term: String): Series<String> {
        val t = termId(term)
        if (t < 0) return 0 j { _: Int -> "" }
        return namesOfIds(classIdsOf(t))
    }

    /** Proper subclasses of [cls], root-most first. */
    fun subclassesOf(cls: String): Series<String> {
        val c = classIndex(cls)
        if (c < 0) return 0 j { _: Int -> "" }
        return namesOfIds(closure.descendantIds(c))
    }

    /** Proper superclasses of [cls], root-most first. */
    fun superclassesOf(cls: String): Series<String> {
        val c = classIndex(cls)
        if (c < 0) return 0 j { _: Int -> "" }
        return namesOfIds(closure.ancestorIds(c))
    }

    /** Declared class of argument [argIdx] (1-based) of [predicate], or null when SUMO declares none. */
    fun domainOf(predicate: String, argIdx: Int): String? = domains["$predicate/$argIdx"]?.let { names[termOfClass[it]] }

    /**
     * Does [term] satisfy the declared domain of [predicate]'s argument
     * [argIdx]? An undeclared slot is unconstrained (true). `domainSubclass`
     * slots require a CLASS under the declared one; `domain` slots accept an
     * instance of it (or, as SUMO does, a class that is itself an instance).
     */
    fun domainOk(predicate: String, argIdx: Int, term: String): Boolean {
        val key = "$predicate/$argIdx"
        val c = domains[key] ?: return true
        val t = termId(term)
        if (t < 0) return false
        val id = closure.id(c)
        return if (domainSubclass[key] == true) {
            val tc = classOfTerm[t]
            tc >= 0 && closure.selfAndAncestorIds(tc).contains(id)
        } else classIdsOf(t).contains(id)
    }

    /** The literal form of [domainOk]: a number in a slot typed under Number / Quantity. */
    fun domainOkLiteral(predicate: String, argIdx: Int, literal: String): Boolean {
        val key = "$predicate/$argIdx"
        val c = domains[key] ?: return true
        return numberIdsOf(literal).contains(closure.id(c))
    }

    /** Declared result class of a function, or null. */
    fun rangeOf(function: String): String? = ranges[function]?.let { names[termOfClass[it]] }
    fun rangeIsSubclass(function: String): Boolean = rangeSubclass[function] == true

    private val inheritedMask = arrayOfNulls<RoaringSeries>(termOfClass.size)

    /** Every class disjoint from class [c] by declaration on [c] or any ancestor, closed downward. */
    private fun disjointMask(c: Int): RoaringSeries {
        inheritedMask[c]?.let { return it }
        val acc = IntAccumulator()
        val selfAndUp = closure.selfAndAncestorIds(c)
        selfAndUp.forEach { aid ->
            val a = closure.node(aid)
            for (d in declaredDisjoint[a]) { acc.add(closure.id(d)); acc.addAll(closure.descendantIds(d)) }
        }
        return acc.toRoaring().also { inheritedMask[c] = it }
    }

    /** Are the classes [a] and [b] disjoint — by a `disjoint`, `partition` or `disjointDecomposition` on them or their ancestors? */
    fun disjoint(a: String, b: String): Boolean {
        val ca = classIndex(a); val cb = classIndex(b)
        if (ca < 0 || cb < 0) return false
        return disjointMask(ca).intersects(closure.selfAndAncestorIds(cb))
    }

    /** The Number-subtree classes a literal belongs to, closed upward (so Quantity, Abstract, Entity come along). */
    fun numberClassesOf(literal: String): Series<String> = namesOfIds(numberIdsOf(literal))

    private fun numberIdsOf(literal: String): RoaringSeries {
        val d = literal.toDoubleOrNull() ?: return RoaringSeries.EMPTY
        if (d.isNaN() || d.isInfinite()) return RoaringSeries.EMPTY
        val leaves = ArrayList<String>()
        leaves += "RealNumber"; leaves += "RationalNumber"
        if (d > 0) leaves += "PositiveRealNumber"
        if (d < 0) leaves += "NegativeRealNumber"
        if (d >= 0) leaves += "NonnegativeRealNumber"
        val l = literal.toLongOrNull() ?: d.let { if (it == kotlin.math.floor(it) && kotlin.math.abs(it) < 9.0e15) it.toLong() else null }
        if (l != null) {
            leaves += "Integer"
            if (l > 0) leaves += "PositiveInteger"
            if (l < 0) leaves += "NegativeInteger"
            if (l >= 0) leaves += "NonnegativeInteger"
            leaves += if (l % 2 == 0L) "EvenInteger" else "OddInteger"
        }
        val acc = IntAccumulator()
        for (name in leaves) {
            val c = classIndex(name)
            if (c >= 0) { acc.add(closure.id(c)); acc.addAll(closure.ancestorIds(c)) }
        }
        return acc.toRoaring()
    }

    private fun namesOfIds(ids: RoaringSeries): Series<String> {
        val arr = ids.toIntArray()
        return arr.size j { i: Int -> names[termOfClass[closure.node(arr[i])]] }
    }

    /** Container shapes across the closure's stored sets. */
    fun shapeHistogram(): Map<String, Int> = closure.shapeHistogram()

    companion object {
        private const val TERM_SEED = 0x5355_4D4FL
        private val CLASS_SLOTS = setOf("subclass", "instance", "domain", "domainSubclass", "range", "rangeSubclass", "disjoint", "partition", "disjointDecomposition", "exhaustiveDecomposition")

        fun parse(kif: String): SumoClassifier = of(KifExpr.parseAll(kif))

        fun of(forms: List<KifExpr>): SumoClassifier = of(forms as Iterable<KifExpr>)

        /** Consumes forms once, allowing independently parsed corpus files to be released in order. */
        fun of(forms: Iterable<KifExpr>): SumoClassifier = of(taxonomy(forms))

        /** Project the atomic taxonomy declarations of [forms] into a [SumoTaxonomy]. */
        fun taxonomy(forms: Iterable<KifExpr>): SumoTaxonomy {
            val names = ArrayList<String>()
            val termIndex = LinearHashMap<String, Int>(8192)
            fun term(name: String): Int = termIndex[name] ?: names.size.also { names.add(name); termIndex[name] = it }
            val isClass = HashSet<Int>()
            val subclassEdges = IntAccumulator(8192) // (sub, sup) term ids, flat
            val instanceEdges = IntAccumulator(8192)
            val disjointPairs = IntAccumulator(1024)
            val domainKeys = ArrayList<String>(); val domainClass = IntAccumulator(2048); val domainSub = ArrayList<Boolean>()
            val rangeKeys = ArrayList<String>(); val rangeClass = IntAccumulator(512); val rangeSub = ArrayList<Boolean>()
            var rules = 0
            var formCount = 0
            var taxonomyForms = 0
            var unprojectedTaxonomyForms = 0

            fun atom(e: KifExpr): String? = (e as? KifExpr.Atom)?.token?.takeIf { !it.startsWith("?") && !it.startsWith("\"") }
            fun pair(acc: IntAccumulator, a: Int, b: Int) { acc.add(a); acc.add(b) }

            for (f in forms) {
                formCount++
                val list = f as? KifExpr.ListExpr ?: continue
                val head = atom(list.elements.firstOrNull() ?: continue) ?: continue
                val args = list.elements.drop(1)
                if (head in CLASS_SLOTS) {
                    val arity = when (head) {
                        "domain", "domainSubclass" -> args.size == 3 && atom(args[1])?.toIntOrNull() != null
                        "partition", "disjointDecomposition", "exhaustiveDecomposition" -> args.size >= 3
                        else -> args.size == 2
                    }
                    if (!arity || args.any { atom(it) == null }) { unprojectedTaxonomyForms++; continue }
                    taxonomyForms++
                }
                when (head) {
                    "=>", "<=>" -> rules++
                    "subclass" -> if (args.size == 2) {
                        val a = atom(args[0]); val b = atom(args[1])
                        if (a != null && b != null) { val ta = term(a); val tb = term(b); isClass += ta; isClass += tb; pair(subclassEdges, ta, tb) }
                    }
                    "instance" -> if (args.size == 2) {
                        val a = atom(args[0]); val b = atom(args[1])
                        if (a != null && b != null) { val ta = term(a); val tb = term(b); isClass += tb; pair(instanceEdges, ta, tb) }
                    }
                    "domain", "domainSubclass" -> if (args.size == 3) {
                        val p = atom(args[0]); val n = atom(args[1])?.toIntOrNull(); val c = atom(args[2])
                        if (p != null && n != null && c != null) {
                            term(p); val tc = term(c); isClass += tc
                            domainKeys += "$p/$n"; domainClass.add(tc); domainSub += head == "domainSubclass"
                        }
                    }
                    "range", "rangeSubclass" -> if (args.size == 2) {
                        val p = atom(args[0]); val c = atom(args[1])
                        if (p != null && c != null) {
                            term(p); val tc = term(c); isClass += tc
                            rangeKeys += p; rangeClass.add(tc); rangeSub += head == "rangeSubclass"
                        }
                    }
                    "disjoint" -> if (args.size == 2) {
                        val a = atom(args[0]); val b = atom(args[1])
                        if (a != null && b != null) { val ta = term(a); val tb = term(b); isClass += ta; isClass += tb; pair(disjointPairs, ta, tb) }
                    }
                    "partition", "disjointDecomposition" -> if (args.size >= 3) {
                        val whole = atom(args[0]) ?: continue
                        isClass += term(whole)
                        val parts = args.drop(1).mapNotNull { atom(it) }.map { term(it).also { t -> isClass += t } }
                        for (i in parts.indices) for (j in i + 1 until parts.size) pair(disjointPairs, parts[i], parts[j])
                    }
                    "exhaustiveDecomposition" -> args.mapNotNull { atom(it) }.forEach { isClass += term(it) }
                }
            }
            return SumoTaxonomy(
                names.toTypedArray(), BooleanArray(names.size) { it in isClass },
                subclassEdges.toIntArray(), instanceEdges.toIntArray(), disjointPairs.toIntArray(),
                domainKeys.toTypedArray(), domainClass.toIntArray(), domainSub.toBooleanArray(),
                rangeKeys.toTypedArray(), rangeClass.toIntArray(), rangeSub.toBooleanArray(),
                intArrayOf(formCount, taxonomyForms, unprojectedTaxonomyForms, rules),
            )
        }

        /** Build the classifier from a projected taxonomy: term ids, closure and slot maps. */
        fun of(t: SumoTaxonomy): SumoClassifier {
            val names = t.names
            val termIndex = FunnelHashIndex.build(names.toSeries(), TERM_SEED)
            val classOfTerm = IntArray(names.size) { -1 }
            val termOfClass = IntArray(t.isClass.count { it })
            var c = 0
            for (term in names.indices) if (t.isClass[term]) { classOfTerm[term] = c; termOfClass[c] = term; c++ }
            val parentLists = Array(termOfClass.size) { IntAccumulator(4) }
            for (i in 0 until t.subclassEdges.size step 2) parentLists[classOfTerm[t.subclassEdges[i]]].add(classOfTerm[t.subclassEdges[i + 1]])
            val closure = ClosureIndex.build(termOfClass.size) { ci -> parentLists[ci].toRoaring().toIntArray() }

            val directTypes = Array(names.size) { IntAccumulator(2) }
            for (i in 0 until t.instanceEdges.size step 2) directTypes[t.instanceEdges[i]].add(classOfTerm[t.instanceEdges[i + 1]])
            // SUMO's implicit typing: every class is an instance of Class ((domain subclass 1 Class),
            // Class ⊂ SetOrClass), so a class term satisfies a `domain … Class` slot without a
            // spelled-out `(instance X Class)`.
            val classClass = termIndex.get("Class")?.let { classOfTerm[it] } ?: -1
            if (classClass >= 0) for (term in names.indices) if (classOfTerm[term] >= 0) directTypes[term].add(classClass)
            val instanceTypes = Array(names.size) { term ->
                val acc = IntAccumulator(8)
                directTypes[term].toRoaring().forEach { ci -> acc.add(closure.id(ci)); acc.addAll(closure.ancestorIds(ci)) }
                acc.toRoaring()
            }

            val disjointLists = Array(termOfClass.size) { IntAccumulator(2) }
            for (i in 0 until t.disjointPairs.size step 2) {
                val a = classOfTerm[t.disjointPairs[i]]; val b = classOfTerm[t.disjointPairs[i + 1]]
                disjointLists[a].add(b); disjointLists[b].add(a)
            }
            val declaredDisjoint = Array(termOfClass.size) { disjointLists[it].toRoaring().toIntArray() }

            val domains = LinearHashMap<String, Int>(2048); val domainSubclass = LinearHashMap<String, Boolean>(512)
            for (i in t.domainKeys.indices) { domains[t.domainKeys[i]] = classOfTerm[t.domainClass[i]]; domainSubclass[t.domainKeys[i]] = t.domainSubclass[i] }
            val ranges = LinearHashMap<String, Int>(512); val rangeSubclass = LinearHashMap<String, Boolean>(128)
            for (i in t.rangeKeys.indices) { ranges[t.rangeKeys[i]] = classOfTerm[t.rangeClass[i]]; rangeSubclass[t.rangeKeys[i]] = t.rangeSubclass[i] }

            val (formCount, taxonomyForms, unprojectedTaxonomyForms, rules) = t.counts
            val stats = linkedMapOf(
                "forms" to formCount, "taxonomyForms" to taxonomyForms,
                "unprojectedTaxonomyForms" to unprojectedTaxonomyForms,
                "otherForms" to formCount - taxonomyForms - unprojectedTaxonomyForms - rules,
                "terms" to names.size, "classes" to termOfClass.size,
                "subclassEdges" to t.subclassEdges.size / 2, "instanceEdges" to t.instanceEdges.size / 2,
                "domainSlots" to t.domainKeys.size, "rangeSlots" to t.rangeKeys.size,
                "disjointPairs" to t.disjointPairs.size / 2, "rules" to rules,
                "closureBytes" to closure.byteSize(),
            )
            return SumoClassifier(names, termIndex, classOfTerm, termOfClass, closure, instanceTypes, declaredDisjoint, domains, domainSubclass, ranges, rangeSubclass, stats)
        }
    }
}
