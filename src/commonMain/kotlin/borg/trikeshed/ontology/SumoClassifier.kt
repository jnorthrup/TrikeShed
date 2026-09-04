package borg.trikeshed.ontology

import borg.trikeshed.collections.associative.LinearHashMap
import borg.trikeshed.collections.bits.ClosureIndex
import borg.trikeshed.collections.bits.IntAccumulator
import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.kif.KifExpr
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * SUMO as a classifier that is bitset in shape.
 *
 * Built from SUO-KIF forms — the pinned Merge + Mid-level corpus (5,279 terms,
 * 2,558 classes on 2026-09-04) or any subset. Everything the classifier
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
    private val termIndex: LinearHashMap<String, Int>,
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

    /** Every term in pool order. */
    val terms: Series<String> get() = names.size j { i: Int -> names[i] }

    fun termId(name: String): Int = termIndex[name] ?: -1
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
        private val CLASS_SLOTS = setOf("subclass", "instance", "domain", "domainSubclass", "range", "rangeSubclass", "disjoint", "partition", "disjointDecomposition", "exhaustiveDecomposition")

        fun parse(kif: String): SumoClassifier = of(KifExpr.parseAll(kif))

        fun of(forms: List<KifExpr>): SumoClassifier {
            val names = ArrayList<String>()
            val termIndex = LinearHashMap<String, Int>(8192)
            fun term(name: String): Int = termIndex[name] ?: names.size.also { names.add(name); termIndex[name] = it }
            val isClass = HashSet<Int>()
            val subclassEdges = ArrayList<IntArray>() // (sub, sup) term ids
            val instanceEdges = ArrayList<IntArray>()
            val disjointPairs = ArrayList<IntArray>()
            data class Slot(val key: String, val cls: Int, val subclass: Boolean)
            val domainSlots = ArrayList<Slot>()
            val rangeSlots = ArrayList<Slot>()
            var rules = 0

            fun atom(e: KifExpr): String? = (e as? KifExpr.Atom)?.token?.takeIf { !it.startsWith("?") && !it.startsWith("\"") }

            for (f in forms) {
                val list = f as? KifExpr.ListExpr ?: continue
                val head = atom(list.elements.firstOrNull() ?: continue) ?: continue
                val args = list.elements.drop(1)
                when (head) {
                    "=>", "<=>" -> rules++
                    "subclass" -> if (args.size == 2) {
                        val a = atom(args[0]); val b = atom(args[1])
                        if (a != null && b != null) { val ta = term(a); val tb = term(b); isClass += ta; isClass += tb; subclassEdges += intArrayOf(ta, tb) }
                    }
                    "instance" -> if (args.size == 2) {
                        val a = atom(args[0]); val b = atom(args[1])
                        if (a != null && b != null) { val ta = term(a); val tb = term(b); isClass += tb; instanceEdges += intArrayOf(ta, tb) }
                    }
                    "domain", "domainSubclass" -> if (args.size == 3) {
                        val p = atom(args[0]); val n = atom(args[1])?.toIntOrNull(); val c = atom(args[2])
                        if (p != null && n != null && c != null) { term(p); val tc = term(c); isClass += tc; domainSlots += Slot("$p/$n", tc, head == "domainSubclass") }
                    }
                    "range", "rangeSubclass" -> if (args.size == 2) {
                        val p = atom(args[0]); val c = atom(args[1])
                        if (p != null && c != null) { term(p); val tc = term(c); isClass += tc; rangeSlots += Slot(p, tc, head == "rangeSubclass") }
                    }
                    "disjoint" -> if (args.size == 2) {
                        val a = atom(args[0]); val b = atom(args[1])
                        if (a != null && b != null) { val ta = term(a); val tb = term(b); isClass += ta; isClass += tb; disjointPairs += intArrayOf(ta, tb) }
                    }
                    "partition", "disjointDecomposition" -> if (args.size >= 3) {
                        val whole = atom(args[0]) ?: continue
                        isClass += term(whole)
                        val parts = args.drop(1).mapNotNull { atom(it) }.map { term(it).also { t -> isClass += t } }
                        for (i in parts.indices) for (j in i + 1 until parts.size) disjointPairs += intArrayOf(parts[i], parts[j])
                    }
                    "exhaustiveDecomposition" -> args.mapNotNull { atom(it) }.forEach { isClass += term(it) }
                }
            }

            val classOfTerm = IntArray(names.size) { -1 }
            val termOfClass = IntArray(isClass.size)
            var c = 0
            for (t in names.indices) if (t in isClass) { classOfTerm[t] = c; termOfClass[c] = t; c++ }
            val parentLists = Array(termOfClass.size) { IntAccumulator(4) }
            for ((sub, sup) in subclassEdges.map { it[0] to it[1] }) parentLists[classOfTerm[sub]].add(classOfTerm[sup])
            val closure = ClosureIndex.build(termOfClass.size) { ci -> parentLists[ci].toRoaring().toIntArray() }

            val directTypes = Array(names.size) { IntAccumulator(2) }
            for (e in instanceEdges) directTypes[e[0]].add(classOfTerm[e[1]])
            // SUMO's implicit typing: every class is an instance of Class ((domain subclass 1 Class),
            // Class ⊂ SetOrClass), so a class term satisfies a `domain … Class` slot without a
            // spelled-out `(instance X Class)`.
            val classClass = termIndex["Class"]?.let { classOfTerm[it] } ?: -1
            if (classClass >= 0) for (t in names.indices) if (classOfTerm[t] >= 0) directTypes[t].add(classClass)
            val instanceTypes = Array(names.size) { t ->
                val acc = IntAccumulator(8)
                directTypes[t].toRoaring().forEach { ci -> acc.add(closure.id(ci)); acc.addAll(closure.ancestorIds(ci)) }
                acc.toRoaring()
            }

            val disjointLists = Array(termOfClass.size) { IntAccumulator(2) }
            for (p in disjointPairs) { disjointLists[classOfTerm[p[0]]].add(classOfTerm[p[1]]); disjointLists[classOfTerm[p[1]]].add(classOfTerm[p[0]]) }
            val declaredDisjoint = Array(termOfClass.size) { disjointLists[it].toRoaring().toIntArray() }

            val domains = LinearHashMap<String, Int>(2048); val domainSubclass = LinearHashMap<String, Boolean>(512)
            for (s in domainSlots) { domains[s.key] = classOfTerm[s.cls]; domainSubclass[s.key] = s.subclass }
            val ranges = LinearHashMap<String, Int>(512); val rangeSubclass = LinearHashMap<String, Boolean>(128)
            for (s in rangeSlots) { ranges[s.key] = classOfTerm[s.cls]; rangeSubclass[s.key] = s.subclass }

            val stats = linkedMapOf(
                "terms" to names.size, "classes" to termOfClass.size,
                "subclassEdges" to subclassEdges.size, "instanceEdges" to instanceEdges.size,
                "domainSlots" to domainSlots.size, "rangeSlots" to rangeSlots.size,
                "disjointPairs" to disjointPairs.size, "rules" to rules,
                "closureBytes" to closure.byteSize(),
            )
            return SumoClassifier(names.toTypedArray(), termIndex, classOfTerm, termOfClass, closure, instanceTypes, declaredDisjoint, domains, domainSubclass, ranges, rangeSubclass, stats)
        }
    }
}
