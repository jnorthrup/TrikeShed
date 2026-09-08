package borg.trikeshed.lcnc

import borg.trikeshed.collections.mutableSeriesOf
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import borg.trikeshed.couch.ViewResult
import borg.trikeshed.couch.ViewRow
import borg.trikeshed.couch.ViewServer
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.view

/** The palette uses the same expression lowering and reducers as persisted Couch views. */
object ViewNodes {
    fun registry(): Map<String, LcncNodeRunner> = mapOf(
        LcncContracts.VIEW_EMIT to LcncNodeRunner { node, inputs ->
            val raw = inputs["documents"] ?: inputs["documents?"]
            val documents = when (raw) {
                null -> emptyList()
                is List<*> -> raw
                is Array<*> -> raw.asList()
                else -> error("view.emit documents must be a list")
            }
            val definition = ViewProgramLowering.lower(LcncProgram(node.id, s_[node], emptySeriesOf()))
            val mapped = ViewServer().execute(definition, documents.map { value ->
                when (value) {
                    is Document -> value
                    is Map<*, *> -> {
                        require(value.keys.all { it is String }) { "view.emit document keys must be text" }
                        val id = value["_id"] as? String ?: error("view.emit document requires a text _id")
                        Document(id, value.entries.mapNotNull { (key, item) ->
                            if (key == "_id" || item == null) null else Field(key as String, item)
                        })
                    }
                    else -> error("view.emit documents must contain objects")
                }
            })
            mapOf("rows" to mapped.rows.view.map(::rowMap))
        },
        LcncContracts.VIEW_REDUCE to LcncNodeRunner { node, inputs ->
            val raw = inputs["rows"] as? List<*> ?: error("view.reduce rows must be a list")
            val rows = mutableSeriesOf<ViewRow>()
            for (value in raw) {
                val row = value as? Map<*, *> ?: error("view.reduce rows must contain objects")
                require(row.containsKey("key") && row.containsKey("value")) { "view.reduce row requires key and value" }
                rows.append(ViewRow(row["key"], row["value"], row["docId"] as? String ?: ""))
            }
            val reducer = node.params["reducer"] ?: "_count"
            val result = ViewResult(rows).reduce(reducer)
            mapOf("reduced" to result.rows.view.map(::rowMap))
        },
    )

    private fun rowMap(row: ViewRow): Map<String, Any?> = linkedMapOf(
        "key" to row.key,
        "value" to when (val value = row.value) {
            is Document -> value.fields.associate { it.name to it.value } + ("_id" to value.id)
            else -> value
        },
        "docId" to row.docId,
    )
}
