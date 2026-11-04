package borg.trikeshed.nlp

import borg.trikeshed.cas.TermPostings
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentRevision

/** These are source-verified parser observations supplied by the host, not legal assertions. */
data class EnglishObservationToken(val index: Int, val begin: Int, val end: Int, val word: String,
    val lemma: String, val pos: String, val copy: Int = 0)
data class EnglishObservationSentence(val index: Int, val begin: Int, val end: Int, val quote: String,
    val tokens: Series<EnglishObservationToken>)
data class EnglishObservationReceipt(val receiptCid: ContentId, val originalCid: ContentId,
    val extractedTextCid: ContentId, val begin: Int, val end: Int, val totalLength: Int,
    val selection: DocumentRevision?, val sentences: Series<EnglishObservationSentence>)

/** Default includes every lexical lemma. Filtering never discards the original occurrence or sentence. */
data class EnglishCorpusPolicy(val caseFold: Boolean = false, val stopWords: Set<String> = emptySet(),
    val posPrefixes: Set<String> = emptySet(), val protectLegalMarkers: Boolean = true) {
    fun term(lemma: String): String = if (caseFold) lemma.lowercase() else lemma
    fun toMap(): Map<String, Any?> = _m["revision" j "reported-lemma/1", "caseFold" j caseFold,
        "stopWords" j stopWords.sorted(), "posPrefixes" j posPrefixes.sorted(),
        "protectLegalMarkers" j protectLegalMarkers, "lexical" j "lemma contains a letter or digit"]
}

data class EnglishCorpusRange(val receiptCid: ContentId, val begin: Int, val end: Int,
    val selection: DocumentRevision?) {
    fun toMap(): Map<String, Any?> = _m["receiptCid" j receiptCid.value, "begin" j begin,
        "end" j end, "selection" j selection?.toMap()]
}
data class EnglishCorpusDocument(val index: Int, val originalCid: ContentId, val extractedTextCid: ContentId,
    val totalLength: Int, val ranges: Series<EnglishCorpusRange>, val rawTokens: Int, val includedTokens: Int,
    val counts: Series<Int>) {
    val coveredUnits: Int get() = ranges.view.sumOf { it.end - it.begin }
    val complete: Boolean get() = ranges.size > 0 && ranges[0].begin == 0 && ranges[ranges.size - 1].end == totalLength &&
        (1 until ranges.size).all { ranges[it - 1].end == ranges[it].begin }
    fun toMap(): Map<String, Any?> = _m["index" j index, "originalCid" j originalCid.value,
        "extractedTextCid" j extractedTextCid.value, "totalLength" j totalLength,
        "ranges" j (ranges α { it.toMap() }).toList(), "coveredUnits" j coveredUnits,
        "complete" j complete, "rawTokens" j rawTokens, "includedTokens" j includedTokens,
        "counts" j counts.toList()]
}
data class EnglishCorpusTerm(val index: Int, val lemma: String, val frequency: Int, val documentFrequency: Int) {
    fun toMap(): Map<String, Any?> = _m["index" j index, "lemma" j lemma,
        "frequency" j frequency, "documentFrequency" j documentFrequency]
}
data class EnglishCorpusSentence(val id: ContentId, val document: Int, val receiptCid: ContentId,
    val selection: DocumentRevision?, val index: Int, val begin: Int, val end: Int, val quote: String) {
    fun toMap(): Map<String, Any?> = _m["id" j id.value, "document" j document,
        "receiptCid" j receiptCid.value, "selection" j selection?.toMap(), "index" j index,
        "begin" j begin, "end" j end, "quote" j quote]
}
data class EnglishCorpusOccurrence(val document: Int, val receiptCid: ContentId, val sentenceId: ContentId,
    val index: Int, val begin: Int, val end: Int, val word: String, val lemma: String, val term: String,
    val pos: String, val included: Boolean) {
    fun toMap(): Map<String, Any?> = _m["document" j document, "receiptCid" j receiptCid.value,
        "sentenceId" j sentenceId.value, "index" j index, "begin" j begin, "end" j end,
        "word" j word, "lemma" j lemma, "term" j term, "pos" j pos, "included" j included]
}
data class EnglishCorpusHit(val sentence: EnglishCorpusSentence, val originalCid: ContentId,
    val extractedTextCid: ContentId, val score: Double, val matches: Series<EnglishCorpusOccurrence>) {
    fun toMap(): Map<String, Any?> = _m["sentence" j sentence.toMap(), "originalCid" j originalCid.value,
        "extractedTextCid" j extractedTextCid.value, "score" j score,
        "matches" j (matches α { it.toMap() }).toList(), "authority" j "lexical-match-only"]
}

/** Immutable selected-source statistics. Counts never become evidence confidence or reasoning admission. */
class EnglishCorpusSnapshot internal constructor(val policy: EnglishCorpusPolicy,
    val documents: Series<EnglishCorpusDocument>, val terms: Series<EnglishCorpusTerm>,
    val sentences: Series<EnglishCorpusSentence>, val occurrences: Series<EnglishCorpusOccurrence>) {
    val tokenCount: Int = documents.view.sumOf { it.includedTokens }
    val rawTokenCount: Int = occurrences.size
    val matrixCid: ContentId = ContentId.of(CanonicalCbor.encodeMap(matrix()))
    /** Hash of [toMap] exactly: the host may retain those canonical bytes under this identity. */
    val cid: ContentId by lazy { ContentId.of(CanonicalCbor.encodeMap(toMap())) }
    private val sentenceById = sentences.view.associateBy { it.id }
    private val occurrencesBySentence = occurrences.view.groupBy { it.sentenceId }
    private val index = TermPostings().also { postings ->
        for (sentence in sentences.view) {
            val tokens = occurrencesBySentence[sentence.id].orEmpty().filter { it.included }.map { it.term }
            if (tokens.isNotEmpty()) postings.upsertTokens(sentence.id, tokens)
        }
    }

    fun matrix(): Map<String, Any?> = _m["schema" j "trikeshed.english-matrix.v1", "policy" j policy.toMap(),
        "documentUnit" j "distinct-original-and-extracted-content", "vocabulary" j (terms α { it.lemma }).toList(),
        "documents" j (documents α { it.toMap() }).toList(), "tokenCount" j tokenCount]
    fun toMap(): Map<String, Any?> = _m["schema" j "trikeshed.english-corpus.v1", "reviewStatus" j "unreviewed",
        "authority" j "statistics-of-reported-parser-observations", "legalMeaning" j "not-established",
        "countsAreEvidenceConfidence" j false, "coverage" j "explicitly-selected-source-ranges-only",
        "matrixCid" j matrixCid.value, "matrix" j matrix(), "rawTokenCount" j rawTokenCount,
        "retrieval" j _m["unit" j "included-lexical-sentence", "method" j index.params.method.name,
            "idfMethod" j index.params.idfMethod.name, "k1" j index.params.k1, "b" j index.params.b, "delta" j index.params.delta],
        "terms" j (terms α { it.toMap() }).toList(), "sentences" j (sentences α { it.toMap() }).toList(),
        "occurrences" j (occurrences α { it.toMap() }).toList()]

    /** Query terms are already lemmas; no hidden tokenization, NLP, model or inferred relation. */
    fun search(query: Series<String>, limit: Int = 32): Series<EnglishCorpusHit> {
        require(query.size in 1..64 && limit in 1..256) { "English query term/result limit" }
        val selectedPolicy = policy
        val normalized = (query α { term -> EnglishCorpus.field(term); selectedPolicy.term(term) }).toList()
        val selected = normalized.toSet()
        return index.topKTokens(normalized, limit).map { hit ->
            val sentence = requireNotNull(sentenceById[hit.key as ContentId])
            val source = documents[sentence.document]
            val matches = occurrencesBySentence[sentence.id].orEmpty().filter { it.included && it.term in selected }
            EnglishCorpusHit(sentence, source.originalCid, source.extractedTextCid, hit.score, matches.toSeries())
        }.toSeries()
    }
}

object EnglishCorpus {
    const val MAX_RECEIPTS = 256
    const val MAX_DOCUMENTS = 256
    const val MAX_TOKENS = 20_000
    const val MAX_VOCABULARY = 4096
    const val MAX_TEXT_UNITS = 8 * 1024 * 1024
    const val MAX_BYTES = 32 * 1024 * 1024
    private val protected = _s["not", "no", "never", "neither", "nor", "cannot", "n't", "unless", "except",
        "n’t", "without", "only", "if", "until", "shall", "must", "may", "can", "will", "would", "should"]

    fun build(receipts: Series<EnglishObservationReceipt>, policy: EnglishCorpusPolicy = EnglishCorpusPolicy()): EnglishCorpusSnapshot {
        require(receipts.size in 1..MAX_RECEIPTS) { "Select 1..256 English inspection receipts" }
        require(policy.stopWords.size <= 4096 && policy.posPrefixes.size <= 64) { "Corpus filter limit" }
        for (value in policy.stopWords) field(value)
        for (value in policy.posPrefixes) { field(value); require(value.length <= 16) { "POS prefix limit" } }
        val frozenPolicy = policy.copy(stopWords = policy.stopWords.toSet(), posPrefixes = policy.posPrefixes.toSet())
        val stops = frozenPolicy.stopWords.map { frozenPolicy.term(it) }.toSet()
        val selected = receipts.view.toMutableList().sortedWith(compareBy({ it.originalCid.value }, { it.extractedTextCid.value }, { it.begin }, { it.end }, { it.receiptCid.value }))
        require(selected.map { it.receiptCid }.toSet().size == selected.size) { "Duplicate inspection receipt" }
        val originals = linkedMapOf<ContentId, MutableList<EnglishObservationReceipt>>()
        var units = 0L
        for (receipt in selected) {
            require(receipt.begin >= 0 && receipt.end > receipt.begin && receipt.end <= receipt.totalLength) { "Invalid selected source range" }
            receipt.selection?.let { source ->
                field(source.project); field(source.id); field(source.rev)
                require(source.cid == receipt.originalCid.value && source.project.isNotBlank() && source.id.isNotBlank() && source.rev.isNotBlank() && source.seq >= 0) { "Source selection identity mismatch" }
            }
            val previous = originals.getOrPut(receipt.originalCid) { mutableListOf() }
            if (previous.isNotEmpty()) {
                require(previous[0].extractedTextCid == receipt.extractedTextCid && previous[0].totalLength == receipt.totalLength) { "Mixed extractions for one original source" }
                require(previous.last().end <= receipt.begin) { "Overlapping source ranges would duplicate words" }
            }
            previous.add(receipt)
            units += receipt.end.toLong() - receipt.begin
            require(units <= MAX_TEXT_UNITS) { "Selected source coverage exceeds corpus budget" }
        }
        require(originals.size <= MAX_DOCUMENTS)
        val sentences = mutableListOf<EnglishCorpusSentence>()
        val occurrences = mutableListOf<EnglishCorpusOccurrence>()
        val ranges = mutableListOf<Series<EnglishCorpusRange>>()
        var fieldUnits = 0L
        fun bounded(value: String) { field(value); fieldUnits += value.length; require(fieldUnits <= MAX_TEXT_UNITS) { "Corpus text budget" } }
        for ((document, entry) in originals.entries.withIndex()) {
            val documentRanges = mutableListOf<EnglishCorpusRange>()
            for (receipt in entry.value) {
                documentRanges.add(EnglishCorpusRange(receipt.receiptCid, receipt.begin, receipt.end, receipt.selection))
                require(receipt.sentences.size <= 512) { "Inspection sentence limit" }
                val sentenceIndices = mutableSetOf<Int>()
                var previousEnd = receipt.begin
                for (sentence in receipt.sentences.view) {
                    bounded(sentence.quote)
                    require(sentence.index >= 0 && sentenceIndices.add(sentence.index) && sentence.begin >= previousEnd && sentence.end > sentence.begin && sentence.end <= receipt.end && sentence.quote.length == sentence.end - sentence.begin) { "Invalid source sentence" }
                    previousEnd = sentence.end
                    val sentenceId = ContentId.of(CanonicalCbor.encodeMap(_m["receiptCid" j receipt.receiptCid.value,
                        "sentence" j sentence.index, "begin" j sentence.begin, "end" j sentence.end]))
                    sentences.add(EnglishCorpusSentence(sentenceId, document, receipt.receiptCid, receipt.selection,
                        sentence.index, sentence.begin, sentence.end, sentence.quote))
                    require(sentence.tokens.size in 1..8192 && occurrences.size <= MAX_TOKENS - sentence.tokens.size) { "Corpus original token budget" }
                    val tokenIndices = mutableSetOf<Int>()
                    var tokenEnd = sentence.begin
                    for (token in sentence.tokens.view) {
                        require(occurrences.size < MAX_TOKENS) { "Corpus exceeds 20000 original tokens" }
                        require(token.copy == 0) { "Parser copy nodes are not lexical token occurrences" }
                        bounded(token.word); bounded(token.lemma); bounded(token.pos)
                        require(token.index > 0 && tokenIndices.add(token.index) && token.begin >= tokenEnd && token.end > token.begin && token.end <= sentence.end) { "Invalid original token occurrence" }
                        val begin = token.begin - sentence.begin; val end = token.end - sentence.begin
                        boundary(sentence.quote, begin); boundary(sentence.quote, end)
                        require(sentence.quote.substring(begin, end) == token.word && sentence.quote.substring(tokenEnd - sentence.begin, begin).isBlank()) { "Token differs from source quotation or omits text" }
                        tokenEnd = token.end
                        val term = frozenPolicy.term(token.lemma)
                        val keep = term.any { it.isLetterOrDigit() } && (frozenPolicy.protectLegalMarkers && term.lowercase() in protected ||
                            term !in stops && (frozenPolicy.posPrefixes.isEmpty() || frozenPolicy.posPrefixes.any { token.pos.startsWith(it) }))
                        occurrences.add(EnglishCorpusOccurrence(document, receipt.receiptCid, sentenceId, token.index,
                            token.begin, token.end, token.word, token.lemma, term, token.pos, keep))
                    }
                    require(sentence.quote.substring(tokenEnd - sentence.begin).isBlank()) { "Tokens omit trailing sentence text" }
                }
            }
            ranges.add(documentRanges.toSeries())
        }
        val vocabulary = occurrences.asSequence().filter { it.included }.map { it.term }.distinct().sorted().toList()
        require(vocabulary.size <= MAX_VOCABULARY) { "Corpus exceeds 4096 lexical terms" }
        val termIndices = mutableMapOf<String, Int>()
        vocabulary.forEachIndexed { index, value -> termIndices[value] = index }
        val counts = Array(originals.size) { IntArray(vocabulary.size) }
        val raw = IntArray(originals.size); val included = IntArray(originals.size)
        for (occurrence in occurrences) {
            raw[occurrence.document]++
            if (occurrence.included) { counts[occurrence.document][termIndices.getValue(occurrence.term)]++; included[occurrence.document]++ }
        }
        val documents = originals.entries.withIndex().map { (index, entry) ->
            EnglishCorpusDocument(index, entry.key, entry.value[0].extractedTextCid, entry.value[0].totalLength,
                ranges[index], raw[index], included[index], counts[index].toSeries())
        }
        val terms = vocabulary.mapIndexed { index, lemma -> EnglishCorpusTerm(index, lemma,
            counts.sumOf { it[index] }, counts.count { it[index] > 0 }) }
        val snapshot = EnglishCorpusSnapshot(frozenPolicy, documents.toSeries(), terms.toSeries(), sentences.toSeries(), occurrences.toSeries())
        require(CanonicalCbor.encodeMap(snapshot.toMap()).size <= MAX_BYTES) { "Corpus receipt exceeds 32MiB" }
        return snapshot
    }

    internal fun field(value: String) {
        require(value.length <= 16_384) { "Corpus field limit" }
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (c.isHighSurrogate()) { require(i < value.length && value[i].isLowSurrogate()) { "Invalid corpus Unicode" }; i++ }
            else require(!c.isLowSurrogate()) { "Invalid corpus Unicode" }
        }
    }
    private fun boundary(text: String, offset: Int) {
        require(!(offset > 0 && offset < text.length && text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())) { "Corpus span splits a Unicode scalar" }
    }
}

/** Symmetric Dirichlet priors and an explicit finite sampler budget, never a language generator. */
data class EnglishLdaConfig(val topics: Int = 4, val iterations: Int = 50, val seed: Int = 1,
    val alpha: Double = .1, val beta: Double = .01) {
    fun toMap(): Map<String, Any?> = _m["topics" j topics, "iterations" j iterations,
        "seed" j seed, "alpha" j alpha, "beta" j beta]
}

/** Frozen count matrices and posterior mixtures; topic labels have no inferred legal meaning. */
class EnglishLdaResult internal constructor(val corpusCid: ContentId, val matrixCid: ContentId,
    val config: EnglishLdaConfig, val vocabulary: Series<String>, val tokenCount: Int,
    private val topicWords: Array<IntArray>, private val documentTopics: Array<IntArray>,
    private val totals: IntArray, private val documentLengths: IntArray) {
    val topicWordCounts: Series<Series<Int>> = topicWords.size j { topicWords[it].toSeries() }
    val documentTopicCounts: Series<Series<Int>> = documentTopics.size j { documentTopics[it].toSeries() }
    val topicTotals: Series<Int> = totals.toSeries()
    val conditionalWeights: Long get() = tokenCount.toLong() * config.iterations * config.topics
    val cid: ContentId by lazy { ContentId.of(CanonicalCbor.encodeMap(toMap())) }
    private val termIndices = vocabulary.view.withIndex().associate { (it.value j it.index).pair }

    /** Closed-vocabulary posterior predictive probability; unknown terms are explicitly absent. */
    fun wordProbability(topic: Int, term: String): Double? {
        require(topic in 0 until config.topics) { "Topic index outside model" }
        val index = termIndices[term] ?: return null
        return (topicWords[topic][index] + config.beta) / (totals[topic] + vocabulary.size * config.beta)
    }
    fun documentProbabilities(document: Int): Series<Double> {
        require(document in documentTopics.indices) { "Document index outside model" }
        val counts = documentTopics[document]
        val divisor = documentLengths[document] + config.topics * config.alpha
        return counts.size j { (counts[it] + config.alpha) / divisor }
    }
    fun topicTerms(topic: Int, limit: Int = 32): Series<Map<String, Any?>> {
        require(topic in 0 until config.topics && limit in 1..128) { "Topic display limit" }
        val order = (0 until vocabulary.size).sortedWith(compareByDescending<Int> { topicWords[topic][it] }.thenBy { it }).take(limit)
        return order.map { word -> _m["index" j word, "lemma" j vocabulary[word], "count" j topicWords[topic][word],
            "probability" j wordProbability(topic, vocabulary[word])] }.toSeries()
    }
    fun toMap(): Map<String, Any?> = _m["schema" j "trikeshed.english-lda.v1", "corpusCid" j corpusCid.value,
        "matrixCid" j matrixCid.value, "config" j config.toMap(), "algorithm" j "collapsed-gibbs-symmetric-dirichlet/1",
        "random" j "splitmix64/1", "order" j "canonical-documents-then-vocabulary-then-occurrences",
        "reviewStatus" j "unreviewed", "authority" j "descriptive-statistics-only", "legalMeaning" j "not-established",
        "countsAreEvidenceConfidence" j false, "tokenCount" j tokenCount,
        "conditionalWeights" j conditionalWeights, "vocabulary" j vocabulary.toList(),
        "topicWordCounts" j (topicWordCounts α { it.toList() }).toList(),
        "documentTopicCounts" j (documentTopicCounts α { it.toList() }).toList(), "topicTotals" j topicTotals.toList(),
        "documentTopics" j documentTopics.indices.map { document -> _m["document" j document,
            "tokens" j documentLengths[document], "priorOnly" j (documentLengths[document] == 0),
            "probabilities" j documentProbabilities(document).toList()] },
        "topics" j (0 until config.topics).map { topic -> _m["topic" j topic, "tokens" j totals[topic],
            "terms" j topicTerms(topic).toList()] }]
}

/** Deterministic bounded collapsed Gibbs LDA over the same source-pinned integer matrix. */
fun EnglishCorpusSnapshot.lda(config: EnglishLdaConfig = EnglishLdaConfig()): EnglishLdaResult {
    require(config.topics in 1..16 && config.iterations in 1..100) { "LDA supports 1..16 topics and 1..100 iterations" }
    require(config.alpha.isFinite() && config.alpha in 1e-9..1000.0 && config.beta.isFinite() && config.beta in 1e-9..1000.0) { "Finite positive LDA priors required" }
    require(documents.size in 1..EnglishCorpus.MAX_DOCUMENTS && terms.size in 1..EnglishCorpus.MAX_VOCABULARY && tokenCount in 1..EnglishCorpus.MAX_TOKENS) { "LDA requires a nonempty bounded lexical matrix" }
    val work = tokenCount.toLong() * config.iterations * config.topics
    require(work <= 20_000_000L) { "LDA exceeds 20000000 conditional weights" }
    val vocabulary = terms α { it.lemma }
    val words = IntArray(tokenCount)
    val sources = IntArray(tokenCount)
    val lengths = IntArray(documents.size)
    var at = 0
    for (document in documents.view) {
        require(document.index in 0 until documents.size && document.counts.size == terms.size) { "Malformed corpus matrix" }
        var length = 0
        for (word in 0 until terms.size) {
            val count = document.counts[word]
            require(count >= 0 && count <= tokenCount - at) { "Malformed corpus token count" }
            repeat(count) { words[at] = word; sources[at] = document.index; at++ }
            length += count
        }
        require(length == document.includedTokens) { "Document count conservation failed" }
        lengths[document.index] = length
    }
    check(at == tokenCount) { "Corpus count conservation failed" }
    val rng = EnglishLdaRandom(config.seed)
    val assignments = IntArray(tokenCount)
    val documentTopics = Array(documents.size) { IntArray(config.topics) }
    val topicWords = Array(config.topics) { IntArray(terms.size) }
    val topicTotals = IntArray(config.topics)
    for (i in words.indices) {
        val topic = rng.nextInt(config.topics)
        assignments[i] = topic
        documentTopics[sources[i]][topic]++; topicWords[topic][words[i]]++; topicTotals[topic]++
    }
    val weights = DoubleArray(config.topics)
    repeat(config.iterations) {
        for (i in words.indices) {
            val word = words[i]; val document = sources[i]; val old = assignments[i]
            documentTopics[document][old]--; topicWords[old][word]--; topicTotals[old]--
            var sum = 0.0
            for (topic in 0 until config.topics) {
                val weight = (documentTopics[document][topic] + config.alpha) *
                    (topicWords[topic][word] + config.beta) / (topicTotals[topic] + terms.size * config.beta)
                check(weight.isFinite() && weight > 0) { "Invalid LDA conditional weight" }
                weights[topic] = weight; sum += weight
            }
            val threshold = rng.nextDouble() * sum
            var cumulative = 0.0
            var topic = config.topics - 1
            for (candidate in 0 until config.topics) {
                cumulative += weights[candidate]
                if (threshold < cumulative) { topic = candidate; break }
            }
            assignments[i] = topic
            documentTopics[document][topic]++; topicWords[topic][word]++; topicTotals[topic]++
        }
    }
    check(topicTotals.sum() == tokenCount)
    for (document in documentTopics.indices) check(documentTopics[document].all { it >= 0 } && documentTopics[document].sum() == lengths[document])
    for (topic in topicWords.indices) check(topicWords[topic].all { it >= 0 } && topicWords[topic].sum() == topicTotals[topic])
    for (word in 0 until terms.size) check(topicWords.sumOf { it[word] } == terms[word].frequency)
    return EnglishLdaResult(cid, matrixCid, config, vocabulary, tokenCount, topicWords, documentTopics, topicTotals, lengths)
}

/** Explicit algorithm revision avoids depending on a platform or Kotlin-version RNG. */
private class EnglishLdaRandom(seed: Int) {
    private var state = seed.toLong()
    private fun nextLong(): Long {
        state += -7046029254386353131L
        var value = state
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }
    fun nextInt(bound: Int): Int = ((nextLong() ushr 1) % bound.toLong()).toInt()
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / 9_007_199_254_740_992.0
}
