package borg.trikeshed.ontology

import borg.trikeshed.collections.associative.FunnelHashIndex
import borg.trikeshed.lib.*
import borg.trikeshed.kif.KifExpr
import java.security.MessageDigest

/**
 * The pinned SUMO corpus as a classifier. `fetchSumoCorpus` (build.gradle.kts)
 * lands `sumo/Merge.kif` and `sumo/Mid-level-ontology.kif` on the jvmMain
 * classpath, checksum-verified against gradle/sumo-corpus.pins; this is the
 * first reader of those bytes. Missing middle resources retain their empty-text
 * fallback. [full] requires the complete separate manifest and verifies every
 * payload again at runtime. See docs/ontology/sumo-corpus.md for scope and counts.
 */
object SumoCorpus {
    val FILES = listOf("sumo/Merge.kif", "sumo/Mid-level-ontology.kif")

    fun text(resource: String): String =
        SumoCorpus::class.java.classLoader.getResourceAsStream(resource)?.use { it.readBytes().decodeToString() } ?: ""

    fun text(): String = FILES.joinToString("\n") { text(it) }

    val pinned: SumoClassifier by lazy { SumoClassifier.parse(text()) }

    private val loader: ClassLoader get() = SumoCorpus::class.java.classLoader
    private val hasFull: Boolean get() = loader.getResource("sumo/full/corpus.pins") != null

    /** The frozen image when [SumoFrozen.RESOURCE] is on the classpath, else null. */
    private val frozen: SumoFrozen.Image? by lazy { loader.getResourceAsStream(SumoFrozen.RESOURCE)?.use(SumoFrozen::read) }

    /** The KIF forms: the full corpus when present, else the pinned middle. */
    fun forms(): Iterable<KifExpr> = if (hasFull) fullForms(loader) else KifExpr.parseAll(text())

    /** The taxonomy parsed from KIF: the full corpus when present, else the pinned middle. */
    fun parsedTaxonomy(): SumoTaxonomy = SumoClassifier.taxonomy(forms())

    /** Forms that name or describe terms rather than relate them. */
    private val lexicalHeads = setOf("documentation", "format", "termFormat", "externalImage", "comment",
        "subclass", "instance", "subrelation", "subAttribute", "disjoint", "partition", "exhaustiveDecomposition",
        "disjointDecomposition", "domain", "domainSubclass", "range", "rangeSubclass", "relatedExternalConcept",
        "abbreviation", "synonymousExternalConcept", "lexicon", "equivalenceMapping", "subsumingExternalConcept")

    /**
     * Classes related by the axioms: two classes are related when one rule or statement (not a naming,
     * typing or taxonomy form) mentions both, e.g. JudgeAtLaw and CourtRoom in one `=>` rule. An
     * instance mention counts as its classes. CSR over term ids of [taxonomy]: the relatives of term i
     * are `ids[starts[i] until starts[i+1]]`. Frozen with the taxonomy.
     */
    fun parsedRelated(taxonomy: SumoTaxonomy, forms: Iterable<KifExpr>): Pair<IntArray, IntArray> {
        val termOf = HashMap<String, Int>(taxonomy.names.size * 2)
        for (i in taxonomy.names.indices) termOf[taxonomy.names[i]] = i
        val classesOf = HashMap<Int, IntArray>()
        run {
            val acc = HashMap<Int, ArrayList<Int>>()
            for (e in 0 until taxonomy.instanceEdges.size step 2) acc.getOrPut(taxonomy.instanceEdges[e]) { ArrayList() }.add(taxonomy.instanceEdges[e + 1])
            for ((k, v) in acc) classesOf[k] = v.toIntArray()
        }
        val related = HashMap<Int, HashSet<Int>>()
        fun atoms(e: KifExpr, into: HashSet<Int>) {
            when (e) {
                is KifExpr.Atom -> termOf[e.token]?.let { t ->
                    if (taxonomy.isClass[t]) into.add(t) else classesOf[t]?.forEach { into.add(it) }
                }
                is KifExpr.ListExpr -> e.elements.forEach { atoms(it, into) }
                is KifExpr.Quoted -> atoms(e.expr, into)
                is KifExpr.Var -> {}
            }
        }
        for (f in forms) {
            if (f !is KifExpr.ListExpr) continue
            val head = (f.elements.firstOrNull() as? KifExpr.Atom)?.token ?: continue
            if (head in lexicalHeads) continue
            val mentioned = HashSet<Int>().also { atoms(f, it) }
            if (mentioned.size < 2 || mentioned.size > 24) continue
            for (a in mentioned) related.getOrPut(a) { HashSet() }.addAll(mentioned.filter { it != a })
        }
        val starts = IntArray(taxonomy.names.size + 1)
        val ids = IntArray(related.values.sumOf { it.size })
        var k = 0
        for (i in taxonomy.names.indices) { starts[i] = k; related[i]?.sorted()?.forEach { ids[k++] = it } }
        starts[taxonomy.names.size] = k
        return starts to ids
    }

    /**
     * WordNet 3.0 noun lemmas joined to SUMO term ids in [taxonomy], from the pinned
     * `WordNetMappings30-noun.txt`, as CSR: sorted distinct lemmas, `starts` (lemmas + 1) and the
     * distinct senses of lemma i at `terms[starts[i] until starts[i+1]]`. The first sense is the
     * preferred one: an equivalence mapping (`=`) outranks a subsumption (`+`), ties keep the first
     * synset. Lemmas are lowercase, `_` for spaces.
     */
    fun parsedLexicon(taxonomy: SumoTaxonomy): Triple<Array<String>, IntArray, IntArray> {
        val termOf = HashMap<String, Int>(taxonomy.names.size * 2)
        for (i in taxonomy.names.indices) if (taxonomy.isClass[i]) termOf[taxonomy.names[i]] = i
        // A mapping to an instance (JudgeAtLaw, LegislativeBill, Page are attribute instances, not
        // classes) reads as the class it is an instance of, so the sense is kept, not dropped.
        for (e in 0 until taxonomy.instanceEdges.size step 2) {
            val name = taxonomy.names[taxonomy.instanceEdges[e]]
            if (name !in termOf) termOf[name] = taxonomy.instanceEdges[e + 1]
        }
        // lemma → (rank shl 20 or synset order) shl 32 or term, sorted later; one entry per distinct term.
        val senses = HashMap<String, LinkedHashMap<Int, Long>>()
        var order = 0L
        text("sumo/WordNetMappings/WordNetMappings30-noun.txt").lineSequence().forEach { line ->
            if (line.isEmpty() || !line[0].isDigit()) return@forEach
            val at = line.indexOf("&%").takeIf { it >= 0 } ?: return@forEach
            var end = at + 2
            while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_' || line[end] == '-')) end++
            val term = termOf[line.substring(at + 2, end)] ?: return@forEach
            val r = if (line.getOrNull(end) == '=') 0L else 1L
            val f = line.split(' ')
            val weight = (r shl 40) or order++
            for (w in 0 until f[3].toInt(16)) {
                val m = senses.getOrPut(f[4 + 2 * w].lowercase()) { LinkedHashMap() }
                if (weight < (m[term] ?: Long.MAX_VALUE)) m[term] = weight
            }
        }
        val lemmas = senses.keys.sorted().toTypedArray()
        val starts = IntArray(lemmas.size + 1)
        val terms = IntArray(senses.values.sumOf { it.size })
        var k = 0
        for ((i, l) in lemmas.withIndex()) {
            starts[i] = k
            for ((t, _) in senses.getValue(l).entries.sortedBy { it.value }) terms[k++] = t
        }
        starts[lemmas.size] = k
        return Triple(lemmas, starts, terms)
    }

    /** Taxonomy and noun lexicon: from the frozen image when present, else parsed from KIF. */
    private val image: SumoFrozen.Image by lazy {
        frozen ?: forms().toList().let { f -> SumoClassifier.taxonomy(f).let { t ->
            val (l, s, i) = parsedLexicon(t); val (rs, ri) = parsedRelated(t, f)
            SumoFrozen.Image(t, l, s, i, rs, ri)
        } }
    }

    /** Built from [image]: the full corpus when frozen or present, else the pinned middle. */
    val classifier: SumoClassifier by lazy { SumoClassifier.of(image.taxonomy) }

    /** Noun lemma → its senses as preorder class ids in [classifier] (CSR over [SumoFrozen.Image.lexiconStarts]). */
    private val nounIndex: Join<FunnelHashIndex<String>, IntArray> by lazy {
        val names = image.taxonomy.names
        FunnelHashIndex.build(image.lexiconLemmas.toSeries(), 0x574E_4F55L) j
            IntArray(image.lexiconTerms.size) { i -> classifier.classId(names[image.lexiconTerms[i]])!!.value }
    }

    /** Preferred sense of [lemma] as a preorder class id, or -1. */
    fun nounClassId(lemma: String): Int = nounIndex.a.get(lemma)?.let { nounIndex.b[image.lexiconStarts[it]] } ?: -1

    /** Every sense of [lemma], preferred first; empty when the lemma is not a mapped noun. */
    fun nounSenses(lemma: String): IntArray = nounIndex.a.get(lemma)?.let { i ->
        nounIndex.b.copyOfRange(image.lexiconStarts[i], image.lexiconStarts[i + 1])
    } ?: IntArray(0)

    /**
     * The sense of [lemma] its [neighbors] (the other nouns read with it) and the [believed] classes
     * (self+ancestor ids of classes already believed) support most. Each neighbor votes the
     * information content of the most specific ancestor some sense of it shares with the candidate
     * (Resnik); the believed set votes the most specific believed class above the candidate. Broad
     * ancestors (Entity, Physical, …) carry ~0 information, so they no longer outvote the specific
     * one. Ties and silence keep the preferred sense; -1 when the lemma is not a mapped noun.
     */
    fun nounSense(lemma: String, neighbors: Collection<String>, believed: borg.trikeshed.collections.bits.RoaringSeries): Int {
        val senses = nounSenses(lemma)
        if (senses.size < 2) return senses.firstOrNull() ?: -1
        val others = neighbors.filter { it != lemma }.map { nounSenses(it) }.filter { it.isNotEmpty() }
        var best = senses[0]; var bestScore = 0f
        for (s in senses) {
            val cs = closure(s)
            // A neighbor votes the stronger of: the most specific ancestor it shares with s (is-a),
            // or the most informative axiom joining the two lineages, scored by its less specific end.
            // Both are monotone: a sense never scores below its own ancestor sense.
            var score = informationOf(cs and believed)
            for (group in others) score += group.maxOf { t ->
                val ct = closure(t)
                maxOf(informationOf(cs and ct), axiomInformation(cs, ct))
            }
            if (score > bestScore) { best = s; bestScore = score }
        }
        return best
    }

    /** Class id → class ids an axiom relates to it (CSR frozen with the taxonomy, mapped to preorder ids). */
    private val related: Join<IntArray, IntArray> by lazy {
        val n = classifier.classCount
        val names = image.taxonomy.names
        val rows = arrayOfNulls<borg.trikeshed.collections.bits.IntAccumulator>(n)
        for (t in names.indices) {
            val from = image.relatedStarts[t]; val to = image.relatedStarts[t + 1]
            if (from == to) continue
            val c = classifier.classId(names[t])?.value ?: continue
            val row = rows[c] ?: borg.trikeshed.collections.bits.IntAccumulator(maxOf(1, to - from)).also { rows[c] = it }
            for (k in from until to) classifier.classId(names[image.relatedIds[k]])?.value?.let(row::add)
        }
        val sets = Array(n) { rows[it]?.toRoaring()?.toIntArray() ?: IntArray(0) }
        val starts = IntArray(n + 1)
        for (c in 0 until n) starts[c + 1] = starts[c] + sets[c].size
        val ids = IntArray(starts[n])
        for (c in 0 until n) sets[c].copyInto(ids, starts[c])
        starts j ids
    }

    /**
     * The most informative axiom joining lineages [a] and [b] (self+ancestor ids): over classes x in
     * [a] an axiom relates to some y in [b], the greatest min(information x, information y).
     */
    private fun axiomInformation(a: borg.trikeshed.collections.bits.RoaringSeries, b: borg.trikeshed.collections.bits.RoaringSeries): Float {
        val (starts, ids) = related.a to related.b
        var best = 0f
        a.forEach { x ->
            val ix = information[x]
            if (ix <= best || x !in 0 until starts.size - 1) return@forEach
            for (k in starts[x] until starts[x + 1]) {
                val y = ids[k]
                if (b.contains(y)) { val v = minOf(ix, information[y]); if (v > best) best = v }
            }
        }
        return best
    }

    /** Information content of class id c: ln(classes / classes under c), 0 at the root. */
    private val information: FloatArray by lazy {
        val n = classifier.classCount
        val under = IntArray(n)
        for (c in 0 until n) closure(c).forEach { if (it in 0 until n) under[it]++ }
        FloatArray(n) { kotlin.math.ln(n.toFloat() / under[it].coerceAtLeast(1)) }
    }

    /** The information content of the most specific class in [ids]. */
    private fun informationOf(ids: borg.trikeshed.collections.bits.RoaringSeries): Float {
        val ic = information
        var m = 0f
        ids.forEach { if (it in ic.indices && ic[it] > m) m = ic[it] }
        return m
    }

    /** Self+ancestor ids of class [id]. */
    fun closure(id: Int): borg.trikeshed.collections.bits.RoaringSeries =
        classifier.ancestors(SumoClassId(id)) or borg.trikeshed.collections.bits.RoaringSeries.of(listOf(id))

    /** The original Merge + Mid-level classifier keeps its identity and term IDs. */
    val middle: SumoClassifier get() = pinned

    val fullFiles: Series<String> by lazy { fullManifest(SumoCorpus::class.java.classLoader) α { it.b } }

    /** Deferred/experimental: independent IDs; requires the optional full resource artifact. */
    val full: SumoClassifier by lazy { full(SumoCorpus::class.java.classLoader) }

    /** Explicit resource boundary, also usable by isolated classloaders. Files parse independently. */
    fun full(classLoader: ClassLoader): SumoClassifier = SumoClassifier.of(fullForms(classLoader))

    /** Every top-level form of the full corpus, checksum-verified per file. */
    fun fullForms(classLoader: ClassLoader): Iterable<KifExpr> =
        fullManifest(classLoader).view.asSequence().flatMap { (sha256, resource) ->
            val bytes = checkNotNull(classLoader.getResourceAsStream(resource)) {
                "Full SUMO corpus is incomplete: $resource"
            }.use { it.readBytes() }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(actual == sha256) { "Full SUMO checksum mismatch: $resource (expected $sha256, actual $actual)" }
            val forms = try {
                KifExpr.parseAll(bytes.decodeToString(throwOnInvalidSequence = true), strict = true)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("Invalid full SUMO KIF in $resource: ${e.message}", e)
            }
            check(forms.isNotEmpty() && forms.all {
                it is KifExpr.ListExpr && it.elements.firstOrNull() is KifExpr.Atom
            }) { "Invalid full SUMO top-level form in $resource" }
            forms.asSequence()
        }.asIterable()

    /** SHA-256 joined to the complete classpath resource path, in manifest order. */
    fun fullManifest(classLoader: ClassLoader): Series2<String, String> {
        val manifest = checkNotNull(classLoader.getResourceAsStream("sumo/full/corpus.pins")) {
            "Full SUMO corpus unavailable: add the optional sumoFullCorpusJar to this classpath"
        }.use { it.readBytes().decodeToString(throwOnInvalidSequence = true) }
        check(Regex("(?m)^#\\s*ref\\s*=\\s*[0-9a-f]{40}\\s*$").findAll(manifest).count() == 1) {
            "Full SUMO manifest requires one pinned revision"
        }
        val count = Regex("(?m)^#\\s*files\\s*=\\s*(\\d+)\\s*$").findAll(manifest).singleOrNull()?.groupValues?.get(1)?.toIntOrNull()
        val pins = SeriesBuffer<Twin<String>>()
        val paths = HashSet<String>()
        for (line in manifest.lineSequence()) {
            val row = line.substringBefore('#').trim()
            if (row.isEmpty()) continue
            val parts = row.split(Regex("\\s+"), limit = 2)
            check(parts.size == 2 && parts[0].matches(Regex("[0-9a-f]{64}"))) { "Invalid full SUMO pin: $row" }
            val path = parts[1]
            check(path.matches(Regex("[A-Za-z0-9_./-]+\\.kif")) && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
                "Invalid full SUMO resource path: $path"
            }
            check(paths.add(path)) { "Duplicate full SUMO resource: $path" }
            pins.add(parts[0] j "sumo/full/$path")
        }
        check(count != null && count > 0 && count == pins.size) { "Full SUMO manifest expected $count files, found ${pins.size}" }
        return pins.drain()
    }
}
