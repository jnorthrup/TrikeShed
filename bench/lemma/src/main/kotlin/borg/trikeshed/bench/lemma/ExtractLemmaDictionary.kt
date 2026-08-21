package borg.trikeshed.bench.lemma

import borg.trikeshed.nlp.lemma.FunnelLemmatizer
import borg.trikeshed.nlp.lemma.LemmaDictionary
import borg.trikeshed.nlp.lemma.LemmaObservation
import java.io.File
import java.time.Instant

/**
 * args[0] = output resources dir; args[1..] = corpus roots (files/dirs), default = repo docs.
 * Writes observations.tsv and MANIFEST.txt. Deterministic for a fixed corpus + CoreNLP version.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: ExtractLemmaDictionary <outDir> [corpusRoot...]" }
    val outDir = File(args[0]).apply { mkdirs() }
    val roots = args.drop(1)
    val maxTokensPerFile = System.getProperty("lemma.maxTokensPerFile", "60000").toInt()
    val maxDictWords = System.getProperty("lemma.maxDictWords", "120000").toInt()

    val nlp = Corpus.pipeline()
    val files = Corpus.files(roots)
    println("extract: ${files.size} files; CoreNLP lemma over each (paragraph-blocked)")

    val raw = ArrayList<LemmaObservation>()
    val manifest = StringBuilder()
    var tokens = 0L
    val t0 = System.nanoTime()
    for (f in files) {
        val doc = Corpus.annotate(nlp, f, maxTokensPerFile)
        raw += doc.observations()
        tokens += doc.tokenCount
        manifest.append(f.path).append('\t').append(doc.tokenCount).append('\n')
    }
    Corpus.dictWords(nlp, maxDictWords)?.let { d ->
        raw += d.observations()
        tokens += d.tokenCount
        manifest.append(d.source).append('\t').append(d.tokenCount).append('\n')
    }
    val elapsedMs = (System.nanoTime() - t0) / 1_000_000

    val aggregated = LemmaDictionary.aggregate(raw)
    val header = """
        English lemma observations — DERIVED DATA, see ATTRIBUTION.md
        reference: Stanford CoreNLP ${edu.stanford.nlp.pipeline.StanfordCoreNLP::class.java.`package`.implementationVersion ?: "4.5.x"} lemma annotator (Morpha)
        generated: ${Instant.now()}  tokens=$tokens  rawObservations=${raw.size}  aggregated=${aggregated.size}
        format: word, prevLemma, nextLemma, lemma, count ; '-' = sentence edge ; lemmas/words as CoreNLP emitted them
    """.trimIndent()
    File(outDir, "observations.tsv").writeText(LemmaDictionary.render(aggregated, header))
    File(outDir, "MANIFEST.txt").writeText(
        "# source\ttokens\n$manifest# total\t$tokens\n# extraction-ms\t$elapsedMs\n# aggregated-observations\t${aggregated.size}\n"
    )

    val frozen = FunnelLemmatizer.freeze(aggregated, seed = 0L)
    println("wrote ${outDir}/observations.tsv (${aggregated.size} rows, $tokens tokens, ${elapsedMs} ms)")
    println("frozen check: vocabulary=${frozen.vocabulary} linkedContexts=${frozen.linkedContexts}")
}

fun RefDocument.observations(): List<LemmaObservation> {
    val out = ArrayList<LemmaObservation>(tokenCount)
    for (p in paragraphs.indices) for (s in paragraphs[p].indices) {
        val words = paragraphs[p][s]
        val lems = lemmas[p][s]
        for (i in words.indices) {
            out += LemmaObservation(
                word = words[i],
                prevLemma = if (i > 0) lems[i - 1] else null,
                nextLemma = if (i < words.lastIndex) lems[i + 1] else null,
                lemma = lems[i],
            )
        }
    }
    return out
}
