package borg.trikeshed.forge.server

import borg.trikeshed.forge.sheet.SheetColumn
import borg.trikeshed.forge.sheet.SheetRef
import borg.trikeshed.forge.sheet.SheetSeed

/** Existing JVM projections as bounded SheetSeed families, without reparsing their JSON. */
fun projectionSheets(id: String, projection: Map<String, Any?>): List<SheetSeed> {
    val sheets = ArrayList<SheetSeed>()
    var remainingRows = 1024
    var remainingChars = 65536
    var allocated = 1
    fun walk(value: Any?, key: String, title: String, parent: String?, depth: Int) {
        val rows = ArrayList<List<Any?>>()
        val pending = ArrayList<Triple<Any?, String, String>>()
        var limit: String? = null
        fun cell(value: Any?, name: String, prefix: String = ""): Any? = when (value) {
            is Map<*, *>, is List<*> -> {
                if (allocated >= 256 || depth >= 16) {
                    limit = if (depth >= 16) "depth_limit" else "sheet_limit"
                    "[projection limit]"
                } else {
                    allocated++
                    val child = "$key/$prefix${name.replace("~", "~0").replace("/", "~1")}"
                    pending.add(Triple(value, child, name))
                    SheetRef(child)
                }
            }
            is String -> value.take(remainingChars.coerceAtLeast(0)).also {
                remainingChars -= it.length
                if (it.length < value.length) limit = "payload_limit"
            }
            else -> value
        }
        fun admit(): Boolean {
            if (remainingRows <= 0 || remainingChars <= 0) {
                limit = if (remainingRows <= 0) "row_limit" else "payload_limit"
                return false
            }
            remainingRows--
            return true
        }
        val objectRows = value is Map<*, *>
        var columns = listOf(
            SheetColumn(if (objectRows) "key" else "index", if (objectRows) "IoString" else "IoInt"),
            SheetColumn("value", "Any"),
        )
        if (value is Map<*, *>) {
            // Coordinates and pseudo-source are large; preserve the structural facets first.
            val entries = value.entries.sortedBy { if (it.key == "pointcuts" || it.key == "pseudoSource") 1 else 0 }
            for ((name, item) in entries) {
                if (!admit()) break
                rows.add(listOf(cell(name.toString(), "key"), cell(item, name.toString())))
            }
        } else if (value is List<*>) {
            if (value.isNotEmpty() && value.all { it is Map<*, *> }) {
                val names = value.take(remainingRows.coerceAtLeast(0)).flatMap { (it as Map<*, *>).keys }
                    .map { it.toString() }.distinct()
                columns = names.map { SheetColumn(it, "Any") }
                for ((index, item) in value.withIndex()) {
                    if (!admit()) break
                    val record = item as Map<*, *>
                    rows.add(names.map { name -> cell(record[name], name, "$index/") })
                }
            } else for ((index, item) in value.withIndex()) {
                    if (!admit()) break
                    rows.add(listOf(index, cell(item, index.toString())))
            }
        }
        sheets.add(SheetSeed(key, title, columns, rows, parent, limit != null, limit))
        for ((item, child, name) in pending) walk(item, child, name, key, depth + 1)
    }
    walk(projection, id, id, null, 0)
    return sheets
}
