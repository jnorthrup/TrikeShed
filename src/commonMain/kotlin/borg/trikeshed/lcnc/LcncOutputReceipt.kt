package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.parse.ValueBudget
import borg.trikeshed.parse.jsonOf

/** Exact execution output survives independently of the bounded HTTP/board projection. */
object LcncOutputReceipt {
    fun retain(cas: CasStore, output: Map<String, Any?>): Map<String, Any?> {
        if (ValueBudget().violation(output) == null) return output
        fun reference(value: Any?): Map<String, Any?> {
            val bytes = jsonOf(value).encodeToByteArray()
            val cid = cas.put(bytes)
            return mapOf("retainedCid" to cid.value, "contentType" to "application/json",
                "bytes" to bytes.size, "preview" to bytes.decodeToString().take(256), "previewOnly" to true)
        }
        val retained = reference(output)
        val window = ValueBudget(maxNodes = 256, maxChars = 2048)
        val projection = output.mapValues { (key, value) ->
            if (key in setOf("outputs", "returns") && value is Map<*, *>)
                value.entries.associate { (name, item) -> name.toString() to
                    if (window.violation(item) == null) item else reference(item) }
            else if (window.violation(value) == null) value else reference(value)
        }
        val fields = if (ValueBudget(maxChars = 100_000).violation(projection) == null) projection
            else output.mapValues { (_, value) -> reference(value) }
        cas.sync()
        return fields + mapOf("outputCid" to retained["retainedCid"], "outputPreview" to true)
    }
}
