package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * WHAT A RUN READ: the consumed-input ledger a run carries through its walk.
 *
 * The post Forge answers wants iterated compute over a file set with the
 * artifacts worth saving; a rebuild needs to know what a run consumed. Every
 * lego that reads a citizen (a stored prompt, a project document, the project
 * listing itself) records the identity it read here, and the run's receipt
 * carries the entries plus one fingerprint over their cids. Installed by the
 * executor at root ring entry as a CoroutineContext element, so a runner deep
 * inside a ring records into the same ledger (rings are blocks).
 */
class LcncConsumedLedger(val limit: Int = 1024) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LcncConsumedLedger> {
        const val PROMPT = "prompt"
        const val PROJECT = "project"
        const val PROJECT_INDEX = "project-index"

        /** The listing identity a production recomputes: over the sorted, distinct file ids. */
        fun indexFingerprintOf(ids: Collection<String>): String =
            ContentId.of(ids.distinct().sorted().joinToString("\n").encodeToByteArray()).value
    }

    data class Consumed(
        val kind: String,
        val id: String,
        val cid: String,
        val sequence: Long? = null,
        val rev: String? = null,
        val prefix: String? = null,
        val glob: String? = null,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>("kind" to kind, "id" to id, "cid" to cid).also { m ->
            sequence?.let { m["sequence"] = it }
            rev?.let { m["rev"] = it }
            prefix?.let { m["prefix"] = it }
            glob?.let { m["glob"] = it }
        }
    }

    private val entries = LinkedHashMap<String, Consumed>()

    /** True once an entry was dropped at [limit]; the receipt says so rather than lying by omission. */
    var truncated: Boolean = false
        private set

    fun consumed(entry: Consumed) {
        val key = entry.kind + " " + entry.id + " " + entry.cid
        if (key in entries) return
        if (entries.size >= limit) { truncated = true; return }
        entries[key] = entry
    }

    fun consumed(kind: String, id: String, cid: String, sequence: Long? = null, rev: String? = null, prefix: String? = null, glob: String? = null) =
        consumed(Consumed(kind, id, cid, sequence, rev, prefix, glob))

    fun entries(): List<Consumed> = entries.values.toList()

    /** The stored prompt versions read, by name: what a receipt's `promptVersions` names. */
    fun promptVersions(): Map<String, String> =
        entries.values.filter { it.kind == PROMPT }.associate { it.id to it.cid }

    /** One identity over everything read: the sorted, distinct cids joined. Same inputs, same fingerprint. */
    fun fingerprint(): String = fingerprintOf(entries.values.map { it.cid })

    fun fingerprintOf(cids: Collection<String>): String =
        ContentId.of(cids.distinct().sorted().joinToString("\n").encodeToByteArray()).value

    /**
     * The listing fingerprint `project.docs` records: over the sorted file ids, so an added or
     * removed file moves it while an edited file does not (the edit is the document's own row).
     */
    fun indexFingerprint(ids: Collection<String>): String = indexFingerprintOf(ids)


}
