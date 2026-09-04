package borg.trikeshed.ontology

/**
 * The pinned SUMO corpus as a classifier. `fetchSumoCorpus` (build.gradle.kts)
 * lands `sumo/Merge.kif` and `sumo/Mid-level-ontology.kif` on the jvmMain
 * classpath, checksum-verified against gradle/sumo-corpus.pins; this is the
 * first reader of those bytes. Absent files (offline cold cache) yield an
 * empty text, so [pinned] degrades to the empty classifier rather than throwing.
 */
object SumoCorpus {
    val FILES = listOf("sumo/Merge.kif", "sumo/Mid-level-ontology.kif")

    fun text(resource: String): String =
        SumoCorpus::class.java.classLoader.getResourceAsStream(resource)?.use { it.readBytes().decodeToString() } ?: ""

    fun text(): String = FILES.joinToString("\n") { text(it) }

    val pinned: SumoClassifier by lazy { SumoClassifier.parse(text()) }
}
