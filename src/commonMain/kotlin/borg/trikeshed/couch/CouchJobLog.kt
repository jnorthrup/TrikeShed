package borg.trikeshed.couch

import borg.trikeshed.job.*

/**
 * A job's ordered command references in one revision-pinned Couch document.
 * Borrows Couch/CAS and their durability and file lifetimes; owns no platform I/O.
 * A stale or ambiguous append retires this writer. Reopen to recover its outcome.
 */
class CouchJobLog private constructor(
    private val couch: Couch,
    val documentId: String,
    private var revision: String?,
    private val retained: MutableList<Frame>,
) : JobLog(retained) {
    private var failed = false

    override fun append(sequence: Long, payload: ByteArray) {
        check(!failed) { "Job log '$documentId' must be reopened after a failed append" }
        require(sequence > (retained.lastOrNull()?.sequence ?: 0L)) { "Job log sequence must increase" }
        require(payload.isNotEmpty()) { "Empty job log payload" }
        val bytes = payload.copyOf()
        try {
            val cid = ContentId.of(bytes)
            check(couch.cas.put(bytes) == cid) { "Job log CAS acknowledgement mismatch" }
            check(couch.cas.get(cid)?.contentEquals(bytes) == true) { "Job log CAS read-back mismatch" }
            couch.cas.sync()
            val frames = retained.map { frame ->
                mapOf("sequence" to frame.sequence.toString(), "cid" to ContentId.of(frame.payload).value)
            } + mapOf("sequence" to sequence.toString(), "cid" to cid.value)
            // Never refresh revision here: doing so would let a stale writer erase another log.
            val ack = couch.put(documentId, mapOf("format" to FORMAT, "frames" to frames), revision)
            val nextRevision = ack["rev"] as? String
            check(ack["ok"] == true && ack["id"] == documentId && !nextRevision.isNullOrBlank()) {
                "Job log '$documentId' append was not acknowledged: $ack"
            }
            super.append(sequence, bytes)
            revision = nextRevision
        } catch (failure: Throwable) {
            failed = true
            throw failure
        }
    }

    override fun replay(): kotlin.sequences.Sequence<Frame> = retained.asSequence().map { it.copy(payload = it.payload.copyOf()) }

    override fun injectTornFrame(sequence: Long, payload: ByteArray): Unit =
        error("Torn-frame injection is only available on an in-memory job log")

    companion object {
        const val FORMAT = "job-log/v1"

        fun open(couch: Couch, documentId: String): CouchJobLog {
            require(documentId.isNotBlank()) { "Empty job log document id" }
            check(!couch.store.head.isDeleted(documentId)) { "Job log '$documentId' was deleted; explicit recovery is required" }
            val doc = couch.docJson(documentId)
                ?: return CouchJobLog(couch, documentId, null, mutableListOf())
            require(doc["format"] == FORMAT) { "Unsupported job log format for '$documentId'" }
            val revision = requireNotNull(doc["_rev"] as? String).also { require(it.isNotBlank()) }
            val frames = requireNotNull(doc["frames"] as? List<*>) { "Missing job log frames" }
            val retained = mutableListOf<Frame>()
            for (value in frames) {
                val frame = requireNotNull(value as? Map<*, *>) { "Invalid job log frame" }
                require(frame.keys == setOf("sequence", "cid")) { "Invalid job log frame fields" }
                // Decimal strings keep Long identities exact on every target, including JS.
                val sequence = requireNotNull((frame["sequence"] as? String)?.toLongOrNull()) { "Invalid job log sequence" }
                require(sequence > (retained.lastOrNull()?.sequence ?: 0L)) { "Job log sequence must increase" }
                val cid = ContentId(requireNotNull(frame["cid"] as? String) { "Missing job log CID" })
                val bytes = checkNotNull(couch.cas.get(cid)) { "Missing job log command $cid" }
                check(bytes.isNotEmpty() && ContentId.of(bytes) == cid) { "Corrupt job log command $cid" }
                retained.add(Frame(sequence, bytes.copyOf()))
            }
            return CouchJobLog(couch, documentId, revision, retained)
        }
    }
}
