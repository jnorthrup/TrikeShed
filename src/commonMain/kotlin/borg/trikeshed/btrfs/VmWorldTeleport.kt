package borg.trikeshed.btrfs

import borg.trikeshed.cas.FileTreeManifest
import borg.trikeshed.couch.Couch
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.view

/**
 * Publishes a canonical file-tree root through the existing Couch CAS transport.
 * File payloads remain independent content-addressed objects shared by worlds,
 * snapshots and replicas. Couch discovers their references from the root itself.
 * Older opaque send-stream documents remain readable by [restore].
 */
class VmWorldTeleport(
    private val db: Couch,
    private val store: BtrfsWorldStore,
) {
    fun docIdFor(guestId: String): String = "$PREFIX$guestId"

    /** Publish only after every referenced blob and the canonical root are verified in Couch CAS. */
    fun publish(guestId: String): Map<String, Any?> {
        val mount = store.mount(guestId)
        val subvolume = store.subvolumeFor(guestId)
        val manifest = mount.manifest(subvolume)
            ?: return mapOf("ok" to false, "error" to "not_found", "reason" to "no world for guest '$guestId'")
        for ((path, extent) in manifest.entries.view) {
            if (extent == null) continue
            // A mount upgraded from a private extent store migrates verified
            // objects through fetchFile before exposing them in the shared CAS.
            val bytes = runCatching { mount.fetchFile(subvolume, path) }.getOrNull()
            if (bytes == null || bytes.size.toLong() != extent.b || ContentId.of(bytes) != extent.a) {
                return mapOf("ok" to false, "error" to "incomplete_world", "reason" to "world contains a missing or corrupt file object")
            }
            putVerified(db.cas, extent.a, bytes)
        }
        val bytes = manifest.encode()
        val cid = ContentId.of(bytes)
        putVerified(db.cas, cid, bytes)
        val id = docIdFor(guestId)
        val result = db.put(
            id,
            mapOf(
                "kind" to KIND,
                "guest" to guestId,
                "contentId" to cid.value,
                "contentType" to CONTENT_TYPE,
                "length" to bytes.size.toLong(),
                "durable" to store.durable,
            ),
            db.store.head.getRev(id),
        )
        return if (result["ok"] == true) result + ("cid" to cid.value) + ("length" to bytes.size.toLong())
        else result
    }

    /**
     * Verify the root and every child before publishing a restored subvolume.
     * Existing subvolumes are never overwritten. Missing, corrupt, mismatched or
     * unsupported publications return false without exposing a partial tree.
     */
    fun restore(guestId: String, into: String = store.subvolumeFor(guestId)): Boolean {
        val doc = publication(guestId) ?: return false
        val bytes = rootBytes(doc) ?: return false
        return when (doc["contentType"]) {
            CONTENT_TYPE -> {
                val manifest = canonical(bytes) ?: return false
                if (!hasFiles(manifest)) return false
                val mount = store.mount(guestId)
                if (mount.hasSubvolume(into)) return false
                for ((_, extent) in manifest.entries.view) {
                    if (extent == null) continue
                    val child = verified(db.cas, extent.a) ?: return false
                    if (child.size.toLong() != extent.b) return false
                    putVerified(mount.cas, extent.a, child)
                }
                mount.receiveManifest(into, manifest, readOnly = true)
            }
            LEGACY_CONTENT_TYPE, null -> store.mount(guestId).receive(into, bytes)
            else -> false
        }
    }

    fun published(): List<String> = db.store.all()
        .filter { !db.isTombstone(it) && it.id.startsWith(PREFIX) }
        .map { it.id.removePrefix(PREFIX) }
        .filter { publication(it) != null }
        .sorted()

    /** Locality includes verified file children, not merely the document or root object. */
    fun isLocal(guestId: String): Boolean {
        val doc = publication(guestId) ?: return false
        val bytes = rootBytes(doc) ?: return false
        return when (doc["contentType"]) {
            CONTENT_TYPE -> canonical(bytes)?.let(::hasFiles) ?: false
            LEGACY_CONTENT_TYPE, null -> true
            else -> false
        }
    }

    private fun publication(guestId: String): Map<String, Any?>? =
        db.docJson(docIdFor(guestId))?.takeIf { it["kind"] == KIND && it["guest"] == guestId }

    private fun rootBytes(doc: Map<String, Any?>): ByteArray? {
        val cid = runCatching { ContentId(doc["contentId"] as? String ?: return null) }.getOrNull() ?: return null
        val bytes = verified(db.cas, cid) ?: return null
        val length = doc["length"] as? Number ?: return null
        return bytes.takeIf { length.toLong() == it.size.toLong() && length.toDouble() == it.size.toDouble() }
    }

    private fun hasFiles(manifest: FileTreeManifest): Boolean = manifest.entries.view.all { (_, extent) ->
        extent == null || verified(db.cas, extent.a)?.size?.toLong() == extent.b
    }

    private fun canonical(bytes: ByteArray): FileTreeManifest? = runCatching { FileTreeManifest.decode(bytes) }.getOrNull()

    private fun verified(cas: CasStore, cid: ContentId): ByteArray? =
        runCatching { cas.get(cid)?.takeIf { ContentId.of(it) == cid } }.getOrNull()

    private fun putVerified(cas: CasStore, cid: ContentId, bytes: ByteArray) {
        if (verified(cas, cid)?.contentEquals(bytes) == true) return
        check(cas.put(bytes) == cid && verified(cas, cid)?.contentEquals(bytes) == true) {
            "CAS did not persist the requested world object"
        }
    }

    companion object {
        const val PREFIX = "vm-worlds/"
        const val KIND = "vm-world"
        const val CONTENT_TYPE = FileTreeManifest.CONTENT_TYPE
        const val LEGACY_CONTENT_TYPE = "application/x-trikeshed-btrfs-send"
    }
}
