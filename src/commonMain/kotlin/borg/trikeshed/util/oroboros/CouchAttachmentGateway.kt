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

    fun putAttachment(ref: OroborosAttachmentRef, bytes: ByteArray) {
        val cid = casStore.put(bytes)
        require(cid == ref.contentId) { "Provided bytes do not match expected ContentId" }

        val doc = Document(
            id = ref.path,
            fields = listOf(
                Field("contentType", ref.contentType),
                Field("length", ref.length.toString()),
                Field("contentId", ref.contentId.value),
                Field("agentId", ref.agentId),
                Field("revision", ref.revision),
                Field("sequence", ref.sequence.toString())
            )
        )
        couchStore.put(doc)
    }

    fun getAttachment(path: String): Pair<OroborosAttachmentRef, ByteArray>? {
        val doc = couchStore.get(path) ?: return null
        // check for tombstone
        if (doc.fields.any { it.name == "deleted" && it.value == "true" }) return null

        val contentType = doc.fields.find { it.name == "contentType" }?.value as? String ?: ""
        val lengthStr = doc.fields.find { it.name == "length" }?.value as? String ?: "0"
        val contentIdStr = doc.fields.find { it.name == "contentId" }?.value as? String ?: return null
        val agentId = doc.fields.find { it.name == "agentId" }?.value as? String ?: ""
        val revision = doc.fields.find { it.name == "revision" }?.value as? String ?: ""
        val sequenceStr = doc.fields.find { it.name == "sequence" }?.value as? String ?: "0"

        val cid = ContentId(contentIdStr)
        val bytes = casStore.get(cid) ?: return null

        val ref = OroborosAttachmentRef(
            path = doc.id,
            contentType = contentType,
            length = lengthStr.toLongOrNull() ?: 0L,
            contentId = cid,
            agentId = agentId,
            revision = revision,
            sequence = sequenceStr.toLongOrNull() ?: 0L
        )
        return Pair(ref, bytes)
    }

    fun deleteAttachment(path: String, revision: String) {
        val doc = couchStore.get(path) ?: return
        val currentRev = doc.fields.find { it.name == "revision" }?.value as? String
        if (currentRev == revision) {
            val tombstone = Document(
                id = path,
                fields = listOf(
                    Field("deleted", "true"),
                    Field("revision", revision)
                )
            )
            couchStore.put(tombstone)
        }
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
