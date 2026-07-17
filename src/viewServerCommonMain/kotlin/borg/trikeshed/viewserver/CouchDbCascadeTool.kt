package borg.trikeshed.viewserver

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class CouchDbCascadeTool(val config: String) {
    fun reduce(keysAndValues: JsonArray): String {
        var count = 0L
        var sum = 0.0
        var min = Double.MAX_VALUE
        var max = Double.MIN_VALUE

        for (item in keysAndValues) {
            val entry = item.jsonArray
            val value = entry[1].jsonPrimitive.content.toDoubleOrNull() ?: 0.0

            count++
            sum += value
            if (value < min) min = value
            if (value > max) max = value
        }

        if (count == 0L) {
            return "{\"count\":0,\"sum\":0.0,\"min\":0.0,\"max\":0.0,\"sumsqr\":0}"
        }

        return "{\"count\":$count,\"sum\":$sum,\"min\":$min,\"max\":$max,\"sumsqr\":0}"
    }
}
