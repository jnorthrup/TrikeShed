package borg.trikeshed.lcnc

import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.relaxfactory.RelaxOp
import borg.trikeshed.relaxfactory.RequestFactoryProxy

/**
 * LCNC connectors for the RequestFactory proxy.
 *
 * `rf.rpc` is the typed lane: text target plus JSON args, one receipt. `rf.batch`
 * is the full envelope lane for callers that already have operation objects.
 */
object RequestFactoryNodes {

    fun registry(proxy: RequestFactoryProxy): Map<String, LcncNodeRunner> = mapOf(
        "rf.rpc" to LcncNodeRunner { node, inputs ->
            val target = ((inputs["target"] ?: inputs["target?"])?.toString()
                ?: node.params["target"]).orEmpty()
            if (target.isBlank()) {
                val receipt = mapOf("ok" to false, "error" to "rpc", "reason" to "target required")
                mapOf("result" to null, "receipt" to receipt)
            } else {
                val receipt = proxy.rpc(target, jsonObject(inputs["args"] ?: inputs["args?"] ?: node.params["args"]))
                mapOf("result" to receipt.result, "receipt" to receipt.fields)
            }
        },
        "rf.batch" to LcncNodeRunner { node, inputs ->
            val raw = inputs["operations"] ?: inputs["operations?"] ?: node.params["operations"]
            val batch = proxy.submit(operationMaps(raw).map { RelaxOp.Raw(it) })
            mapOf(
                "ok" to batch.ok,
                "receipts" to batch.receipts.map { it.fields },
            )
        },
    )

    fun servedTypes(): Set<String> = setOf("rf.rpc", "rf.batch")

    private fun jsonObject(raw: Any?): Map<String, Any?> = when (raw) {
        null -> emptyMap()
        is Map<*, *> -> raw.entries.associate { (k, v) -> k.toString() to v }
        is String -> if (raw.isBlank()) emptyMap() else JsonSupport.parseMap(raw)
        else -> emptyMap()
    }

    private fun operationMaps(raw: Any?): List<Map<String, Any?>> = when (raw) {
        null -> emptyList()
        is Map<*, *> -> listOf(raw.entries.associate { (k, v) -> k.toString() to v })
        is List<*> -> raw.mapNotNull(::operationMap)
        is String -> if (raw.isBlank()) emptyList() else when (val parsed = JsonSupport.parse(raw)) {
            is Map<*, *> -> listOf(parsed.entries.associate { (k, v) -> k.toString() to v })
            is List<*> -> parsed.mapNotNull(::operationMap)
            else -> emptyList()
        }
        else -> emptyList()
    }

    private fun operationMap(raw: Any?): Map<String, Any?>? =
        (raw as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }
}
