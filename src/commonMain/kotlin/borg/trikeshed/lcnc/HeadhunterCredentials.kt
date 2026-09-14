package borg.trikeshed.lcnc

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchChangesProjection
import borg.trikeshed.couch.CouchCommittedFrame
import borg.trikeshed.couch.CouchHeadProjection
import borg.trikeshed.couch.CouchStore
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.ProductionCouchIngress
import borg.trikeshed.isam.synchronizedLock
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Closeable
import borg.trikeshed.narsese.DocumentAppendLog
import borg.trikeshed.narsese.DocumentCasStore
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.file.attribute.PosixFilePermission
import borg.trikeshed.userspace.nio.file.attribute.PosixFilePermissions
import keymux.CouchKeyStore

/** Private credential CAS and revision log. The host must not attach this database to public surfaces. */
class HeadhunterCredentials private constructor(
    private val cas: DocumentCasStore,
    private val log: DocumentAppendLog,
    internal val database: Couch,
    private val gate: Any,
    private val release: () -> Unit,
) : Closeable {
    val keys = CouchKeyStore(database)

    companion object {
        /** The host creates the private directory and owns its access mode before opening these files. */
        suspend fun open(
            parentPath: String,
            casPath: String = "$parentPath/source-credentials.cas",
            logPath: String = "$parentPath/source-credentials.wal",
        ): HeadhunterCredentials {
            val access = PosixFilePermissions.asFileAttribute(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            for (path in arrayOf(casPath, logPath)) {
                FileChannel.open(path, setOf(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE), access)
                    .use { it.force(true) }
            }
            val cas = DocumentCasStore.open(casPath, parentPath)
            var log: DocumentAppendLog? = null
            try {
                val ledger = DocumentAppendLog.open(logPath, parentPath).also { log = it }
                val head = CouchHeadProjection()
                val changes = CouchChangesProjection()
                var nextSequence = ledger.replay { sequence, payload ->
                    val entry = CanonicalCbor.decodeMap(payload)
                    val id = entry["id"] as? String ?: error("Credential log has no document id")
                    val revision = entry["rev"] as? String ?: error("Credential log has no revision")
                    val deleted = entry["deleted"] as? Boolean ?: error("Credential log has no deletion flag")
                    val cid = (entry["bodyCid"] as? String)?.let(::ContentId)
                    require(deleted == (cid == null)) { "Credential log has an invalid body reference" }
                    val doc = cid?.let {
                        CouchStoreFactory.documentFromBody(cas.get(it) ?: error("Credential log body is missing"))
                            ?: error("Credential log body is invalid")
                    }
                    require(doc == null || doc.id == id) { "Credential log body belongs to another document" }
                    val frame = CouchCommittedFrame(sequence - 1, id, revision, deleted, doc)
                    head.applyCommit(frame)
                    changes.applyCommit(frame)
                }
                val gate = Any()
                var closed = false
                var failed = false
                fun checkOpen() {
                    check(!closed) { "Private credential store is closed" }
                    check(!failed) { "Private credential commit failed; reopen before use" }
                }
                val ingress = ProductionCouchIngress(head,
                    commitBoundary = { frame -> synchronizedLock(gate) {
                        checkOpen()
                        try {
                            val cid = frame.doc?.let { cas.put(CouchStoreFactory.canonicalBody(it)) }
                            val committed = frame.copy(sequence = nextSequence)
                            ledger.append(nextSequence + 1, CanonicalCbor.encodeMap(mapOf(
                                "id" to frame.docId, "rev" to frame.rev, "deleted" to frame.deleted,
                                "bodyCid" to cid?.value,
                            )))
                            ledger.flush()
                            head.applyCommit(committed)
                            changes.applyCommit(committed)
                            nextSequence++
                        } catch (failure: Throwable) {
                            failed = true
                            throw failure
                        }
                    } },
                    contentIdFn = { doc -> synchronizedLock(gate) {
                        checkOpen()
                        cas.put(CouchStoreFactory.canonicalBody(doc))
                    } },
                )
                val db = Couch("source-credentials", CouchStore(ingress, head, changes), cas)
                return HeadhunterCredentials(cas, ledger, db, gate) { closed = true }
            } catch (failure: Throwable) {
                try { log?.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                try { cas.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                throw failure
            }
        }
    }

    /** Synchronous commits finish inside the same gate before either file is closed. */
    override fun close(): Unit = synchronizedLock(gate) {
        release()
        var failure: Throwable? = null
        try { log.close() } catch (caught: Throwable) { failure = caught }
        try { cas.close() } catch (caught: Throwable) {
            failure?.addSuppressed(caught) ?: run { failure = caught }
        }
        failure?.let { throw it }
    }
}
