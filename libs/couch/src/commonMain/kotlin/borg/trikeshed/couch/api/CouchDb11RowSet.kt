package borg.trikeshed.couch.api

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.miniduck.*
import borg.trikeshed.lib.j
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
                val docLoader: (() -> RowVec)? = docMap?.let { map -> { parseDocRowVec(map) } }

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

        fun parseDocRowVec(map: Map<String, Any?>): borg.trikeshed.cursor.RowVec {
            val entries = map.entries.toList()
            val keys = entries.size j { index: Int -> entries[index].key }
            val cells = entries.size j { index: Int -> entries[index].value }
            return KeyedRowVec(keys, cells).toRowVec()
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
