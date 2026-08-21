package borg.trikeshed.bench.lemma

import borg.trikeshed.nlp.lemma.FunnelLemmatizer
import borg.trikeshed.nlp.lemma.LemmaDictionary
import borg.trikeshed.nlp.lemma.ResidualLemmaCache
import borg.trikeshed.nlp.lemma.lemmatizeDocument
import edu.stanford.nlp.pipeline.CoreDocument
import java.io.File
import kotlin.random.Random

/**
 * args[0] = resources dir holding observations.tsv; args[1] = RESULTS.md path; args[2..] = held-out corpus roots.
 *
 * Three scales ("fractals"): sentence (each sentence alone), paragraph (sentences with paragraph CIDs),
 * document (paragraph CIDs too). At each scale we time (a) CoreNLP live — tokenize,ssplit,pos,lemma on the
 * raw text, and (b) FunnelLemmatizer over CoreNLP's own tokens, with the residual cache; agreement is
 * token-level lemma equality (lowercased). A synthetic self-similar corpus with a controlled repetition
 * ratio is run as well so the residual effect is measurable independent of the docs' natural repetition.
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: LemmaBenchmark <resourcesDir> <resultsMd> [heldOutRoot...]" }
    val resources = File(args[0])
    val results = File(args[1])
    val roots = args.drop(2)

    val tsv = File(resources, "observations.tsv")
    require(tsv.isFile) { "missing $tsv — run extractDictionary first" }
    val tFreeze0 = System.nanoTime()
    val observations = LemmaDictionary.parse(tsv.readText())
    val lemmatizer = FunnelLemmatizer.freeze(observations, seed = 0L)
    val freezeMs = (System.nanoTime() - tFreeze0) / 1_000_000

    val nlp = Corpus.pipeline()
    val files = Corpus.files(roots).let { all ->
        // held-out = every 5th file by stable hash of path, so train/test don't overlap when both default to the docs
        all.filter { (it.path.hashCode() and 0x7fffffff) % 5 == 0 }.ifEmpty { all.take(20) }
    }
    println("bench: ${files.size} held-out files; freeze ${freezeMs} ms; vocab ${lemmatizer.vocabulary}; contexts ${lemmatizer.linkedContexts}")

    val out = StringBuilder()
    out.append("# Lemma benchmark — CoreNLP live vs FunnelLemmatizer (frozen from observations.tsv)\n\n")
    out.append("freeze: ${freezeMs} ms, observations=${observations.size}, vocabulary=${lemmatizer.vocabulary}, linkedContexts=${lemmatizer.linkedContexts}\n\n")
    out.append("| corpus | scale | tokens | corenlp ms | funnel ms | speedup | agreement | tokens lemmatized | skipped sents | skipped paras |\n")
    out.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|\n")

    // Warm-up both sides once so JIT isn't charged to the first row.
    files.firstOrNull()?.let { Corpus.annotate(nlp, it, 5_000) }

    val docs = files.map { Corpus.annotate(nlp, it, 60_000) }
    runScales("docs", docs, nlp, lemmatizer, out)

    for ((name, rep) in listOf("synthetic-r0" to 0.0, "synthetic-r50" to 0.5, "synthetic-r90" to 0.9)) {
        val synth = Synthetic.corpus(nlp, observations.map { it.word }.distinct(), repetition = rep, paragraphs = 200, seed = 7)
        runScales(name, listOf(synth), nlp, lemmatizer, out)
    }

    results.writeText(out.toString())
    println(out)
    println("wrote $results")
}

private fun runScales(
    corpus: String,
    docs: List<RefDocument>,
    nlp: edu.stanford.nlp.pipeline.StanfordCoreNLP,
    lemmatizer: FunnelLemmatizer,
    out: StringBuilder,
) {
    for (scale in listOf("sentence", "paragraph", "document")) {
        var tokens = 0
        var agree = 0
        var done = 0
        var skS = 0
        var skP = 0
        var coreMs = 0L
        var funnelMs = 0L
        for (doc in docs) {
            // CoreNLP live: re-annotate the raw text of each unit at this scale (that is what a user of CoreNLP pays).
            val units: List<String> = when (scale) {
                "sentence" -> doc.paragraphs.flatten().map { it.joinToString(" ") }
                "paragraph" -> doc.paragraphs.map { p -> p.joinToString("\n") { it.joinToString(" ") } }
                else -> listOf(doc.paragraphs.joinToString("\n\n") { p -> p.joinToString("\n") { it.joinToString(" ") } })
            }
            val c0 = System.nanoTime()
            for (u in units) nlp.annotate(CoreDocument(u))
            coreMs += (System.nanoTime() - c0) / 1_000_000

            // Funnel: same tokens, residual cache scoped per scale.
            val cache = ResidualLemmaCache()
            val f0 = System.nanoTime()
            val (lemmas, stats) = when (scale) {
                "sentence" -> {
                    // each sentence as its own paragraph: no paragraph-level skipping possible
                    lemmatizer.lemmatizeDocument(doc.paragraphs.flatten().map { listOf(it) }, cache)
                        .let { (l, s) -> l.map { it.single() }.let { flat -> regroup(flat, doc.paragraphs) to s } }
                }
                else -> lemmatizer.lemmatizeDocument(doc.paragraphs, cache)
            }
            funnelMs += (System.nanoTime() - f0) / 1_000_000

            for (p in doc.lemmas.indices) for (s in doc.lemmas[p].indices) for (i in doc.lemmas[p][s].indices) {
                tokens++
                if (doc.lemmas[p][s][i].lowercase() == lemmas[p][s][i]) agree++
            }
            done += stats.tokensLemmatized
            skS += stats.sentencesSkipped
            skP += stats.paragraphsSkipped
        }
        val speedup = if (funnelMs == 0L) "∞" else "%.1fx".format(coreMs.toDouble() / funnelMs)
        val agreement = if (tokens == 0) 0.0 else agree * 100.0 / tokens
        out.append("| $corpus | $scale | $tokens | $coreMs | $funnelMs | $speedup | %.2f%% | $done | $skS | $skP |\n".format(agreement))
    }
}

private fun regroup(flat: List<List<String>>, shape: List<List<List<String>>>): List<List<List<String>>> {
    var k = 0
    return shape.map { p -> p.map { flat[k++] } }
}

/** Self-similar corpus: sentences drawn Zipf-style from the vocabulary; paragraphs reuse earlier sentences with probability [repetition]; later paragraphs reuse whole earlier paragraphs with the same probability. */
object Synthetic {
    fun corpus(nlp: edu.stanford.nlp.pipeline.StanfordCoreNLP, vocab: List<String>, repetition: Double, paragraphs: Int, seed: Int): RefDocument {
        val rnd = Random(seed)
        val words = vocab.filter { it.all { c -> c.isLetter() } }.take(20_000)
        fun zipfWord(): String = words[(words.size * (1 - Math.sqrt(rnd.nextDouble()))).toInt().coerceIn(0, words.lastIndex)]
        fun sentence(): String = (1..rnd.nextInt(6, 18)).joinToString(" ") { zipfWord() } + " ."
        val sentPool = ArrayList<String>()
        val paraPool = ArrayList<String>()
        val blocks = ArrayList<String>()
        repeat(paragraphs) {
            if (paraPool.isNotEmpty() && rnd.nextDouble() < repetition) { blocks += paraPool[rnd.nextInt(paraPool.size)]; return@repeat }
            val n = rnd.nextInt(3, 8)
            val para = (1..n).joinToString("\n") {
                if (sentPool.isNotEmpty() && rnd.nextDouble() < repetition) sentPool[rnd.nextInt(sentPool.size)]
                else sentence().also { s -> sentPool += s }
            }
            paraPool += para
            blocks += para
        }
        val text = blocks.joinToString("\n\n")
        val tmp = File.createTempFile("synthetic-", ".txt").apply { writeText(text); deleteOnExit() }
        return Corpus.annotate(nlp, tmp, Int.MAX_VALUE)
    }
}
