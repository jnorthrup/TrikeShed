package borg.trikeshed.btrfs

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.job.ContentId

/**
 * A file-based guest VM world, published so it replicates.
 *
 * The world was already content-addressed — [UserspaceBtrfs] keys every extent by its hash — and
 * [UserspaceBtrfs.send] already emits a self-contained stream that [UserspaceBtrfs.receive]
 * verifies before it commits anything. What was missing was the join: nothing put that stream where
 * the couch transport could see it, so a VM world could not move between nodes even though every
 * piece needed to move it existed.
 *
 * [publish] lands the stream as a CAS block and writes an ordinary attachment document naming it:
 *
 * ```
 * vm-worlds/<guest>  { kind: "vm-world", guest, contentId: "sha256:…", contentType, length }
 * ```
 *
 * From there nothing special happens, which is the point. `CouchDatabase.referencedCids` already
 * treats a `contentId` as a blob the replicator must ship, so `_replicate` carries the world; the
 * document renders a 1.x `_attachments` stub, so `GET /{db}/vm-worlds/<guest>/content` serves the
 * stream; `_cas/{cid}` and the IPFS `/api/v0/block/get` alias serve the same bytes; and a
 * RequestFactory client can pull it with `block_get`. One publish puts a VM world on every lane the
 * service already had.
 *
 * [restore] is the far side: fetch the block this node now holds and receive it into a subvolume.
 */
class VmWorldTeleport(
    private val db: CouchDatabase,
    private val store: BtrfsWorldStore,
) {
    /** A guest's world document id. */
    fun docIdFor(guestId: String): String = "$PREFIX$guestId"

    private fun mount(guestId: String) = UserspaceBtrfs(store.root, store.fileOpsFor(guestId))

    /**
     * Snapshot the guest's world into the CAS and name it with a document.
     *
     * Idempotent by content: an unchanged world hashes to the block already stored, so republishing
     * costs one hash and no new bytes. Returns a failure map rather than throwing when the guest has
     * no world yet — publishing something that was never written is a caller error, not a crash.
     */
    fun publish(guestId: String): Map<String, Any?> {
        val subvol = store.subvolumeFor(guestId)
        val stream = mount(guestId).send(subvol)
            ?: return mapOf("ok" to false, "error" to "not_found", "reason" to "no world for guest '$guestId'")
        val cid = db.blockPut(stream)
        val id = docIdFor(guestId)
        val result = db.put(
            id,
            mapOf(
                "kind" to KIND,
                "guest" to guestId,
                "contentId" to cid.value,
                "contentType" to CONTENT_TYPE,
                "length" to stream.size.toLong(),
                "durable" to store.durable,
            ),
            db.store.head.getRev(id),
        )
        return if (result["ok"] == true) result + ("cid" to cid.value) + ("length" to stream.size.toLong())
        else result
    }

    /**
     * Receive a published world into [into] (the guest's own subvolume by default).
     *
     * Returns false when the document is absent, when the block has not replicated here yet, or
     * when the target subvolume already exists — [UserspaceBtrfs.receive] refuses to overwrite a
     * live subvolume, and this does not force it. Restoring over a running guest's world would need
     * a name of its own, which is the caller's decision to make rather than this method's.
     */
    fun restore(guestId: String, into: String = store.subvolumeFor(guestId)): Boolean {
        val doc = db.docJson(docIdFor(guestId)) ?: return false
        val cidText = doc["contentId"] as? String ?: return false
        val bytes = db.cas.get(ContentId(cidText)) ?: return false // not replicated to this node yet
        return mount(guestId).receive(into, bytes)
    }

    /** Guests with a published world in this database, whether or not their blocks are local. */
    fun published(): List<String> =
        db.store.all()
            .filter { !db.isTombstone(it) && it.id.startsWith(PREFIX) }
            .map { it.id.removePrefix(PREFIX) }
            .sorted()

    /** True when this node actually holds the bytes, not just the document naming them. */
    fun isLocal(guestId: String): Boolean {
        val cidText = db.docJson(docIdFor(guestId))?.get("contentId") as? String ?: return false
        return db.cas.get(ContentId(cidText)) != null
    }

    companion object {
        const val PREFIX = "vm-worlds/"
        const val KIND = "vm-world"
        const val CONTENT_TYPE = "application/x-trikeshed-btrfs-send"
    }
}
