package borg.trikeshed.bench.lemma

import edu.stanford.nlp.pipeline.CoreDocument
import edu.stanford.nlp.pipeline.StanfordCoreNLP
import java.io.File
import java.util.Properties

/** A document as paragraphs → sentences → tokens, with the reference lemmas alongside. */
class RefDocument(
    val source: String,
    /** paragraphs[p][s] = tokens of sentence s in paragraph p (CoreNLP PTB tokens). */
    val paragraphs: List<List<List<String>>>,
    /** same shape, CoreNLP lemmas. */
    val lemmas: List<List<List<String>>>,
) {
    val tokenCount: Int get() = paragraphs.sumOf { p -> p.sumOf { it.size } }
    val sentenceCount: Int get() = paragraphs.sumOf { it.size }
}

object Corpus {
    private val defaultRoots = listOf("../../doc", "../../docs", "../../README.md", "../../src/README.md")

    fun pipeline(): StanfordCoreNLP = StanfordCoreNLP(Properties().apply {
        setProperty("annotators", "tokenize,ssplit,pos,lemma")
        setProperty("tokenize.language", "en")
        setProperty("ssplit.newlineIsSentenceBreak", "two")
    })

    /** Markdown/text files under the given roots (or the defaults), largest-first, capped by [maxFiles]. */
    fun files(roots: List<String>, maxFiles: Int = 400): List<File> {
        val rs = (roots.ifEmpty { defaultRoots }).map { File(it) }
        return rs.flatMap { root ->
            when {
                root.isFile -> listOf(root)
                root.isDirectory -> root.walkTopDown()
                    .filter { it.isFile && (it.extension == "md" || it.extension == "txt") }
                    .toList()
                else -> emptyList()
            }
        }.distinct().sortedByDescending { it.length() }.take(maxFiles)
    }

    /** Paragraphs = blank-line-separated blocks; CoreNLP splits sentences and tokenizes inside each block. */
    fun annotate(nlp: StanfordCoreNLP, file: File, maxTokens: Int): RefDocument {
        val text = file.readText()
        val blocks = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val paragraphs = ArrayList<List<List<String>>>()
        val lemmas = ArrayList<List<List<String>>>()
        var tokens = 0
        for (block in blocks) {
            if (tokens >= maxTokens) break
            val doc = CoreDocument(block)
            nlp.annotate(doc)
            val sents = doc.sentences()
            if (sents.isEmpty()) continue
            paragraphs += sents.map { s -> s.tokens().map { it.word() } }
            lemmas += sents.map { s -> s.tokens().map { it.lemma() } }
            tokens += sents.sumOf { it.tokens().size }
        }
        return RefDocument(file.path, paragraphs, lemmas)
    }

    /** `/usr/share/dict/words` (macOS web2, public domain) as one-word sentences: base-form coverage. */
    fun dictWords(nlp: StanfordCoreNLP, maxWords: Int): RefDocument? {
        val f = File("/usr/share/dict/words")
        if (!f.isFile) return null
        val words = f.readLines().filter { it.isNotBlank() && it.all { c -> c.isLetter() } }.take(maxWords)
        val doc = CoreDocument(words.joinToString("\n"))
        nlp.annotate(doc)
        val toks = doc.sentences().map { s -> s.tokens().map { it.word() } }
        val lems = doc.sentences().map { s -> s.tokens().map { it.lemma() } }
        return RefDocument(f.path, listOf(toks), listOf(lems))
    }
}
