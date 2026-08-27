package borg.trikeshed.cas

import borg.trikeshed.couch.CouchStore
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

/**
 * R2 — persist the inverted index so rings and sessions don't re-derive the world each boot.
 *
 * [LineCasIndex] is process-local: every boot re-derives the whole link-match continent from
 * scratch. This lands the index's stable [LineCasIndex.snapshot] as a content-addressed blob
 * (CAS) referenced by a durable Couch document, so a boot can restore the exact indexed spines
 * — no cold derivation.
 *
 * The snapshot is the source of truth: it round-trips byte-exactly (a linkedKey encodes the
 * stamp AND the contentCid; the code is the coordinate), so restore == re-derive. The doc carries
 * the usual `contentId` (the blob) plus `docCount` / `contentKeys` / `seq` so the surface can
 * show the index's own shape without reading the blob.
 *
 * Incremental: [write] is called after every [MemoryStore] ingest, so the snapshot trails the
 * live index by at most one put — never a full re-derive at boot.
 */
object LineCasIndexPersistence {
    /** The durable document id holding the current snapshot. */
    const val DOC_ID = "index/linecas/v1"

    /** CAS-put the snapshot bytes and land the reference document. Returns the blob ContentId. */
    fun write(couch: CouchStore, cas: CasStore, index: LineCasIndex): ContentId {
        val text = index.snapshot()
        val bytes = text.encodeToByteArray()
        val cid = cas.put(bytes)
        val doc = Document(
            DOC_ID,
            listOf(
                Field("kind", "linecas-index"),
                Field("docCount", index.documentCount.toString()),
                Field("contentKeys", index.contentKeyCount.toString()),
                Field("spineBytes", bytes.size.toString()),
                Field("contentId", cid.value),
            ),
        )
        couch.put(doc, couch.head.getRev(DOC_ID))
        return cid
    }

    /** True when a snapshot document is present and non-tombstoned. */
    fun exists(couch: CouchStore): Boolean {
        val d = couch.get(DOC_ID) ?: return false
        return d.fields.none { it.name == "_deleted" && it.value == true }
    }

    /** Read the snapshot blob bytes back from the store; null when absent. */
    fun read(couch: CouchStore, cas: CasStore): ByteArray? {
        val d = couch.get(DOC_ID) ?: return null
        if (d.fields.any { it.name == "_deleted" && it.value == true }) return null
        val cidStr = d.fields.firstOrNull { it.name == "contentId" }?.value as? String ?: return null
        return cas.get(ContentId(cidStr))
    }

    /** The [LineCasIndex] restored from the persisted snapshot; null when no snapshot exists. */
    fun restore(couch: CouchStore, cas: CasStore): LineCasIndex? {
        val bytes = read(couch, cas) ?: return null
        return LineCasIndex.restore(bytes.decodeToString())
    }

    /**
     * Rebuild the snapshot from a corpus of spines (the boot-time fallback when no snapshot
     * exists yet, or an explicit re-derive): ingest every spine into a fresh index, persist it.
     */
    fun rebuildFrom(couch: CouchStore, cas: CasStore, spines: Series<LineSpine>): ContentId {
        val idx = LineCasIndex()
        for (i in 0 until spines.size) idx.ingestSpine(spines[i])
        return write(couch, cas, idx)
    }
}
