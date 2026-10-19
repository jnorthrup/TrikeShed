package borg.trikeshed.util.oroboros

import borg.trikeshed.couch.CouchStore
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.j
import borg.trikeshed.lib.joins

data class OroborosAttachmentRef(
    val path: String,
    val contentType: String,
    val length: Long,
    val contentId: ContentId,
    val agentId: String,
    val revision: String,
    val sequence: Long
)

class CouchAttachmentGateway(
    private val couchStore: CouchStore,
    private val casStore: CasStore
) {

    /** [source]: the file [bytes] were read from; the CAS then names it in place rather than copying it. */
    fun putAttachment(ref: OroborosAttachmentRef, bytes: ByteArray, source: String? = null) {
        val cid = if (source != null) casStore.putRef(source, bytes, ref.contentId) else casStore.put(bytes)
        require(cid == ref.contentId) { "Provided bytes do not match expected ContentId" }

        // The derived-code-as-metadata precedent (MemoryStore.spineCid): the locality-preserving
        // AngularCodec coordinate rides every attachment doc — taxonomy signature of the path
        // (doc ids ARE paths) + simhash of the decodable text surface, so similarity grouping
        // never needs the bytes. Code is deterministic in (path, bytes).
        val textSurface = runCatching {
            val s = bytes.decodeToString()
            if (s.none { it == '\uFFFD' || (it.code < 32 && it !in " \t\r\n") }) s.take(4096) else ""
        }.getOrDefault("")
        val code = borg.trikeshed.narsese.AngularCodec.fragmentCode(
            (borg.trikeshed.narsese.AngularCodec.taxonomySigOfKey(ref.path).toString()) + "/" + textSurface,
        )
        val doc = Document(
            id = ref.path,
            fields = listOf(
                Field("contentType", ref.contentType),
                Field("length", ref.length.toString()),
                Field("contentId", ref.contentId.value),
                Field("agentId", ref.agentId),
                Field("revision", ref.revision),
                Field("sequence", ref.sequence.toString()),
                Field("code", code.toString()),
                Field("codeRing8", (code and 0xFF).toString())
            )
        )
        // The head rev is required once the path exists (ProductionCouchIngress semantics);
        // without it every re-reconcile of a changed file was a silent conflict.
        couchStore.put(doc, couchStore.head.getRev(ref.path))
    }

    fun getAttachment(path: String): Pair<OroborosAttachmentRef, ByteArray>? {
        val ref = getAttachmentRef(path) ?: return null
        val bytes = casStore.get(ref.contentId) ?: return null
        return Pair(ref, bytes)
    }

    /** Attachment identity and stamp without reading its source bytes. */
    fun getAttachmentRef(path: String): OroborosAttachmentRef? {
        val doc = couchStore.get(path) ?: return null
        // check for tombstone
        if (doc.fields.any { it.name == "deleted" && it.value == "true" }) return null

        val contentType = doc.fields.find { it.name == "contentType" }?.value as? String ?: ""
        val lengthStr = doc.fields.find { it.name == "length" }?.value as? String ?: "0"
        val contentIdStr = doc.fields.find { it.name == "contentId" }?.value as? String ?: return null
        val agentId = doc.fields.find { it.name == "agentId" }?.value as? String ?: ""
        val revision = doc.fields.find { it.name == "revision" }?.value as? String ?: ""
        val sequenceStr = doc.fields.find { it.name == "sequence" }?.value as? String ?: "0"

        return OroborosAttachmentRef(
            path = doc.id,
            contentType = contentType,
            length = lengthStr.toLongOrNull() ?: 0L,
            contentId = ContentId(contentIdStr),
            agentId = agentId,
            revision = revision,
            sequence = sequenceStr.toLongOrNull() ?: 0L
        )
    }

    /** Tombstone [path] when it still carries [revision]; false when it is already gone or has moved on. */
    fun deleteAttachment(path: String, revision: String): Boolean {
        val doc = couchStore.get(path) ?: return false
        val currentRev = doc.fields.find { it.name == "revision" }?.value as? String
        if (currentRev != revision) return false
        val tombstone = Document(
            id = path,
            fields = listOf(
                Field("deleted", "true"),
                Field("revision", revision)
            )
        )
        return couchStore.put(tombstone, couchStore.head.getRev(path))
    }

    fun listAttachments(prefix: String): List<OroborosAttachmentRef> {
        val allDocs = couchStore.all().filter { doc ->
            doc.fields.none { it.name == "deleted" && it.value == "true" }
        }
        val result = mutableListOf<OroborosAttachmentRef>()
        for (doc in allDocs) {
            val docId = doc.id
            if (!docId.startsWith(prefix)) continue
            val contentType = doc.fields.find { it.name == "contentType" }?.value as? String ?: ""
            val lengthStr = doc.fields.find { it.name == "length" }?.value as? String ?: "0"
            val contentIdStr = doc.fields.find { it.name == "contentId" }?.value as? String ?: ""
            val agentId = doc.fields.find { it.name == "agentId" }?.value as? String ?: ""
            val revision = doc.fields.find { it.name == "revision" }?.value as? String ?: ""
            val sequenceStr = doc.fields.find { it.name == "sequence" }?.value as? String ?: "0"
            val contentId = if (contentIdStr.isNotEmpty()) ContentId(contentIdStr)
                else ContentId("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
            result.add(OroborosAttachmentRef(
                path = docId,
                contentType = contentType,
                length = lengthStr.toLongOrNull() ?: 0L,
                contentId = contentId,
                agentId = agentId,
                revision = revision,
                sequence = sequenceStr.toLongOrNull() ?: 0L
            ))
        }
        return result
    }

    fun manifest(): Series2<String, OroborosAttachmentRef> {
        val allDocs = couchStore.all().filter { doc ->
            doc.fields.none { it.name == "deleted" && it.value == "true" }
        }

        val paths = allDocs.size j { i: Int -> allDocs[i].id }
        val refs = allDocs.size j { i: Int ->
            val doc = allDocs[i]
            val contentType = doc.fields.find { it.name == "contentType" }?.value as? String ?: ""
            val lengthStr = doc.fields.find { it.name == "length" }?.value as? String ?: "0"
            val contentIdStr = doc.fields.find { it.name == "contentId" }?.value as? String ?: ""
            val agentId = doc.fields.find { it.name == "agentId" }?.value as? String ?: ""
            val revision = doc.fields.find { it.name == "revision" }?.value as? String ?: ""
            val sequenceStr = doc.fields.find { it.name == "sequence" }?.value as? String ?: "0"

            OroborosAttachmentRef(
                path = doc.id,
                contentType = contentType,
                length = lengthStr.toLongOrNull() ?: 0L,
                contentId = ContentId(contentIdStr.ifEmpty { "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" }),
                agentId = agentId,
                revision = revision,
                sequence = sequenceStr.toLongOrNull() ?: 0L
            )
        }

        return paths.joins(refs)
    }
}
