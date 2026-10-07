package borg.trikeshed.narsese

import borg.trikeshed.ontology.SumoCorpus

/**
 * Classes for nouns the lexicon cannot type, by transport through the verbs they bear. Every statement is one unit of
 * its bearer's energy. A verb is a surface: the typed bearers of the verb split what passes through it among their
 * classes, n(v,C) of n(v). A head's statements pass through the verbs they bear and land on classes in those proportions,
 *
 *     mass(C) = Σ_v n(h,v) · n(v,C) / n(v)   in milli-units, each verb's part floored,
 *
 * so what lands sums to what passed: no head counts for more than it was read, however many verbs it shares with a class.
 * A verb no typed bearer bears passes nothing, and that energy stays out of every class while still counting in the
 * head's T. Head × verb times verb × class, each row summing to one, is one product of stochastic matrices; NAL abduction
 * revised over the shared verbs instead adds a belief per verb, the head's statements counted again at every verb.
 *
 * A head's own typed statements are taken out of the surfaces it lands through, so a head is never placed by itself.
 * The classes landed on are the caps of a row like a sense row: the mass under a SUMO class sums over its closure, and a
 * class holds the head at the integer plane den·(2Σm + n) ≥ num·(2T + K) ([focus]), K the classes landed on.
 */
class PositTransport {
    private val verbs = HashMap<String, Int>()
    /** Per verb: class id → typed bearers bearing it. */
    private val landing = ArrayList<HashMap<Int, Int>>()
    /** Per verb: typed bearers bearing it, n(v). */
    private var typed = IntArray(16)
    /** Per head: verb id → statements it bears. */
    private val heads = LinkedHashMap<String, HashMap<Int, Int>>()
    /** Per head: its own typed statements, packInts(verb, class) → count. */
    private val owns = HashMap<String, HashMap<Long, Int>>()

    /** One statement: [verb] borne by a bearer typed [bearerClass] (-1 untyped), whose head is [head] when it is placed. */
    fun add(verb: String, bearerClass: Int, head: String?) {
        val v = verbs.getOrPut(verb) { landing.size.also { landing.add(HashMap()); if (it == typed.size) typed = typed.copyOf(it * 2) } }
        if (bearerClass >= 0) { landing[v].merge(bearerClass, 1, Int::plus); typed[v]++ }
        if (head == null) return
        heads.getOrPut(head) { HashMap() }.merge(v, 1, Int::plus)
        if (bearerClass >= 0) owns.getOrPut(head) { HashMap() }.merge(pack(v, bearerClass), 1, Int::plus)
    }

    /** The heads placed, in the order first read. */
    val placed: Set<String> get() = heads.keys

    /** Where [head]'s statements land: class id → milli-units, its T, and the part of T that passed a surface. */
    class Landed(val mass: Map<Int, Long>, val total: Long, val passed: Long)

    fun land(head: String): Landed {
        val borne = heads[head] ?: return Landed(emptyMap(), 0L, 0L)
        val own = owns[head].orEmpty()
        val ownByVerb = HashMap<Int, Int>()
        for ((k, n) in own) ownByVerb.merge((k ushr 32).toInt(), n, Int::plus)
        val out = HashMap<Int, Long>(); var total = 0L; var passed = 0L
        for ((v, n) in borne) {
            val units = n * Nal.UNIT
            total += units
            val nv = typed[v] - (ownByVerb[v] ?: 0)
            if (nv <= 0) continue
            passed += units
            for ((c, all) in landing[v]) {
                val ncv = all - (own[pack(v, c)] ?: 0)
                if (ncv > 0) out.merge(c, units * ncv / nv, Long::plus)
            }
        }
        return Landed(out, total, passed)
    }

    /** The [n] classes most landed on, each at least [minInformation] informative; ties to the more specific, then the lower id. */
    fun posits(landed: Landed, n: Int, minInformation: Float): IntArray =
        landed.mass.entries.filter { SumoCorpus.informationOf(it.key) >= minInformation }
            .sortedWith(compareByDescending<Map.Entry<Int, Long>> { it.value }.thenByDescending { SumoCorpus.informationOf(it.key) }.thenBy { it.key })
            .take(n).map { it.key }.toIntArray()

    /**
     * Where the landing comes to focus: the most specific class whose nested cap holds a share ≥ num/den of the head's T,
     * the classes landed on under it merged with their Jeffreys halves, or -1.
     */
    fun focus(landed: Landed, num: Long = SenseRow.HOLDS_NUM, den: Long = SenseRow.HOLDS_DEN): Int {
        val k = landed.mass.size
        if (k == 0) return -1
        val sum = HashMap<Int, Long>(); val count = HashMap<Int, Int>()
        for ((c, m) in landed.mass) SumoCorpus.closure(c).forEach { a -> sum.merge(a, m, Long::plus); count.merge(a, 1, Int::plus) }
        var best = -1; var bestIc = -1f
        val rhs = num * (2 * landed.total + k * Nal.UNIT)
        for ((a, m) in sum) {
            if (den * (2 * m + count.getValue(a) * Nal.UNIT) < rhs) continue
            val ic = SumoCorpus.informationOf(a)
            if (ic > bestIc || (ic == bestIc && a < best)) { best = a; bestIc = ic }
        }
        return best
    }

    private fun pack(v: Int, c: Int): Long = (v.toLong() shl 32) or (c.toLong() and 0xFFFF_FFFFL)
}
