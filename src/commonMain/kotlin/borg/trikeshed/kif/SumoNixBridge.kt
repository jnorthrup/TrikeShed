package borg.trikeshed.kif

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf

/** Immutable artifacts for the pinned upstream solver, never a local inference result. */
data class SumoNixExport(val inputJson: String, val mlir: String, val inputCid: ContentId,
                         val frameId: ContentId, val scope: String)

/** Positive scalar Horn interchange. The host supplies source/review authority, not guest JSON. */
object SumoNixBridge {
    const val SUMO_REVISION = "1e2fb138d562769d21e7f3e6cfdade9129705ec4"
    const val MLIR_REVISION = "1547e4a37ea1393a7cd3e3cfe7a89aff38a36dba"
    const val SCHEMA = "sumo-nix-positive-horn/1"

    /**
     * Finite input/export bounds only. Upstream computes an unbounded fixpoint;
     * execution needs a host deadline, memory and output cap. No native execution
     * or host approval is established by these artifacts. ontology=null keeps
     * every proof premise in the supplied facts/rules, without hidden ancestry.
     */
    fun compile(revision: Long, sources: List<KifSourceAssertion>, query: String,
                scope: String = "host-explicit-assertions"): SumoNixExport {
        require(revision >= 0 && sources.size <= 128 && sources.map { it.id }.distinct().size == sources.size)
        require(sources.sumOf { it.text.length.toLong() } <= 1_048_576)
        require(scope in _s["host-explicit-assertions", "review-record-observations"])
        val goal = atom(KifSemantic.decode(query).formula, variables = false)
        val facts = mutableListOf<List<String>>()
        val rules = mutableListOf<List<Any>>()
        val factSources = mutableListOf<Map<String, Any?>>()
        val ruleSources = mutableListOf<Map<String, Any?>>()
        val excluded = mutableListOf<Map<String, Any?>>()
        val theory = StringBuilder("module {\n  kif.theory @trikeshed {\n")
        var node = 0
        fun term(words: List<String>): String {
            val operands = words.map { word ->
                val name = "%n${node++}"
                theory.append("    $name = kif.${if (word.startsWith('?')) "var" else "atom"} ${mlirString(word)}\n")
                name
            }
            val name = "%n${node++}"
            theory.append("    $name = kif.list(${operands.joinToString(", ")})\n")
            return name
        }
        for (source in sources) {
            require(source.id.length in 1..1024 && source.sourceRevision.length in 1..1024 && source.text.length <= 65536)
            for (text in s_[source.id, source.sourceRevision, source.text].view) text.encodeToByteArray(throwOnInvalidSequence = true)
            val receipt = linkedMapOf<String, Any?>("id" to source.id, "sourceCid" to source.sourceCid.value,
                "sourceRevision" to source.sourceRevision, "review" to source.review.name, "text" to source.text)
            receipt["sourceNodeId"] = ContentId.of(CanonicalCbor.encodeMap(receipt)).value
            val decoded = runCatching { KifSemantic.decode(source.text).formula }
            val admitted = runCatching {
                require(source.review == KifReview.APPROVED) { "Host review is ${source.review}" }
                val formula = decoded.getOrThrow()
                if (formula is KifFormula.Implies) {
                    val premises = (formula.antecedent as? KifFormula.And)?.bodies ?: _l[formula.antecedent]
                    require(premises.size in 1..16) { "Horn premise limit" }
                    val body = premises.map { atom(it, variables = true) }
                    val conclusion = atom(formula.consequent, variables = true)
                    val bound = body.flatten().filter { it.startsWith('?') }.toSet()
                    require(conclusion.filter { it.startsWith('?') }.all { it in bound }) { "Horn consequent is not range restricted" }
                    val antecedent: Any = if (body.size == 1) body.single() else _l.get<Any>("and") + body
                    rules.add(_l["=>", antecedent, conclusion])
                    val ruleId = "${conclusion[0]}#${rules.size}"
                    ruleSources.add(_m["id" j ruleId, "source" j receipt])
                    val bodyNodes = body.map(::term)
                    val bodyNode = if (bodyNodes.size == 1) bodyNodes.single() else {
                        val and = "%n${node++}"
                        theory.append("    $and = kif.atom \"and\"\n")
                        val list = "%n${node++}"
                        theory.append("    $list = kif.list(${(_l[and] + bodyNodes).joinToString(", ")})\n")
                        list
                    }
                    val consequent = term(conclusion)
                    theory.append("    kif.rule $bodyNode implies $consequent\n")
                } else {
                    val fact = atom(formula, variables = false)
                    facts.add(fact)
                    factSources.add(_m["fact" j fact, "source" j receipt])
                    if (fact.size == 3 && fact[0] in _s["subclass", "instance"])
                        theory.append("    sumo.${fact[0]} ${mlirString(fact[1])} ${mlirString(fact[2])}\n")
                    else theory.append("    sumo.holds ${mlirString(fact[0])} [${fact.drop(1).joinToString(", ", transform = ::mlirString)}]\n")
                }
            }
            if (admitted.isFailure) excluded.add(_m["source" j receipt,
                "reason" j (admitted.exceptionOrNull()?.message ?: "Outside positive scalar Horn fragment")])
        }
        theory.append("  }\n}\n")
        val data = linkedMapOf<String, Any?>("schema" to SCHEMA, "sumoNixRevision" to SUMO_REVISION,
            "mlirNixRevision" to MLIR_REVISION, "scope" to scope, "revision" to revision,
            "ontology" to null, "facts" to facts, "rules" to rules, "query" to goal,
            "provenance" to _m["facts" j factSources, "rules" j ruleSources], "excluded" to excluded,
            "semantics" to "positive-scalar-horn-first-proof/1", "executionStatus" to "NOT_EXECUTED")
        val frame = ContentId.of(CanonicalCbor.encodeMap(data))
        data["frameId"] = frame.value
        val json = jsonOf(data)
        val bytes = json.encodeToByteArray(throwOnInvalidSequence = true)
        require(bytes.size <= 4 * 1024 * 1024 && theory.length <= 4 * 1024 * 1024) { "Horn export byte limit" }
        return SumoNixExport(json, theory.toString(), ContentId.of(bytes), frame, scope)
    }

    private fun atom(formula: KifFormula, variables: Boolean): List<String> {
        val p = formula as? KifFormula.Predicate ?: error("Only positive relational Horn atoms execute")
        require(p.name != "equal" && p.arguments.size <= 16) { "Equality or oversized atom is outside this fragment" }
        return _l[p.name] + p.arguments.map { term -> when (term) {
            is KifTerm.Constant -> term.token
            is KifTerm.Literal -> term.spelling
            is KifTerm.Free -> { require(variables) { "Facts and queries must be ground" }; term.name }
            else -> error("Only scalar Horn terms execute")
        } }
    }

    /** MLIR string literals use UTF-8 byte escapes, including control and non-ASCII bytes. */
    private fun mlirString(text: String): String = buildString {
        append('"')
        for (byte in text.encodeToByteArray(throwOnInvalidSequence = true)) {
            val value = byte.toInt() and 255
            if (value in 32..126 && value != 34 && value != 92) append(value.toChar())
            else append('\\').append(value.toString(16).padStart(2, '0'))
        }
        append('"')
    }
}
