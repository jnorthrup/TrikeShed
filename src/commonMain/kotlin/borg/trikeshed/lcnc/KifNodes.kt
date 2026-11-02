package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.*

/** Read-only syntax surface. It neither asserts facts nor grants source/review authority. */
object KifNodes {
    const val INSPECT = "kif.inspect"

    val runner: LcncPureRunner = LcncPureRunner { node, inputs ->
        val supplied = when {
            "text?" in inputs -> inputs["text?"]
            "text" in inputs -> inputs["text"]
            else -> node.params["kif"]
        }
        require(supplied is String) { "kif.inspect requires KIF text" }
        val decoded = KifSemantic.decode(supplied)
        mapOf(
            "formula" to formula(decoded.formula),
            "text" to supplied,
            "sourceCid" to ContentId.of(supplied.encodeToByteArray()).value,
            "canonical" to decoded.syntax.toKifString(),
            "freeVariables" to decoded.freeVariables.toList(),
            "unsupportedForms" to decoded.unsupportedForms.map { it.toKifString() },
            "status" to "syntax-only-unreviewed",
        )
    }

    private fun term(value: KifTerm): Map<String, Any?> = when (value) {
        is KifTerm.Constant -> mapOf("kind" to "constant", "token" to value.token)
        is KifTerm.Literal -> mapOf("kind" to "literal", "spelling" to value.spelling)
        is KifTerm.Free -> mapOf("kind" to "free", "name" to value.name)
        is KifTerm.Bound -> mapOf("kind" to "bound", "binder" to value.binder)
        is KifTerm.Function -> mapOf("kind" to "function", "name" to value.name,
            "arguments" to value.arguments.map(::term))
        is KifTerm.Quote -> mapOf("kind" to "quote", "source" to value.syntax.toKifString())
        is KifTerm.Unsupported -> mapOf("kind" to "unsupported-term", "source" to value.syntax.toKifString())
    }

    private fun formula(value: KifFormula): Map<String, Any?> = when (value) {
        is KifFormula.Predicate -> mapOf("kind" to "predicate", "name" to value.name,
            "arguments" to value.arguments.map(::term))
        is KifFormula.Not -> mapOf("kind" to "not", "body" to formula(value.body))
        is KifFormula.And -> mapOf("kind" to "and", "bodies" to value.bodies.map(::formula))
        is KifFormula.Or -> mapOf("kind" to "or", "bodies" to value.bodies.map(::formula))
        is KifFormula.Implies -> mapOf("kind" to "implies", "antecedent" to formula(value.antecedent),
            "consequent" to formula(value.consequent))
        is KifFormula.Equivalent -> mapOf("kind" to "equivalent", "left" to formula(value.left),
            "right" to formula(value.right))
        is KifFormula.ForAll -> mapOf("kind" to "forall", "binders" to value.binders, "body" to formula(value.body))
        is KifFormula.Exists -> mapOf("kind" to "exists", "binders" to value.binders, "body" to formula(value.body))
        is KifFormula.Unsupported -> mapOf("kind" to "unsupported-formula", "source" to value.syntax.toKifString())
    }
}
