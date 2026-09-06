package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.json.JsonSupport

/**
 * THE WORKSPACE SNAPSHOT (Forge genesis, Cut C; the post's bullet 2, "snapshots or VCS
 * integration"): one content-addressed document that names, at one instant, every
 * published program's version, every stored prompt's version, every mounted project's
 * store sequence, and the latest run receipt per program. It is composed from the
 * blackboard and the stores, never from browser state; its cid is the identity of the
 * workspace at that moment, and `previousCid` chains snapshots into a lineage.
 *
 * Pure: composition and identity live here; storing and serving are the jvmMain
 * service's business.
 */
data class WorkspaceSnapshot(
    val atMs: Long,
    val note: String,
    val previousCid: String?,
    /** program name -> {programCid, sourceCid} */
    val programs: Map<String, Map<String, String>>,
    /** prompt name -> cid */
    val prompts: Map<String, String>,
    /** mounted project databases: {name, kind, path, updateSeq, docs} */
    val projectDbs: List<Map<String, Any?>>,
    /** program name -> {receiptCid, runId, status, sequence} of the latest run */
    val receipts: Map<String, Map<String, Any?>>,
) {
    /**
     * The canonical form: sorted keys, and every integral number written as an integer, so a
     * document parsed back (the parser reifies numbers as doubles) re-mints the same cid.
     */
    fun canonicalJson(): String = JsonSupport.stringify(
        normalize(
            linkedMapOf<String, Any?>(
                "kind" to KIND,
                "atMs" to atMs,
                "note" to note,
                "previousCid" to previousCid,
                "programs" to programs.sorted().mapValues { (_, v) -> v.sorted() },
                "prompts" to prompts.sorted(),
                "projectDbs" to projectDbs.sortedBy { it["name"]?.toString().orEmpty() }.map { it.sorted() },
                "receipts" to receipts.sorted().mapValues { (_, v) -> v.sorted() },
            ),
        ),
    )

    val bytes: ByteArray get() = canonicalJson().encodeToByteArray()
    val cid: String get() = ContentId.of(bytes).value

    /** Key order without java.util.TreeMap: the same on every target. */
    private fun <V> Map<String, V>.sorted(): Map<String, V> = entries.sortedBy { it.key }.associate { it.key to it.value }

    private fun normalize(v: Any?): Any? = when (v) {
        is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to normalize(x) }
        is List<*> -> v.map { normalize(it) }
        is Double -> if (v == kotlin.math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 9.0e15) v.toLong() else v
        is Float -> normalize(v.toDouble())
        is Int -> v.toLong()
        else -> v
    }

    fun counts(): Map<String, Int> = mapOf("programs" to programs.size, "prompts" to prompts.size, "projectDbs" to projectDbs.size, "receipts" to receipts.size)

    companion object {
        const val KIND = "lcnc.snapshot/1"
        const val HEAD_KEY = "lcnc/snapshot/head"
        const val NOTE_MAX = 512

        /**
         * Compose from the blackboard's program and run entries (the `lcnc/program/` and `lcnc/run/`
         * key families) plus the two store listings. Only completed runs count as a program's latest receipt, newest by
         * sequence; a program with no completed run has no receipt row.
         */
        fun compose(
            atMs: Long,
            note: String,
            previousCid: String?,
            entries: Map<String, Any?>,
            prompts: Map<String, String>,
            projectDbs: List<Map<String, Any?>>,
        ): WorkspaceSnapshot {
            val programs = LinkedHashMap<String, Map<String, String>>()
            val receipts = LinkedHashMap<String, Map<String, Any?>>()
            for ((key, value) in entries) {
                val m = value as? Map<*, *> ?: continue
                if (key.startsWith(LcncBlackboard.PROGRAM_PREFIX)) {
                    val name = key.removePrefix(LcncBlackboard.PROGRAM_PREFIX)
                    val programCid = m["programCid"]?.toString() ?: continue
                    programs[name] = linkedMapOf("programCid" to programCid, "sourceCid" to m["sourceCid"]?.toString().orEmpty())
                } else if (key.startsWith("lcnc/run/")) {
                    if (m["status"]?.toString() != "completed") continue
                    val program = m["program"]?.toString() ?: continue
                    val receiptCid = m["receiptCid"]?.toString() ?: continue
                    val sequence = (m["sequence"] as? Number)?.toLong() ?: -1L
                    val prior = receipts[program]
                    if (prior == null || ((prior["sequence"] as? Number)?.toLong() ?: -1L) < sequence) {
                        receipts[program] = linkedMapOf("receiptCid" to receiptCid, "runId" to m["runId"]?.toString().orEmpty(), "status" to "completed", "sequence" to sequence)
                    }
                }
            }
            return WorkspaceSnapshot(atMs, note.take(NOTE_MAX), previousCid, programs, prompts, projectDbs, receipts)
        }

        fun fromJson(text: String): WorkspaceSnapshot? {
            val m = JsonSupport.parse(text) as? Map<*, *> ?: return null
            if (m["kind"] != KIND) return null
            @Suppress("UNCHECKED_CAST")
            fun strMap(v: Any?): Map<String, String> = (v as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            fun anyMap(v: Any?): Map<String, Any?> = (v as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()
            return WorkspaceSnapshot(
                atMs = (m["atMs"] as? Number)?.toLong() ?: 0L,
                note = m["note"]?.toString().orEmpty(),
                previousCid = m["previousCid"]?.toString(),
                programs = (m["programs"] as? Map<*, *>)?.entries?.associate { it.key.toString() to strMap(it.value) } ?: emptyMap(),
                prompts = strMap(m["prompts"]),
                projectDbs = (m["projectDbs"] as? List<*>)?.map { anyMap(it) } ?: emptyList(),
                receipts = (m["receipts"] as? Map<*, *>)?.entries?.associate { it.key.toString() to anyMap(it.value) } ?: emptyMap(),
            )
        }
    }
}
