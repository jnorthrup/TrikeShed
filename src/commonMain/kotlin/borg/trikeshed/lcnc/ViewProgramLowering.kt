package borg.trikeshed.lcnc

import borg.trikeshed.couch.KeyExpr
import borg.trikeshed.couch.MapFunction
import borg.trikeshed.couch.ReduceFunction
import borg.trikeshed.couch.ValueExpr
import borg.trikeshed.couch.ViewDefinition
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

/**
 * P2: lower an LCNC ring document into the EXISTING ViewServer expression tree.
 * There is no second evaluator and no JS: `view.emit` + optional `view.reduce`
 * become [ViewDefinition]/[KeyExpr]/[ValueExpr] values consumed by ViewServer.
 */
object ViewProgramLowering {
    fun lower(program: LcncProgram): ViewDefinition {
        var emit: LcncNode? = null
        var reduce: LcncNode? = null
        for (i in 0 until program.nodes.size) {
            val node = program.nodes[i]
            when (node.type) {
                LcncContracts.VIEW_EMIT -> {
                    require(emit == null) { "view program has more than one view.emit" }
                    emit = node
                }
                LcncContracts.VIEW_REDUCE -> {
                    require(reduce == null) { "view program has more than one view.reduce" }
                    reduce = node
                }
            }
        }
        val e = requireNotNull(emit) { "view program requires exactly one view.emit" }
        val ddoc = e.params["ddoc"]?.takeIf { it.isNotBlank() } ?: "_design/lcnc"
        val viewName = e.params["view"]?.takeIf { it.isNotBlank() } ?: program.name
        val arrayField = e.params["arrayField"]?.takeIf { it.isNotBlank() }
        val map = if (arrayField != null) {
            MapFunction.EmitEach(
                arrayField = arrayField.removePrefix("doc."),
                keyExpr = keyExpr(e.params["key"] ?: "doc.$arrayField"),
                valueExpr = valueExpr(e.params["value"] ?: "const:1"),
            )
        } else {
            MapFunction.Emit(
                key = keyExpr(e.params["key"] ?: "_id"),
                value = valueExpr(e.params["value"] ?: "const:1"),
            )
        }
        val reducer = reduce?.params?.get("reducer")?.takeIf { it.isNotBlank() }?.let { name ->
            require(name in setOf("_count", "_sum", "_stats", "rollup-count")) {
                "unsupported reducer '$name' — views are bounded built-ins, never JS"
            }
            ReduceFunction.Builtin(name)
        }
        return ViewDefinition(ddoc, viewName, map, reducer)
    }

    private fun keyExpr(spelling: String): KeyExpr = when {
        spelling == "_id" || spelling == "doc._id" -> KeyExpr.DocId
        spelling.startsWith("const:") -> KeyExpr.Const(literal(spelling.removePrefix("const:")))
        spelling.startsWith("doc.") -> KeyExpr.DocField(spelling.removePrefix("doc."))
        else -> KeyExpr.DocField(spelling)
    }

    private fun valueExpr(spelling: String): ValueExpr = when {
        spelling == "_doc" || spelling == "doc" -> ValueExpr.DocValue
        spelling.startsWith("const:") -> ValueExpr.Const(literal(spelling.removePrefix("const:")))
        spelling.startsWith("doc.") -> ValueExpr.DocField(spelling.removePrefix("doc."))
        else -> ValueExpr.DocField(spelling)
    }

    private fun literal(raw: String): Any? = when (raw) {
        "null" -> null
        "true" -> true
        "false" -> false
        else -> raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: raw
    }
}
