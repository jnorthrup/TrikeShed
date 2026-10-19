package borg.trikeshed.lcnc

object KgIngestKey : LcncServiceKey<suspend (String) -> Map<String, Any?>>("KgIngestKey")

/** Turtle/KIF assertions enter the daemon's existing belief intake. */
object KgNodes {
    fun registry(ingest: suspend (String) -> Map<String, Any?>): Map<String, LcncNodeRunner> = mapOf(
        "kg.ingest" to boundLcnc(KgIngestKey(ingest)) { service, node, inputs ->
            val text = (inputs["text"] as? String) ?: (inputs["text?"] as? String) ?: node.params["kg"].orEmpty()
            require(text.isNotBlank()) { "kg.ingest: empty body" }
            mapOf("report" to service.value(text))
        },
    )
}
