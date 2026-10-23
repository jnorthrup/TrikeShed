package borg.trikeshed.couch

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.query.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.jsonOf

/**
 * SQL tables kept as documents in one Couch database; no new store. Table `t` is the documents
 * under `<root>t/`: a row's `id` is the rest of its document id, its other columns are the document's
 * fields. `<root>t` itself (no slash) declares the table's columns (`CREATE TABLE`), so an empty
 * table still has a schema. Every write is a Couch put or delete against the head revision, so
 * rows replicate, version and feed `_changes` like any other document.
 */
class CouchTables(val couch: Couch, val root: String = "tables/") : TableDdl {
    /** Every table: each declared one and each `<root>t/` folder holding rows. */
    fun tables(): List<CatalogTable> {
        val names = HashSet<String>()
        for (id in liveIds(root)) {
            val rest = id.removePrefix(root)
            names += if ('/' in rest) rest.substringBefore('/') else rest
        }
        return names.filter { it.isNotEmpty() }.sorted().map(::table)
    }

    fun table(name: String): CatalogTable = Table(name)

    /** `CREATE TABLE`: declares [columns] (name to pg type, in order); false when the table exists. */
    override fun create(name: String, columns: List<Pair<String, String>>): Boolean {
        if (couch.docJson(root + name) != null || liveIds("$root$name/").isNotEmpty()) return false
        val body = linkedMapOf<String, Any?>("columns" to columns.map { (column, type) -> mapOf("name" to column, "type" to type) })
        return couch.put(root + name, body, null)["ok"] == true
    }

    /** `DROP TABLE`: deletes the declaration and every row; false when there was no table. */
    override fun drop(name: String): Boolean {
        val ids = liveIds("$root$name/") + listOfNotNull((root + name).takeIf { couch.docJson(it) != null })
        for (id in ids) couch.delete(id, couch.store.head.getRev(id))
        return ids.isNotEmpty()
    }

    private fun liveIds(prefix: String): List<String> = ids(prefix, live = true)

    /** Document ids under [prefix], sorted; with deleted ones too unless [live]. */
    private fun ids(prefix: String, live: Boolean): List<String> {
        val ids = couch.store.ids()
        val out = ArrayList<String>()
        for (i in 0 until ids.a) {
            val id = ids.b(i)
            if (id.startsWith(prefix) && !(live && couch.store.head.isDeleted(id))) out += id
        }
        return out.sorted()
    }

    private inner class Table(override val name: String) : CatalogTable, TableWrites {
        val prefix = "$root$name/"

        /** Declared columns (name to pg type), in order. */
        fun declared(): List<Pair<String, String>> =
            (couch.docJson(root + name)?.get("columns") as? List<*>).orEmpty().mapNotNull { c ->
                val column = c as? Map<*, *> ?: return@mapNotNull null
                (column["name"] as? String ?: return@mapNotNull null) to (column["type"] as? String ?: "text")
            }

        override fun scan(): ColumnCursor {
            val rows = liveIds(prefix).mapNotNull { id -> couch.docJson(id)?.let { row(id, it) } }
            val declared = declared()
            val names = LinkedHashSet<String>().apply {
                add("id")
                declared.forEach { add(it.first) }
                rows.forEach { addAll(it.keys) }
            }
            return rowsCursor(names.toList(), rows, declared.toMap())
        }

        override val writes: TableWrites get() = this

        /** A document as a row: its fields, and `id` from the document id unless a field carries it. */
        private fun row(docId: String, body: Map<String, Any?>): Map<String, Any?> {
            val row = linkedMapOf<String, Any?>("id" to docId.removePrefix(prefix))
            for ((k, v) in body) if (!k.startsWith("_")) row[k] = v
            return row
        }

        private fun key(id: Any?): String = when (id) {
            is Double -> if (id == kotlin.math.floor(id) && kotlin.math.abs(id) < 9.007199254740992E15) id.toLong().toString() else id.toString()
            else -> id.toString()
        }

        /**
         * The next integer id when every id so far is one (an identity column, which never reuses a
         * deleted id), else a content id.
         */
        private fun mint(row: Map<String, Any?>): Any {
            val ids = ids(prefix, live = false).map { it.removePrefix(prefix) }
            val numbers = ids.map { it.toLongOrNull() }
            return if (numbers.all { it != null }) (numbers.maxOfOrNull { it!! } ?: 0L) + 1
            else ContentId.of(jsonOf(row).encodeToByteArray()).hex
        }

        override fun insert(row: Map<String, Any?>, resolution: Resolution): Map<String, Any?>? {
            val id = row["id"] ?: mint(row)
            val docId = prefix + key(id)
            val stored = linkedMapOf<String, Any?>().apply { putAll(row); put("id", id) }
            val existing = couch.docJson(docId)
            if (existing != null) when (resolution) {
                Resolution.ERROR -> throw DuplicateKey(name, key(id))
                Resolution.IGNORE -> return null
                Resolution.MERGE -> return put(docId, row(docId, existing) + stored, couch.store.head.getRev(docId))
            }
            return put(docId, stored, null)
        }

        override fun update(id: Any, patch: Map<String, Any?>): Map<String, Any?>? {
            val docId = prefix + key(id)
            val existing = couch.docJson(docId) ?: return null
            return put(docId, row(docId, existing) + patch, couch.store.head.getRev(docId))
        }

        override fun exists(id: Any): Boolean = couch.docJson(prefix + key(id)) != null

        override fun delete(id: Any): Boolean {
            val docId = prefix + key(id)
            return couch.delete(docId, couch.store.head.getRev(docId))["ok"] == true
        }

        private fun put(docId: String, body: Map<String, Any?>, rev: String?): Map<String, Any?> {
            val reply = couch.put(docId, body, rev)
            check(reply["ok"] == true) { "could not serialize access due to concurrent update of ${reply["id"]}" }
            return row(docId, couch.docJson(docId)!!)
        }
    }
}
