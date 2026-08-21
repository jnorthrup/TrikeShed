package borg.trikeshed.nlp.lemma

/**
 * TSV dictionary format shared by the extractor (bench/lemma) and the runtime.
 *
 * `observations.tsv` — one aggregated observation per line:
 *     word <TAB> prevLemma <TAB> nextLemma <TAB> lemma <TAB> count
 * where an absent neighbor (sentence edge) is the literal `-`. Lines starting with `#` are comments.
 *
 * Resource location: `nlp/lemma/en/observations.tsv` under commonMain resources. Reading the resource
 * is the caller's platform concern (classloader on JVM, fetch on JS); this object only parses text, so
 * it stays commonMain-pure.
 */
object LemmaDictionary {
    const val RESOURCE_PATH: String = "nlp/lemma/en/observations.tsv"
    const val EDGE: String = "-"

    fun parse(tsv: String): List<LemmaObservation> {
        val out = ArrayList<LemmaObservation>()
        for (raw in tsv.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isEmpty() || line.startsWith("#")) continue
            val cols = line.split('\t')
            if (cols.size < 4) continue
            out += LemmaObservation(
                word = cols[0],
                prevLemma = cols[1].takeUnless { it == EDGE },
                nextLemma = cols[2].takeUnless { it == EDGE },
                lemma = cols[3],
                weight = cols.getOrNull(4)?.toIntOrNull() ?: 1,
            )
        }
        return out
    }

    fun render(observations: Iterable<LemmaObservation>, header: String = ""): String = buildString {
        if (header.isNotEmpty()) header.lineSequence().forEach { append("# ").append(it).append('\n') }
        append("# word\tprevLemma\tnextLemma\tlemma\tcount\n")
        for (o in observations) {
            append(o.word).append('\t')
            append(o.prevLemma ?: EDGE).append('\t')
            append(o.nextLemma ?: EDGE).append('\t')
            append(o.lemma).append('\t')
            append(o.weight).append('\n')
        }
    }

    /** Aggregate raw (weight=1) observations into counted ones, preserving first-seen order. */
    fun aggregate(raw: Iterable<LemmaObservation>): List<LemmaObservation> {
        val counts = LinkedHashMap<LemmaObservation, Int>()
        for (o in raw) {
            val key = o.copy(weight = 1)
            counts[key] = (counts[key] ?: 0) + o.weight
        }
        return counts.map { (k, n) -> k.copy(weight = n) }
    }
}
