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

    /** The taxonomy parsed from KIF: the full corpus when present, else the pinned middle. */
    fun parsedTaxonomy(): SumoTaxonomy =
        if (hasFull) SumoClassifier.taxonomy(fullForms(loader)) else SumoClassifier.taxonomy(KifExpr.parseAll(text()))

    /**
     * WordNet 3.0 noun lemmas joined to SUMO term ids in [taxonomy], sorted by lemma, from the
     * pinned `WordNetMappings30-noun.txt`. An equivalence mapping (`=`) outranks a subsumption
     * mapping (`+`); ties keep the first synset. Lemmas are lowercase, `_` for spaces.
     */
    fun parsedLexicon(taxonomy: SumoTaxonomy): Pair<Array<String>, IntArray> {
        val termOf = HashMap<String, Int>(taxonomy.names.size * 2)
        for (i in taxonomy.names.indices) if (taxonomy.isClass[i]) termOf[taxonomy.names[i]] = i
        val rank = HashMap<String, Int>()
        val id = HashMap<String, Int>()
        text("sumo/WordNetMappings/WordNetMappings30-noun.txt").lineSequence().forEach { line ->
            if (line.isEmpty() || !line[0].isDigit()) return@forEach
            val at = line.indexOf("&%").takeIf { it >= 0 } ?: return@forEach
            var end = at + 2
            while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_' || line[end] == '-')) end++
            val term = termOf[line.substring(at + 2, end)] ?: return@forEach
            val r = if (line.getOrNull(end) == '=') 0 else 1
            val f = line.split(' ')
            for (w in 0 until f[3].toInt(16)) {
                val lemma = f[4 + 2 * w].lowercase()
                if (r < (rank[lemma] ?: 2)) { rank[lemma] = r; id[lemma] = term }
            }
        }
        val lemmas = id.keys.sorted().toTypedArray()
        return lemmas to IntArray(lemmas.size) { id.getValue(lemmas[it]) }
    }

    /** Taxonomy and noun lexicon: from the frozen image when present, else parsed from KIF. */
    private val image: SumoFrozen.Image by lazy {
        frozen ?: parsedTaxonomy().let { t -> parsedLexicon(t).let { (l, i) -> SumoFrozen.Image(t, l, i) } }
    }

    /** Built from [image]: the full corpus when frozen or present, else the pinned middle. */
    val classifier: SumoClassifier by lazy { SumoClassifier.of(image.taxonomy) }

    /** Noun lemma → preorder class id in [classifier], frozen in a funnel index over the lemmas. */
    private val nounIndex: Join<FunnelHashIndex<String>, IntArray> by lazy {
        val names = image.taxonomy.names
        val lemmas = image.lexiconLemmas
        FunnelHashIndex.build(lemmas.toSeries(), 0x574E_4F55L) j
            IntArray(lemmas.size) { i -> classifier.classId(names[image.lexiconTerms[i]])!!.value }
    }

    /** Preorder class id of [lemma], or -1. */
    fun nounClassId(lemma: String): Int = nounIndex.a.get(lemma)?.let { nounIndex.b[it] } ?: -1

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
