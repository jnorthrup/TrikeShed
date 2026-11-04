package borg.trikeshed.nlp

import borg.trikeshed.job.ContentId
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentSource

/** Exact parser observations and lexical candidates. No marker establishes its legal scope or meaning. */
object EnglishInspection {
    const val MAX_CHARS = 65_536
    const val MAX_SENTENCES = 512
    const val MAX_TOKENS = 8192
    const val MAX_DEPENDENCIES = 16_384
    const val MAX_MARKERS = 4096
    const val MAX_FIELD = 4096
    const val MAX_ANCHORS = 16_384
    const val MAX_QUOTED_ANCHORS = 4096
    const val MAX_BYTES = 4 * 1024 * 1024

    private data class Marker(val kind: String, val label: String, val pattern: Regex)
    private fun words(kind: String, label: String, pattern: String) =
        Marker(kind, label, Regex("\\b(?:$pattern)\\b", RegexOption.IGNORE_CASE))
    private val markers = s_[
        words("condition", "condition-marker", "if|unless|when|whenever|until|before|after|upon|provided\\s+that|providing\\s+that|only\\s+if|even\\s+if|as\\s+long\\s+as|in\\s+the\\s+event\\s+that"),
        words("exception", "exception-marker", "unless|except|excepting|notwithstanding|save\\s+that|other\\s+than"),
        words("condition", "qualification-marker", "subject\\s+to"),
        words("negation", "negation-marker", "not|never|no|neither|nor|cannot"),
        Marker("negation", "contraction-marker", Regex("n['’]t\\b", RegexOption.IGNORE_CASE)),
        words("modal", "modal-marker", "shall|must|may|might|should|can|cannot|could|will|would|ought"),
        words("definition", "definition-marker", "means|mean|defined\\s+(?:as|in)|as\\s+used\\s+in|refers\\s+to"),
        Marker("cross-reference", "reference-marker", Regex("\\b(?:section|article|paragraph|clause|exhibit|schedule|appendix|annex|chapter)s?\\s+[A-Za-z0-9]+(?:[.\\-][A-Za-z0-9]+)*(?:\\([A-Za-z0-9]+\\))*(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)),
        Marker("cross-reference", "section-sign-marker", Regex("§{1,2}\\s*[0-9]+(?:[.\\-][A-Za-z0-9]+)*(?:\\([A-Za-z0-9]+\\))*")),
    ]

    /** [parsed] must describe exactly the requested range; all returned offsets address [source.text]. */
    fun inspect(source: DocumentSource, parsed: NlpDocument, begin: Int, end: Int): Map<String, Any?> {
        validText(source.text)
        require(ContentId.of(source.text.encodeToByteArray()) == source.extractedTextCid) { "Extracted text identity mismatch" }
        require(source.anchors.size <= MAX_ANCHORS && source.provenance.size <= 64 && source.unresolved.size <= 64) { "Source metadata limit" }
        field(source.category)
        for (label in source.unresolved) field(label)
        for ((key, value) in source.provenance) { field(key); field(value) }
        for (anchor in source.anchors) {
            require(anchor.begin >= 0 && anchor.end >= anchor.begin && anchor.end <= source.text.length) { "Source anchor outside extracted text" }
            boundary(source.text, anchor.begin); boundary(source.text, anchor.end)
            require(anchor.page == null || anchor.page > 0) { "Source page must be positive" }
            require(anchor.coordinates == null || anchor.coordinates.size == 4 && anchor.coordinates.all { it >= 0 }) { "Invalid source coordinates" }
            require(anchor.unresolved.size <= 64) { "Source anchor metadata limit" }
            for (value in s_[anchor.folio, anchor.section, anchor.table, anchor.diagram].view) value?.let(::field)
            for (value in anchor.unresolved) field(value)
        }
        span(source.text, begin, end)
        require(end - begin <= MAX_CHARS) { "English inspection exceeds 65536 UTF-16 units" }
        val text = source.text.substring(begin, end)
        require(parsed.text == text) { "Parser text differs from selected source range" }
        require(parsed.sentences.size in 0..MAX_SENTENCES) { "English sentence limit" }
        require(text.isBlank() || parsed.sentences.size > 0) { "Parser omitted nonblank text" }
        var tokenCount = 0
        var dependencyCount = 0
        var markerCount = 0
        var quotedAnchorCount = 0
        var previousSentenceEnd = 0
        val sentenceIndices = mutableSetOf<Int>()
        val rows = mutableListOf<Map<String, Any?>>()
        for (sentence in parsed.sentences.view) {
            require(sentence.index >= 0 && sentenceIndices.add(sentence.index)) { "Duplicate or invalid sentence index" }
            span(text, sentence.begin, sentence.end)
            require(sentence.begin >= previousSentenceEnd && text.substring(previousSentenceEnd, sentence.begin).isBlank()) { "Sentence spans overlap or omit text" }
            previousSentenceEnd = sentence.end
            require(sentence.tokens.size in 1..MAX_TOKENS && tokenCount <= MAX_TOKENS - sentence.tokens.size) { "English token limit" }
            tokenCount += sentence.tokens.size
            require(sentence.dependencies.size in 1..MAX_DEPENDENCIES && dependencyCount <= MAX_DEPENDENCIES - sentence.dependencies.size) { "English dependency limit" }
            dependencyCount += sentence.dependencies.size
            val indices = mutableSetOf<Int>()
            var previousTokenEnd = sentence.begin
            val tokens = mutableListOf<Map<String, Any?>>()
            for (token in sentence.tokens.view) {
                require(token.index > 0 && indices.add(token.index)) { "Duplicate or invalid token index" }
                span(text, token.begin, token.end)
                require(token.begin >= sentence.begin && token.end <= sentence.end && token.begin >= previousTokenEnd &&
                    text.substring(previousTokenEnd, token.begin).isBlank()) { "Token spans overlap, omit text or leave sentence" }
                require(text.substring(token.begin, token.end) == token.word) { "Parser word differs from its source span" }
                previousTokenEnd = token.end
                field(token.word); field(token.lemma); field(token.tag); field(token.ner)
                tokens.add(_m["index" j token.index, "begin" j (begin + token.begin), "end" j (begin + token.end),
                    "word" j token.word, "lemma" j token.lemma, "pos" j token.tag, "ner" j token.ner])
            }
            require(text.substring(previousTokenEnd, sentence.end).isBlank()) { "Tokens omit sentence text" }
            val edges = mutableSetOf<NlpDependency>()
            var roots = 0
            val children = mutableMapOf<Int, MutableList<Int>>()
            val dependencies = mutableListOf<Map<String, Any?>>()
            for (dependency in sentence.dependencies.view) {
                field(dependency.relation)
                require(dependency.relation.isNotBlank() && edges.add(dependency)) { "Duplicate or empty dependency" }
                require(dependency.dependent in indices && (dependency.governor == 0 || dependency.governor in indices) &&
                    dependency.governor != dependency.dependent) { "Invalid dependency endpoint" }
                require((dependency.governor == 0) == dependency.relation.equals("root", true)) { "Invalid root dependency" }
                if (dependency.governor == 0) roots++
                children.getOrPut(dependency.governor) { mutableListOf() }.add(dependency.dependent)
                dependencies.add(_m["governor" j dependency.governor, "dependent" j dependency.dependent, "relation" j dependency.relation])
            }
            require(roots == 1) { "Parser must report one dependency root" }
            val reachable = mutableSetOf<Int>()
            val pending = mutableListOf(0)
            while (pending.isNotEmpty()) {
                val head = pending.removeAt(pending.lastIndex)
                for (child in children[head].orEmpty()) if (reachable.add(child)) pending.add(child)
            }
            require(reachable == indices) { "Dependency graph omits or disconnects token" }
            val quote = text.substring(sentence.begin, sentence.end)
            val matches = mutableListOf<Map<String, Any?>>()
            for (marker in markers.view) for (match in marker.pattern.findAll(quote)) {
                require(markerCount < MAX_MARKERS) { "English marker limit" }; markerCount++
                val first = begin + sentence.begin + match.range.first
                val last = first + match.value.length
                matches.add(_m["kind" j marker.kind, "label" j marker.label, "begin" j first, "end" j last,
                    "quote" j source.text.substring(first, last), "status" j "lexical-candidate"])
            }
            quotedAnchorCount += source.anchors.count { it.begin < begin + sentence.end && it.end > begin + sentence.begin }
            require(quotedAnchorCount <= MAX_QUOTED_ANCHORS) { "Quoted source anchor limit" }
            rows.add(_m["index" j sentence.index, "begin" j (begin + sentence.begin), "end" j (begin + sentence.end), "quote" j quote, "quotation" j source.quotation(begin + sentence.begin, begin + sentence.end),
                "tokens" j tokens, "dependencies" j dependencies,
                "markers" j matches.sortedWith(compareBy({ it["begin"] as Int }, { it["end"] as Int }, { it["kind"] as String }))])
        }
        require(text.substring(previousSentenceEnd).isBlank()) { "Parser omits trailing text" }
        val metadata = parsed.metadata?.let { observed ->
            field(observed.processor); field(observed.implementation)
            require(observed.runtime.size <= 64 && observed.configuration.size <= 64) { "Parser metadata limit" }
            for (values in s_[observed.runtime, observed.configuration].view) for ((key, value) in values) { field(key); field(value) }
            _m["processor" j observed.processor, "implementation" j observed.implementation,
                "runtime" j observed.runtime, "configuration" j observed.configuration]
        }
        val inventory = _m["schema" j "trikeshed.english-inspection.v1", "reviewStatus" j "unreviewed",
            "authority" j "lexical-and-parser-observations-only", "originalCid" j source.originalCid.value,
            "extractedTextCid" j source.extractedTextCid.value,
            "range" j _m["begin" j begin, "end" j end, "totalLength" j source.text.length, "complete" j (begin == 0 && end == source.text.length)],
            "parser" j metadata, "coverage" j _m["kind" j "bounded-text-range", "semantics" j "not-established",
                "markerMethod" j "literal-word-and-phrase-candidates", "scope" j "sentence-token-dependency-observations",
                "omitted" j _l["constituency", "coreference", "openie", "kbp", "legal-interpretation"]],
            "sentences" j rows, "counts" j _m["sentences" j rows.size, "tokens" j tokenCount,
                "dependencies" j dependencyCount, "markers" j markerCount])
        require(CanonicalCbor.encodeMap(inventory).size <= MAX_BYTES) { "English inventory exceeds 4MiB" }
        return inventory
    }

    private fun field(value: String) { require(value.length <= MAX_FIELD) { "Parser field limit" }; validText(value) }
    private fun span(text: String, begin: Int, end: Int) {
        require(begin >= 0 && end > begin && end <= text.length) { "Invalid English text span" }
        boundary(text, begin); boundary(text, end)
    }
    private fun boundary(text: String, at: Int) {
        require(!(at > 0 && at < text.length && text[at].isLowSurrogate() && text[at - 1].isHighSurrogate())) { "English span cuts UTF-16 scalar" }
    }
    private fun validText(value: String) {
        var index = 0
        while (index < value.length) {
            val ch = value[index++]
            if (ch.isHighSurrogate()) { require(index < value.length && value[index].isLowSurrogate()) { "Invalid English Unicode" }; index++ }
            else require(!ch.isLowSurrogate()) { "Invalid English Unicode" }
        }
    }
}
