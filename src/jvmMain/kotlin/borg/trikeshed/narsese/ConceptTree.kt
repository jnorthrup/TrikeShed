package borg.trikeshed.narsese

import borg.trikeshed.collections.bits.IntAccumulator
import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.lib.*
import borg.trikeshed.ontology.SumoClassId
import borg.trikeshed.ontology.SumoCorpus

/**
 * Two concept trees, one laid over the other. The super tree is SUMO's: each class's self+ancestor closure, one
 * precomputed Roaring set per class ([SumoCorpus.closure]), the same for every text. The local tree is where a word's
 * sense row ([SenseMemory.sense]) comes to focus where it was read ([Locality]).
 *
 * A row is a point on the sphere of its K classes. Every class, SUMO's classes above them included, is a cap on that
 * sphere: the classes of the row under it merged as one, holding a share ≥ 7/10 ([SenseRow.holdsAll]), an integer plane
 * on the row's counts. SUMO's closure makes the caps nested, and the mass under a class is the sum of the masses of its
 * row classes, so going up the taxonomy loses nothing. The word's focus is the most specific class whose cap holds the
 * point: its leading class when that holds alone, else the narrowest class above several of its classes that together
 * hold. A focus at T/(T + K/2) ≥ 49/50 ([SenseRow.eternal]) is a cap the word stands in, typed without asking.
 *
 * Across localities the caps of one word either agree or not. A word in one cap in two or more localities whose rows
 * cannot be told apart is a [Factor]: its locality is redundant there. A word in different caps in localities whose rows
 * differ is a [Drift]: the sense changed with the time or the English it was read in.
 */
class ConceptTree(val memory: SenseMemory?) {

    /** A word's class where it was read, its share there, and how it was typed: `cap` (eternal) or `row` (holding, not yet eternal). */
    class Typed(val cls: String, val e: Double, val by: String)

    /** The menu class a cap types [lemma] as in [at] without asking Jev, or null. */
    fun production(lemma: String, at: Locality): String? {
        val m = memory ?: return null
        val row = synchronized(m) { m.sense(lemma, at) } ?: return null
        val i = row.production()
        return if (i >= 0) m.className(row.classes[i]) else null
    }

    /** The class [lemma] denotes in [at]: the answer its row comes to ([answer]); null when there is none, and the lexicon's sense stands. */
    fun typed(lemma: String, at: Locality): Typed? {
        val m = memory ?: return null
        val row = synchronized(m) { m.sense(lemma, at) } ?: return null
        return answer(row, m)
    }

    /** The super tree's closure of the class [lemma] carries in [at]: its focus, else the lexicon's preferred sense. */
    fun closure(lemma: String, at: Locality): RoaringSeries {
        val cls = typed(lemma, at)?.let { SumoCorpus.classifier.classId(it.cls)?.value }
            ?: SumoCorpus.nounClassId(lemma).takeIf { it >= 0 } ?: SumoCorpus.nounClassId(lemma.removeSuffix("s"))
        return if (cls >= 0) SumoCorpus.closure(cls) else RoaringSeries.EMPTY
    }

    /** A word in one cap in every locality it was read in whose rows agree: [cls] across [localities], the least of its shares and confidences. */
    class Factor(val lemma: String, val cls: String, val localities: List<Locality>, val share: Double, val confidence: Double)

    /** A word in different caps in different localities: per locality, its class. */
    class Drift(val lemma: String, val classes: Series<Join<Locality, String>>)

    private class Capped(val at: Locality, val cls: String, val share: Double, val confidence: Double)

    /** True when [lemma]'s rows in [a] and [b] cannot be told apart; true when either is missing. */
    private fun agree(local: Series<Join<Locality, SenseRow>>, a: Locality, b: Locality): Boolean {
        var ra: SenseRow? = null; var rb: SenseRow? = null
        for (x in 0 until local.size) { val c = local[x]; if (c.a == a) ra = c.b; if (c.a == b) rb = c.b }
        return ra == null || rb == null || ra.homogeneous(rb)
    }

    /** Per word, the localities where its row stands in a cap. */
    private fun capped(): Map<String, Join<Series<Join<Locality, SenseRow>>, List<Capped>>> {
        val m = memory ?: return emptyMap()
        return synchronized(m) {
            val out = LinkedHashMap<String, Join<Series<Join<Locality, SenseRow>>, List<Capped>>>()
            for ((lemma, ats) in m.held().view.groupBy({ it.a }, { it.b })) {
                val caps = ats.mapNotNull { a ->
                    m.sense(lemma, a)?.takeIf { it.eternal() }?.let { r -> answer(r, m)?.let { Capped(a, it.cls, it.e, r.confidence) } }
                }
                if (caps.isNotEmpty()) out[lemma] = m.contexts(lemma) j caps
            }
            out
        }
    }

    fun factors(): List<Factor> = capped().mapNotNull { (lemma, v) ->
        val (local, cs) = v
        if (cs.size < 2 || cs.map { it.cls }.distinct().size != 1) return@mapNotNull null
        for (x in cs.indices) for (y in x + 1 until cs.size) if (!agree(local, cs[x].at, cs[y].at)) return@mapNotNull null
        Factor(lemma, cs[0].cls, cs.map { it.at }, cs.minOf { it.share }, cs.minOf { it.confidence })
    }

    fun drift(): List<Drift> = capped().mapNotNull { (lemma, v) ->
        val (local, cs) = v
        var moved = false
        for (x in cs.indices) for (y in x + 1 until cs.size)
            if (cs[x].cls != cs[y].cls && !agree(local, cs[x].at, cs[y].at)) moved = true
        if (!moved) null else Drift(lemma, cs.sortedBy { it.at.decade } α { it.at j it.cls })
    }

    companion object {
        /** The option a sense question offers for a use none of the listed senses names. */
        const val NONE = SenseMemory.NONE

        /** A word read in a locality, as a condition: the key a cap is traced under. */
        fun condition(lemma: String, at: Locality) = "(&&,$lemma,${at.term})"

        /** Class [id]'s name. */
        fun name(id: Int): String = SumoCorpus.classifier.className(SumoClassId(id))

        /** The least information content a class carries to say anything of a word: the same floor posits are held to. */
        const val INFORMATION = 2.5f

        /**
         * What [row] answers: its [focus] when that is a class it was offered, or a class above them informative enough
         * to say something ([INFORMATION]). A focus as wide as Entity, Abstract or Physical answers nothing.
         */
        fun answer(row: SenseRow, m: SenseMemory): Typed? {
            val f = focus(row, m) ?: return null
            if ((0 until row.width).any { m.className(row.classes[it]) == f.cls }) return f
            val id = SumoCorpus.classifier.classId(f.cls)?.value ?: return f
            return if (SumoCorpus.informationOf(id) >= INFORMATION) f else null
        }

        /**
         * Where [row] comes to focus: the most specific class (by information content, then id) whose nested cap holds
         * it. Each of the row's classes adds its index to every class of its SUMO closure, so one pass over the row's
         * classes gives every cap's members, and each cap is one integer plane. A row class SUMO does not name is a cap
         * of its own. Null when no cap holds the row.
         */
        fun focus(row: SenseRow, m: SenseMemory): Typed? {
            val s = scratch.get()
            val c = row.completed
            var loose = -1
            for (i in 0 until row.width) {
                if (i == row.none) continue
                val id = SumoCorpus.classifier.classId(m.className(row.classes[i]))?.value
                if (id == null) { if (row.holds(i) && (loose < 0 || row.share[i] > row.share[loose])) loose = i; continue }
                val wi = c[i]
                SumoCorpus.closure(id).forEach { a -> s.add(a, wi) }
            }
            // Each touched class is a cap: den·(2Σw + n) ≥ num·(2T + K) in units, the plane SenseRow.holdsAll draws.
            val rhs = SenseRow.HOLDS_NUM * (2.0 * row.mass + row.width * Nal.UNIT)
            var best = -1; var bestIc = -1f; var bestW = 0.0; var bestN = 0
            for (x in 0 until s.count) {
                val a = s.touched[x]; val w = s.w[a]; val n = s.n[a]
                if (SenseRow.HOLDS_DEN * (2 * w + n * Nal.UNIT) < rhs) continue
                val ic = SumoCorpus.informationOf(a)
                if (ic > bestIc || (ic == bestIc && a < best)) { best = a; bestIc = ic; bestW = w; bestN = n }
            }
            s.clear()
            val by = if (row.eternal()) "cap" else "row"
            if (best >= 0) return Typed(name(best), (bestW + bestN * Nal.UNIT / 2.0) / (row.mass + row.width * Nal.UNIT / 2.0), by)
            return if (loose >= 0) Typed(m.className(row.classes[loose]), row.share[loose], by) else null
        }

        /** Per thread, the mass and class count under each SUMO class a row touches, dense over class ids, cleared by the touched list. */
        private class Caps(size: Int) {
            val w = DoubleArray(size); val n = IntArray(size); val touched = IntArray(size); var count = 0
            fun add(a: Int, wi: Double) { if (n[a] == 0) touched[count++] = a; w[a] += wi; n[a]++ }
            fun clear() { for (x in 0 until count) { val a = touched[x]; w[a] = 0.0; n[a] = 0 }; count = 0 }
        }

        private val scratch = ThreadLocal.withInitial { Caps(SumoCorpus.classifier.classCount) }
    }
}
