package borg.trikeshed.lcnc

/**
 * THE STALE MARKER (Forge genesis, Cut S): `lcnc/stale/<runId>` says which consumed inputs of a
 * completed run have moved since the run read them. It is merged, never replaced: one production
 * firing per (input, new cid) folds into one marker per run, so a second changed file raises the
 * count and adds a row while the key stays one. Pure: the module's production sink calls [merge]
 * with the firing's bindings and puts the result on the board.
 */
object LcncStaleMarker {
    const val PREFIX = "lcnc/stale/"
    const val LANGUAGE = "lcnc-stale"

    fun key(runId: String): String = PREFIX + runId

    data class Input(val kind: String, val project: String, val id: String, val oldCid: String, val newCid: String, val sequence: Long?, val deleted: Boolean) {
        fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>("kind" to kind, "project" to project, "id" to id, "oldCid" to oldCid, "newCid" to newCid, "deleted" to deleted).also { m -> sequence?.let { m["sequence"] = it } }
        companion object {
            fun fromBindings(b: Map<String, Any?>): Input? = Input(
                kind = b["kind"]?.toString() ?: "project",
                project = b["project"]?.toString() ?: return null,
                id = b["id"]?.toString() ?: return null,
                oldCid = b["oldCid"]?.toString().orEmpty(),
                newCid = b["newCid"]?.toString().orEmpty(),
                sequence = b["sequence"]?.toString()?.toLongOrNull(),
                deleted = b["deleted"]?.toString() == "true",
            )
            fun fromMap(m: Map<*, *>): Input? = Input(
                kind = m["kind"]?.toString() ?: "project",
                project = m["project"]?.toString() ?: return null,
                id = m["id"]?.toString() ?: return null,
                oldCid = m["oldCid"]?.toString().orEmpty(),
                newCid = m["newCid"]?.toString().orEmpty(),
                sequence = (m["sequence"] as? Number)?.toLong() ?: m["sequence"]?.toString()?.toLongOrNull(),
                deleted = m["deleted"] == true || m["deleted"]?.toString() == "true",
            )
        }
    }

    /**
     * The marker after one more firing. Rows are keyed on (id, newCid); a later firing for the same
     * document with a newer cid replaces the row for that document (the older change is history the
     * receipt lineage keeps), so `count` is the number of inputs that moved, not of firings.
     */
    fun merge(existing: Any?, bindings: Map<String, Any?>, atMs: Long): Map<String, Any?>? {
        val input = Input.fromBindings(bindings) ?: return null
        val runId = bindings["runId"]?.toString() ?: return null
        val prior = (existing as? Map<*, *>)
        val rows = LinkedHashMap<String, Input>()
        ((prior?.get("inputs") as? List<*>) ?: emptyList<Any?>()).forEach { row -> (row as? Map<*, *>)?.let { Input.fromMap(it) }?.let { rows[it.project + "/" + it.id] = it } }
        rows[input.project + "/" + input.id] = input
        return linkedMapOf(
            "runId" to runId,
            "receiptKey" to (bindings["receiptKey"] ?: prior?.get("receiptKey")),
            "receiptCid" to (bindings["receiptCid"] ?: prior?.get("receiptCid")),
            "programKey" to (bindings["programKey"] ?: prior?.get("programKey")),
            "programCid" to (bindings["programCid"] ?: prior?.get("programCid")),
            "inputs" to rows.values.map { it.toMap() },
            "count" to rows.size,
            "atMs" to atMs,
        )
    }
}
