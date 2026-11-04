package borg.trikeshed.nlp

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentSource

/** Parser roles and bounded quantity-language readings only; never legal force, applicability or admitted facts. */
object EnglishLegalPatterns {
    const val MAX_MODAL_ACTIONS = 1024
    const val MAX_DURATIONS = 1024
    const val MAX_BINDINGS = 32_768
    const val MAX_QUOTED_ANCHORS = 8192
    const val MAX_NUMBER_TOKENS = 24
    const val MAX_BYTES = 4 * 1024 * 1024
    private val modals = _s["shall", "must", "may", "might", "should", "can", "cannot", "could", "will", "would", "ought"]
    private val units = _m["day" j "day", "days" j "day", "week" j "week", "weeks" j "week",
        "month" j "month", "months" j "month", "year" j "year", "years" j "year"]
    private val modifiers = _s["business", "calendar", "working"]
    private val temporal = _s["within", "before", "after", "by"]
    private val ones = _l["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"]
    private val tens = _l["twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"]
    private val numberWords = (ones + tens + _l["hundred", "thousand", "and"]).toSet()
    private val qualifiers = _s["about", "approximately", "at", "least", "most", "up", "to", "more", "less", "than", "over", "under", "half", "quarter", "of"]
    private fun id(index: Int, copy: Int) = "$index:$copy"
    private fun governor(edge: NlpDependency) = id(edge.governor, edge.governorCopy)
    private fun dependent(edge: NlpDependency) = id(edge.dependent, edge.dependentCopy)

    fun inspect(source: DocumentSource, parsed: NlpDocument, begin: Int, end: Int): Map<String, Any?> {
        val observations = EnglishInspection.inspect(source, parsed, begin, end)
        val rawSentences = observations["sentences"] as List<*>
        var modalCount = 0; var durationCount = 0; var bindingCount = 0
        var quotedAnchors = rawSentences.sumOf { raw ->
            (((raw as Map<*, *>)["quotation"] as Map<*, *>)["anchors"] as List<*>).size
        }
        val sentences = mutableListOf<Map<String, Any?>>()
        for ((ordinal, sentence) in parsed.sentences.view.withIndex()) {
            val observed = rawSentences[ordinal] as Map<*, *>
            val nodes = (observed["nodes"] as List<*>).map { it as Map<*, *> }.associateBy { it["id"] as String }
            val tokens = sentence.tokens
            val tokenPositions = tokens.view.withIndex().associate { (it.value.index j it.index).pair }
            val edges: Iterable<NlpDependency> = sentence.dependencies.view
            fun node(nodeId: String) = requireNotNull(nodes[nodeId]) { "Pattern node is not in the observed graph" }
            fun binding(edge: NlpDependency): Map<String, Any?> {
                require(bindingCount++ < MAX_BINDINGS) { "Legal pattern dependency binding limit" }
                return _m["governorNode" j governor(edge), "dependentNode" j dependent(edge), "relation" j edge.relation]
            }
            val markers = observed["markers"] as List<*>
            val markerKinds = markers.map { (it as Map<*, *>)["kind"] as String }.toSet()
            val unresolved = mutableListOf("legal-force-not-established", "cross-sentence-and-paragraph-scope-not-established")
            if ("condition" in markerKinds || "exception" in markerKinds) unresolved.add("guard-scope-not-established")
            if ("negation" in markerKinds) unresolved.add("negation-scope-not-established")
            if ("definition" in markerKinds) unresolved.add("definition-scope-not-established")
            if ("cross-reference" in markerKinds) unresolved.add("reference-resolution-not-established")
            if (edges.any { it.relation.startsWith("conj") || it.relation == "cc" }) unresolved.add("coordination-scope-not-established")
            val actions = mutableListOf<Map<String, Any?>>()
            for (edge in edges) {
                if (edge.relation != "aux") continue
                val modal = node(dependent(edge))
                val lemma = modal["lemma"] as String
                if (modal["pos"] != "MD" && lemma.lowercase() !in modals && (modal["word"] as String).lowercase() !in modals) continue
                require(modalCount++ < MAX_MODAL_ACTIONS) { "Modal/action candidate limit" }
                val actionId = governor(edge)
                fun roles(vararg names: String) = edges.filter { governor(it) == actionId && it.relation in names }
                    .map { _m["relation" j it.relation, "node" j node(dependent(it)), "dependency" j binding(it)] }
                actions.add(_m["status" j "parser-role-candidate", "modalLiteral" j modal["word"], "modalLemma" j lemma,
                    "modal" j modal, "action" j node(actionId), "dependency" j binding(edge),
                    "subjects" j roles("nsubj", "nsubj:pass"), "objects" j roles("obj", "dobj", "iobj"),
                    "relatedDependencies" j edges.filter { governor(it) == actionId || dependent(it) == actionId }.map(::binding),
                    "unresolved" j unresolved.toList()])
            }
            val durations = mutableListOf<Map<String, Any?>>()
            for ((unitPosition, unit) in tokens.view.withIndex()) {
                val unitLemma = units[unit.lemma.lowercase()] ?: units[unit.word.lowercase()] ?: continue
                var numberEnd = unitPosition - 1
                val qualifierTokens = mutableListOf<NlpToken>()
                while (numberEnd >= 0 && tokens[numberEnd].lemma.lowercase() in modifiers) qualifierTokens.add(tokens[numberEnd--])
                if (numberEnd < 0) continue
                val direct = edges.filter { it.governor == unit.index && it.relation == "nummod" }
                // CoreNLP may head a parenthesized quantity at its number, with the unit as npmod.
                val inverse = edges.filter { edge -> edge.dependent == unit.index &&
                    edge.relation in _s["obl:npmod", "nmod:npmod"] && tokenPositions[edge.governor]?.let { position ->
                        tokens[position].tag == "CD" || tokens[position].ner == "NUMBER"
                    } == true }
                val supported = direct + inverse
                val positions = supported.mapNotNull { tokenPositions[if (it.relation == "nummod") it.dependent else it.governor] }
                    .filter { it in 0..numberEnd }
                val numericNer = tokens[numberEnd].ner in _s["NUMBER", "DURATION"] &&
                    (tokens[numberEnd].tag == "CD" || tokens[numberEnd].lemma.lowercase() in numberWords)
                if (positions.isEmpty() && !numericNer) {
                    if (supported.isNotEmpty()) unresolved.add("postposed-number-unit-attachment-not-read")
                    continue
                }
                var numberStart = positions.minOrNull() ?: numberEnd
                val longNumberSpan = numberEnd - numberStart >= MAX_NUMBER_TOKENS
                fun extendsNumber(token: NlpToken): Boolean {
                    val word = token.word.lowercase()
                    return word in numberWords || word in qualifiers || word.matches(Regex("[0-9]+(?:[.,/–—-][0-9]+)*")) ||
                        word in _s["(", ")", "-", "–", "—", "/", ".", ","] ||
                        word.contains('-') && word.split('-').all { it in numberWords }
                }
                while (numberStart > 0 && numberEnd - numberStart + 1 < MAX_NUMBER_TOKENS && extendsNumber(tokens[numberStart - 1])) numberStart--
                require(durationCount++ < MAX_DURATIONS) { "Duration candidate limit" }
                val numericBegin = begin + tokens[numberStart].begin
                val numericEnd = begin + tokens[numberEnd].end
                val literal = source.text.substring(numericBegin, numericEnd)
                val number = if (longNumberSpan) Reading(null, "number-language-token-limit", emptyList()) else number(source, literal, numericBegin)
                val marker = (if (numberStart > 0) tokens[numberStart - 1] else null)?.takeIf { it.lemma.lowercase() in temporal }
                val first = marker?.let { begin + it.begin } ?: numericBegin
                val last = begin + unit.end
                val intersectingAnchors = source.anchors.count { it.begin < last && it.end > first }
                require(quotedAnchors <= MAX_QUOTED_ANCHORS - intersectingAnchors) { "Legal pattern quotation anchor limit" }
                quotedAnchors += intersectingAnchors
                val issues = unresolved.toMutableList()
                number.issue?.let(issues::add)
                val modifierLemmas = qualifierTokens.map { it.lemma.lowercase() }.toSet()
                if (modifierLemmas.size > 1) issues.add("combined-unit-modifier-meaning-not-established")
                if (supported.isEmpty()) issues.add("number-unit-attachment-lexical-ner-only")
                val unitNodes = nodes.values.filter { it["index"] == unit.index }
                val numberBindings = supported.map(::binding)
                val copiedQuantity = supported.any { it.governorCopy != 0 || it.dependentCopy != 0 }
                if (copiedQuantity) issues.add("copy-node-quantity-scope-not-established")
                val markerHeads = unitNodes.map { it["id"] as String }.toSet() + inverse.map(::governor)
                val markerBindings = marker?.let { selected -> edges.filter {
                    governor(it) in markerHeads && it.dependent == selected.index && it.relation == "case"
                }.map(::binding) }.orEmpty()
                if (marker != null && markerBindings.isEmpty()) issues.add("temporal-marker-attachment-not-established")
                durations.add(_m["status" j if (number.value == null || modifierLemmas.size > 1 || copiedQuantity) "unresolved-quantity-candidate" else "lexical-integer-candidate",
                    "begin" j first, "end" j last, "quote" j source.text.substring(first, last),
                    "quotation" j source.quotation(first, last), "quantityLiteral" j literal, "quantityBegin" j numericBegin,
                    "quantityEnd" j numericEnd, "integerValue" j if (copiedQuantity) null else number.value, "quantityAuthority" j "bounded-lexical-reading-only",
                    "numberReadings" j number.forms, "unitLiteral" j unit.word, "unitLemma" j unitLemma,
                    "unitNodes" j unitNodes, "modifiers" j qualifierTokens.asReversed().map { node(id(it.index, 0)) },
                    "temporalMarker" j marker?.let { node(id(it.index, 0)) }, "numberDependencies" j numberBindings,
                    "markerDependencies" j markerBindings,
                    "attachments" j edges.filter { it.dependent == unit.index && it.relation.startsWith("obl") }.map(::binding),
                    "unresolved" j issues])
            }
            sentences.add(_m["index" j sentence.index, "begin" j observed["begin"], "end" j observed["end"],
                "quote" j observed["quote"], "quotation" j observed["quotation"], "markers" j markers,
                "nodes" j observed["nodes"], "dependencies" j observed["dependencies"],
                "modalActions" j actions, "durations" j durations, "unresolved" j unresolved])
        }
        val result = _m["schema" j "trikeshed.english-legal-patterns.v1", "reviewStatus" j "unreviewed",
            "authority" j "parser-role-and-quantity-candidates-only", "originalCid" j source.originalCid.value,
            "extractedTextCid" j source.extractedTextCid.value, "selection" j source.project?.toMap(),
            "extraction" j source.extraction?.toMap(), "range" j observations["range"], "parser" j observations["parser"],
            "coverage" j "modal-auxiliary-roles-and-bounded-number-unit-language; legal meaning and intersentence scope not established",
            "sentences" j sentences, "counts" j _m["modalActions" j modalCount, "durations" j durationCount, "dependencyBindings" j bindingCount]]
        require(CanonicalCbor.encodeMap(result).size <= MAX_BYTES) { "Legal pattern inventory exceeds 4MiB" }
        return result
    }

    private data class Reading(val value: Int?, val issue: String?, val forms: List<Map<String, Any?>>)
    private fun number(source: DocumentSource, literal: String, begin: Int): Reading {
        fun form(text: String, start: Int, value: Int?) = _m["begin" j start, "end" j (start + text.length),
            "quote" j source.text.substring(start, start + text.length), "integerValue" j value]
        val pair = Regex("^([A-Za-z\\s-]+?)\\s*\\(\\s*([0-9]+)\\s*\\)$").matchEntire(literal)
        if (pair != null) {
            val left = pair.groups[1]!!; val right = pair.groups[2]!!
            // Capture ranges are not part of Kotlin/JS MatchGroup. This grammar anchors the
            // left form at zero and places the digit form after the one opening parenthesis.
            val opening = literal.indexOf('(')
            val rightStart = literal.indexOf(right.value, opening + 1)
            require(opening >= 0 && rightStart > opening && literal.startsWith(left.value) &&
                literal.substring(opening + 1, rightStart).all { it.isWhitespace() } &&
                literal.substring(rightStart, rightStart + right.value.length) == right.value) { "Quantity capture span mismatch" }
            val a = integer(left.value); val b = integer(right.value)
            return Reading(if (a != null && a == b) a else null,
                if (a == null || b == null) "unsupported-number-language" else if (a != b) "conflicting-number-forms" else null,
                _l[form(left.value, begin, a), form(right.value, begin + rightStart, b)])
        }
        val value = integer(literal)
        return Reading(value, if (value == null) "unsupported-number-language" else null, _l[form(literal, begin, value)])
    }

    /** Digits 0..9999, or explicit one..nine thousands/hundreds plus a conventional below-100 tail. */
    private fun integer(text: String): Int? {
        if (text.matches(Regex("[0-9]{1,4}"))) return text.toInt()
        if (!text.matches(Regex("[A-Za-z]+(?:[\\s-]+[A-Za-z]+)*"))) return null
        val words = text.lowercase().split(Regex("[\\s-]+")).filter { it.isNotEmpty() }
        fun below100(part: List<String>): Int? {
            if (part.size == 1) return ones.indexOf(part[0]).takeIf { it >= 0 } ?: tens.indexOf(part[0]).takeIf { it >= 0 }?.let { (it + 2) * 10 }
            if (part.size != 2) return null
            val ten = tens.indexOf(part[0]); val one = ones.indexOf(part[1])
            return if (ten >= 0 && one in 1..9) (ten + 2) * 10 + one else null
        }
        fun below1000(part: List<String>): Int? {
            if (part.size < 2 || part[1] != "hundred") return below100(part)
            val head = ones.indexOf(part[0]); if (head !in 1..9) return null
            if (part.size == 2) return head * 100
            val tail = part.drop(if (part[2] == "and") 3 else 2)
            val rest = below100(tail) ?: return null
            return if (rest > 0) head * 100 + rest else null
        }
        val thousand = words.indexOf("thousand")
        if (thousand < 0) return below1000(words)
        if (thousand != 1 || words.count { it == "thousand" } != 1) return null
        val head = ones.indexOf(words[0]); if (head !in 1..9) return null
        if (words.size == 2) return head * 1000
        val rest = below1000(words.drop(if (words[2] == "and") 3 else 2)) ?: return null
        return if (rest > 0) head * 1000 + rest else null
    }
}
