package borg.trikeshed.couch.api

import borg.trikeshed.miniduck.BlockRowVec
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.MiniRowVec
import borg.trikeshed.miniduck.ViewRowVec
import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.json.JsonParser

/**
 * CouchDB 1.1 view query result row set.
 */
data class CouchDb11RowSet(
    val totalRows: Int,
    val offset: Int,
    val rows: BlockRowVec,
) {
    companion object {
        fun fromJson(json: String): CouchDb11RowSet {
            val root = JsonParser.reify(json.toSeries()) as? Map<String, Any?>
                ?: error("CouchDB row set must be a JSON object")

            val totalRows = root.int("total_rows")
            val offset = root.int("offset")
            val block = BlockRowVec.mutable()

            root.list("rows").forEach { rawRow ->
                val row = rawRow as? Map<String, Any?>
                    ?: error("CouchDB row entry must be a JSON object")
                val docMap = row.mapValueOrNull("doc")
                val docLoader: (() -> MiniRowVec)? = docMap?.let { map -> { parseDocRowVec(map) } }

                block.append(
                    ViewRowVec(
                        id = row.string("id"),
                        key = row["key"],
                        value = row["value"],
                        docLoader = docLoader,
                    ),
                )
            }

            return CouchDb11RowSet(
                totalRows = totalRows,
                offset = offset,
                rows = block.seal(),
            )
        }

       fun parseDocRowVec(map: Map<String, Any?>): DocRowVec {
            val keys = map.keys.toList()
            val cells = keys.map { key -> map[key] }
            return DocRowVec(keys = keys, cells = cells)
        }

       fun Map<String, Any?>.string(name: String): String =
            this[name] as? String ?: error("Missing string field '$name'")

       fun Map<String, Any?>.int(name: String): Int = when (val value = this[name]) {
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            else -> error("Missing numeric field '$name'")
        }

       fun Map<String, Any?>.list(name: String): List<Any?> =
            this[name] as? List<Any?> ?: emptyList()

       fun Map<String, Any?>.mapValueOrNull(name: String): Map<String, Any?>? =
            this[name] as? Map<String, Any?>
    }
}
