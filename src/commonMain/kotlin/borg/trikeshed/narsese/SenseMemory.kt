package borg.trikeshed.narsese

import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.lib.packInts

/**
 * NARS memory of word senses. A belief `<(&&,lemma,locality) ==> class>` holds the evidence that [lemma], read in a
 * [Locality], denotes the SUMO class. One observation is one judgment of the word's use in one sentence: the judge's
 * probability p for each class offered is p of a unit positive and 1−p negative, so the classes of one judgment revise
 * together, and a source counts once in its context.
 *
 * Bounded (AIKR): at [capacity] beliefs the least attended and least confident share is forgotten at once, and a
 * context left with no belief forgets its sources too. Observation renews attention; [tick] decays it. Evidence read in
 * another locality counts here by [Locality.nearness], so a word's sense in one work leans on works near it in time and
 * in English.
 */
class SenseMemory(val capacity: Int = CAPACITY) {
    private val lemmas = ArrayList<String>()
    private val lemmaIds = HashMap<String, Int>()
    private val classes = ArrayList<String>()
    private val classIds = HashMap<String, Int>()
    private val sources = ArrayList<String>()
    private val sourceIds = HashMap<String, Int>()

    /** packInts(lemma id, locality) → context; per context its key, the sources observed and the classes believed. */
    private val contextIds = HashMap<Long, Int>()
    private var contextKeys = LongArray(64)
    private val basis = ArrayList<RoaringSeries>()
    private val held = ArrayList<RoaringSeries>()
    private val byLemma = HashMap<Int, RoaringSeries>()

    /** Beliefs, open-addressed by packInts(context, class id): evidence ([EvidenceCoord.packed]) and attention. */
    private var cap = 64
    private var keys = LongArray(cap).also { it.fill(EMPTY) }
    private var evidence = LongArray(cap)
    private var attention = FloatArray(cap)
    var size = 0
        private set

    /** Contexts holding at least one belief. */
    val contexts: Int get() = held.count { !it.isEmpty() }

    private fun intern(ids: HashMap<String, Int>, names: ArrayList<String>, s: String): Int =
        ids.getOrPut(s) { names.size.also { names.add(s) } }

    private fun context(lemma: Int, at: Locality): Int = contextIds.getOrPut(packInts(lemma, at.packed)) {
        val ctx = basis.size
        if (ctx == contextKeys.size) contextKeys = contextKeys.copyOf(ctx * 2)
        contextKeys[ctx] = packInts(lemma, at.packed)
        basis.add(RoaringSeries.EMPTY); held.add(RoaringSeries.EMPTY)
        byLemma[lemma] = (byLemma[lemma] ?: RoaringSeries.EMPTY) or RoaringSeries.singleton(ctx)
        ctx
    }

    private fun localityOf(ctx: Int) = Locality(contextKeys[ctx].toInt())

    /**
     * One judgment of [lemma]'s use, read in [at]: class → probability. False when [source] was already observed in
     * this context; its evidence is in the memory once.
     */
    fun observe(lemma: String, at: Locality, source: String, judged: Map<String, Double>): Boolean {
        val ctx = context(intern(lemmaIds, lemmas, lemma), at)
        val src = intern(sourceIds, sources, source)
        if (basis[ctx].contains(src)) return false
        basis[ctx] = basis[ctx] or RoaringSeries.singleton(src)
        for ((name, p) in judged) {
            val cls = intern(classIds, classes, name)
            val positive = (p.coerceIn(0.0, 1.0) * Nal.UNIT).toLong()
            put(packInts(ctx, cls), EvidenceCoord(positive, Nal.UNIT - positive))
            held[ctx] = held[ctx] or RoaringSeries.singleton(cls)
        }
        return true
    }

    /** Attention decays by [DECAY]; evidence does not. */
    fun tick() {
        for (s in 0 until cap) if (keys[s] != EMPTY) attention[s] *= DECAY
    }

    /** The evidence that [lemma] denotes [cls], read in [at]: each context's evidence weighed by its nearness to [at]. */
    fun evidence(lemma: String, at: Locality, cls: String): EvidenceCoord {
        val l = lemmaIds[lemma] ?: return EvidenceCoord.EMPTY
        val c = classIds[cls] ?: return EvidenceCoord.EMPTY
        return weighed(l, c, at)
    }

    /** Every class believed of [lemma] anywhere, with its evidence weighed for [at], most expected first. */
    fun senses(lemma: String, at: Locality): List<Pair<String, EvidenceCoord>> {
        val l = lemmaIds[lemma] ?: return emptyList()
        var all = RoaringSeries.EMPTY
        byLemma[l]?.forEach { all = all or held[it] }
        val out = ArrayList<Pair<String, EvidenceCoord>>()
        all.forEach { c -> out.add(classes[c] to weighed(l, c, at)) }
        return out.sortedByDescending { Nal.truthOf(it.second).expectation() }
    }

    /** [lemma]'s contexts: the locality, how many sources were observed there, and each class's own evidence there. */
    fun contextsOf(lemma: String): List<Triple<Locality, Int, List<Pair<String, EvidenceCoord>>>> {
        val l = lemmaIds[lemma] ?: return emptyList()
        val out = ArrayList<Triple<Locality, Int, List<Pair<String, EvidenceCoord>>>>()
        byLemma[l]?.forEach { ctx ->
            if (held[ctx].isEmpty()) return@forEach
            val own = ArrayList<Pair<String, EvidenceCoord>>()
            held[ctx].forEach { c -> slot(packInts(ctx, c)).takeIf { it >= 0 }?.let { own.add(classes[c] to EvidenceCoord(evidence[it])) } }
            out.add(Triple(localityOf(ctx), basis[ctx].cardinality, own.sortedByDescending { Nal.truthOf(it.second).expectation() }))
        }
        return out
    }

    /** Lemmas with a context in [at]. */
    fun lemmasIn(at: Locality): List<String> =
        (0 until basis.size).filter { !held[it].isEmpty() && contextKeys[it].toInt() == at.packed }.map { lemmas[(contextKeys[it] ushr 32).toInt()] }

    /** Every (lemma, locality) holding a belief. */
    fun held(): List<Pair<String, Locality>> =
        (0 until basis.size).filter { !held[it].isEmpty() }.map { lemmas[(contextKeys[it] ushr 32).toInt()] to localityOf(it) }

    private fun weighed(l: Int, c: Int, at: Locality): EvidenceCoord {
        var pos = 0.0; var neg = 0.0
        byLemma[l]?.forEach { ctx ->
            val s = slot(packInts(ctx, c))
            if (s >= 0) {
                val w = at.nearness(localityOf(ctx)); val e = EvidenceCoord(evidence[s])
                pos += w * e.positive; neg += w * e.negative
            }
        }
        return EvidenceCoord(pos.toLong(), neg.toLong())
    }

    private fun put(key: Long, e: EvidenceCoord) {
        val s = slot(key)
        if (s >= 0) { evidence[s] = revise(EvidenceCoord(evidence[s]), e).packed; attention[s] = 1f; return }
        if (size >= capacity) forget()
        insert(key, e.packed, 1f)
    }

    /** The least attended and least confident [FORGET_SHARE]th of the beliefs, forgotten together. */
    private fun forget() {
        val n = maxOf(1, capacity / FORGET_SHARE)
        val score = FloatArray(size); val at = LongArray(size); var i = 0
        for (s in 0 until cap) if (keys[s] != EMPTY) {
            score[i] = attention[s] * Nal.truthOf(EvidenceCoord(evidence[s])).confidence; at[i] = keys[s]; i++
        }
        val cut = score.copyOf(i).also { it.sort() }[minOf(n, i) - 1]
        var gone = 0
        for (x in 0 until i) {
            if (gone >= n || score[x] > cut) continue
            val s = slot(at[x]).takeIf { it >= 0 } ?: continue
            remove(s); gone++
            val ctx = (at[x] ushr 32).toInt()
            held[ctx] = held[ctx].andNot(RoaringSeries.singleton(at[x].toInt()))
            if (held[ctx].isEmpty()) basis[ctx] = RoaringSeries.EMPTY
        }
    }

    private fun home(key: Long, mask: Int): Int = ((key * -0x61c8864680b583ebL) ushr 33).toInt() and mask

    private fun slot(key: Long): Int {
        val mask = cap - 1
        var s = home(key, mask)
        while (keys[s] != EMPTY) {
            if (keys[s] == key) return s
            s = (s + 1) and mask
        }
        return -1
    }

    private fun insert(key: Long, e: Long, a: Float) {
        if ((size + 1) * 2 > cap) grow()
        val mask = cap - 1
        var s = home(key, mask)
        while (keys[s] != EMPTY) s = (s + 1) and mask
        keys[s] = key; evidence[s] = e; attention[s] = a; size++
    }

    private fun grow() {
        val ok = keys; val oe = evidence; val oa = attention
        cap = cap shl 1
        keys = LongArray(cap).also { it.fill(EMPTY) }; evidence = LongArray(cap); attention = FloatArray(cap); size = 0
        for (i in ok.indices) if (ok[i] != EMPTY) insert(ok[i], oe[i], oa[i])
    }

    /** Removes slot [hole]; later members of its probe run shift back, so linear probing keeps no tombstones. */
    private fun remove(hole: Int) {
        val mask = cap - 1
        var s = hole
        keys[s] = EMPTY; size--
        var j = s
        while (true) {
            j = (j + 1) and mask
            val k = keys[j]
            if (k == EMPTY) return
            val h = home(k, mask)
            val stays = if (s <= j) h in (s + 1)..j else h > s || h <= j
            if (!stays) {
                keys[s] = k; evidence[s] = evidence[j]; attention[s] = attention[j]
                keys[j] = EMPTY
                s = j
            }
        }
    }

    /** The memory as text, what [read] restores: `s` source lines, then per context an `x` line and its `b` beliefs. */
    fun write(): String = buildString {
        val live = (0 until basis.size).filter { !held[it].isEmpty() }
        var referenced = RoaringSeries.EMPTY
        for (ctx in live) referenced = referenced or basis[ctx]
        val ids = referenced.toIntArray()
        val renumbered = HashMap<Int, Int>(ids.size * 2)
        ids.forEachIndexed { i, s -> renumbered[s] = i; append("s\t").append(sources[s]).append('\n') }
        for (ctx in live) {
            append("x\t").append(lemmas[(contextKeys[ctx] ushr 32).toInt()]).append('\t').append(contextKeys[ctx].toInt()).append('\t')
            append(basis[ctx].toIntArray().joinToString(",") { renumbered.getValue(it).toString() }).append('\n')
            held[ctx].forEach { c ->
                val s = slot(packInts(ctx, c))
                if (s >= 0) append("b\t").append(classes[c]).append('\t').append(evidence[s]).append('\t').append(attention[s]).append('\n')
            }
        }
    }

    companion object {
        /** Beliefs held at once: enough for a library straddling legal and medical texts. */
        const val CAPACITY = 1 shl 20
        const val DECAY = 0.9f
        const val FORGET_SHARE = 16
        private const val EMPTY = -1L

        /** A memory from [write]'s text. */
        fun read(lines: Sequence<String>, capacity: Int = CAPACITY): SenseMemory {
            val m = SenseMemory(capacity)
            var ctx = -1
            for (line in lines) {
                val f = line.split('\t')
                when (f[0]) {
                    "s" -> m.intern(m.sourceIds, m.sources, f[1])
                    "x" -> {
                        ctx = m.context(m.intern(m.lemmaIds, m.lemmas, f[1]), Locality(f[2].toInt()))
                        m.basis[ctx] = if (f[3].isEmpty()) RoaringSeries.EMPTY else RoaringSeries.of(f[3].split(',').map { it.toInt() }.toIntArray())
                    }
                    "b" -> if (ctx >= 0) {
                        val cls = m.intern(m.classIds, m.classes, f[1])
                        m.insert(packInts(ctx, cls), f[2].toLong(), f[3].toFloat())
                        m.held[ctx] = m.held[ctx] or RoaringSeries.singleton(cls)
                    }
                }
            }
            return m
        }
    }
}
