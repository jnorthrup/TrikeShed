package borg.trikeshed.forge.server

import borg.trikeshed.lcnc.ProjectCorpus
import borg.trikeshed.lcnc.ProjectDoc
import borg.trikeshed.lcnc.ProjectGlob
import borg.trikeshed.lcnc.ProjectNodes
import borg.trikeshed.lcnc.ProjectRef
import borg.trikeshed.lcnc.ProjectText
import borg.trikeshed.lcnc.ProjectVisitUnavailable
import borg.trikeshed.couch.CouchStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.job.BoundedCasReader
import borg.trikeshed.narsese.DocumentAnchor
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

private const val EMPTY_CID = "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

/** An explicitly bound primary worktree, stored under a physical prefix in the daemon's main store. */
data class PrimaryProjectBinding(val name: String, val prefix: String, val store: CouchStore,
    val gateway: CouchAttachmentGateway, val path: String, val reader: BoundedCasReader? = null,
    val maxBytes: Int = DEFAULT_MAX_BYTES) {
    companion object { const val DEFAULT_MAX_BYTES = 16 * 1024 * 1024 }
    init {
        require(name.isNotBlank() && '/' !in name && '\u0000' !in name)
        require(prefix == "projects/$name/") { "Primary prefix must identify the bound project" }
        require(gateway.uses(store)) { "Primary gateway and store must share a backing store" }
        require(reader == null || gateway.uses(reader)) { "Primary bounded reader must own the gateway's CAS" }
        require(path.isNotBlank()) { "Primary project path is required" }
        require(maxBytes in 1 until Int.MAX_VALUE) { "Invalid primary read limit" }
    }
}

/**
 * The daemon's [ProjectCorpus]: mounted project databases read as a document set.
 * Listing reads attachment metadata only (no bytes); reading fetches one
 * attachment on the IO dispatcher and refuses binaries and over-budget bytes.
 * The mined twins the miner leaves beside documents are never listed as
 * documents; `project.extract` reaches them by name.
 */
class JvmProjectCorpus(
    private val registry: ProjectDbRegistry,
    private val scopes: ProjectScopes,
    miner: ProjectMiner? = null,
    private val ingest: (suspend (String, String) -> Unit)? = miner?.let { m ->
        { project, id -> m.ingest(project, id); Unit }
    },
    private val primary: PrimaryProjectBinding? = null,
) : ProjectCorpus {
    internal fun canRetainPrimaryText(row: ProjectDoc): Boolean {
        val b = backing(row.project) ?: return false
        return b.primary && b.reader is borg.trikeshed.job.PackCasStore && row.contentStatus == "verified-bytes" &&
            row.length in 0..(4L * 1024 * 1024) && row.rev.isNotBlank() && row.seq >= 0 &&
            row.id.substringAfterLast('.', "").lowercase() in setOf("txt", "md")
    }
    /** Explicit TXT/MD admission names the reconciled store snapshot, never current disk bytes.
     * Pack ownership is required: generic CasStore.put does not promise REF promotion. */
    suspend fun retainPrimaryText(expected: ProjectDoc, snapshots: borg.trikeshed.job.PackCasStore): borg.trikeshed.narsese.DocumentSource =
        withContext(Dispatchers.IO) {
            val b = requireNotNull(backing(expected.project)) { "Primary project is unavailable" }
            require(b.primary) { "Explicit primary text admission requires the bound primary" }
            require(expected.cid.isNotBlank() && expected.rev.isNotBlank() && expected.seq >= 0) { "A complete source revision is required" }
            require(expected.id.substringAfterLast('.', "").lowercase() in setOf("txt", "md")) { "Primary manual admission supports TXT and MD only" }
            val original = requireNotNull(document(b, expected.id)) { "Primary source bytes are unavailable" }
            fun matches(row: ProjectDoc?) = row != null && row.cid == expected.cid && row.rev == expected.rev && row.seq == expected.seq
            require(matches(original)) { "Selected primary revision changed" }
            val bytes = requireNotNull(bytes(expected.project, expected.id)) { "Primary source bytes are unavailable" }
            require(bytes.size <= 4 * 1024 * 1024 && ContentId.of(bytes).value == expected.cid) { "Primary text identity or size mismatch" }
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            require(text.encodeToByteArray().contentEquals(bytes)) { "Primary text is not exact UTF-8" }
            require(matches(document(b, expected.id))) { "Primary revision changed during admission" }
            val cid = ContentId(expected.cid)
            check(snapshots.put(bytes) == cid)
            snapshots.sync()
            check(snapshots.getBounded(cid, 4 * 1024 * 1024)?.contentEquals(bytes) == true) { "Owned primary snapshot readback failed" }
            require(matches(document(b, expected.id))) { "Primary revision changed during retention" }
            val revision = borg.trikeshed.narsese.DocumentRevision(expected.project, expected.id, expected.cid, expected.rev, expected.seq)
            borg.trikeshed.narsese.DocumentSource(cid, cid, text, expected.id, original.contentType,
                "${expected.project}/${expected.id}@${expected.rev}", category = "document",
                provenance = mapOf("extractionKind" to "identity-utf8", "snapshotKind" to "reconciled-worktree", "reviewStatus" to "unreviewed"),
                project = revision, extraction = revision)
        }

    suspend fun retainPrimaryTextReceipt(expected: ProjectDoc): Map<String, Any?> {
        val b = requireNotNull(backing(expected.project)) { "Primary project is unavailable" }
        val snapshots = b.reader as? borg.trikeshed.job.PackCasStore
            ?: throw UnsupportedOperationException("Owned primary snapshot storage is unavailable")
        val source = retainPrimaryText(expected, snapshots)
        val receipt = borg.trikeshed.narsese.DocumentCuratorCodec.source(source) - "text"
        val bytes = borg.trikeshed.job.CanonicalCbor.encodeMap(receipt)
        val cid = snapshots.put(bytes)
        snapshots.sync()
        check(snapshots.getBounded(cid, bytes.size)?.contentEquals(bytes) == true)
        require(document(expected.project, expected.id)?.let {
            it.cid == expected.cid && it.rev == expected.rev && it.seq == expected.seq
        } == true) { "Primary revision changed during receipt publication" }
        return receipt + mapOf("sourceCid" to cid.value, "reviewStatus" to "unreviewed", "action" to "retained-source")
    }

    internal fun requireManualLinkProject(expected: ProjectDoc) {
        require(expected.project != primary?.name && registry.get(expected.project) != null &&
            scopes.intakeMode(expected.project) == CurationIntake.Mode.Manual) { "Link collection requires a mounted Manual project" }
    }

    internal fun retainSelectedPdfLink(expected: ProjectDoc, inventory: ContentId, linkId: String,
        extension: String, bytes: ByteArray, evidence: ContentId): String {
        requireManualLinkProject(expected)
        require(extension == "pdf" || extension == "html")
        val identity = ContentId.of((inventory.value + "/" + linkId + "/" + ContentId.of(bytes).value).encodeToByteArray())
        val id = "pdf-links/${identity.hex}.$extension"
        val notes = "inventory-cid: ${inventory.value}\nsource-cid: ${expected.cid}\nlink-id: $linkId\nevidence-cid: ${evidence.value}\nreview-status: unreviewed\n".encodeToByteArray()
        scopes.importSelectedLink(expected, id, bytes, notes)
        return id
    }

    /** Primary worktrees are read-only here; only mounted project databases support intake. */
    internal fun intakeEligible(project: String): Boolean = project != primary?.name && registry.get(project) != null

    private val extracting = ConcurrentHashMap<Pair<String, String>, Mutex>()
    private data class Backing(val project: String, val store: CouchStore, val gateway: CouchAttachmentGateway,
        val prefix: String, val primary: Boolean, val reader: BoundedCasReader? = null, val maxBytes: Int = 0)
    private class EvidenceChanged : RuntimeException()

    private fun backing(project: String): Backing? {
        val binding = primary
        if (binding != null && project == binding.name) {
            check(registry.get(project) == null) { "Ambiguous primary and mounted project: $project" }
            val scope = scopes.list().filter { it.name == project }
            if (scope.isEmpty()) return null
            check(scope.size == 1 && scope.single().kind == "git" && scope.single().prefix == binding.prefix &&
                scope.single().path == binding.path) {
                "Primary project binding does not match its registered scope: $project"
            }
            return Backing(project, binding.store, binding.gateway, binding.prefix, true, binding.reader, binding.maxBytes)
        }
        val pdb = registry.get(project) ?: return null
        return Backing(project, pdb.store, pdb.gateway, "", false)
    }

    /** The reader, not attachment metadata, enforces the allocation bound and verifies the CID. */
    private fun primaryBytes(b: Backing, ref: borg.trikeshed.util.oroboros.OroborosAttachmentRef): ByteArray? {
        if (ref.length < 0 || ref.length > b.maxBytes) return null
        val reader = b.reader ?: return null
        val bytes = try { reader.getBounded(ref.contentId, b.maxBytes) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return null }
        return bytes?.takeIf { it.size <= b.maxBytes && it.size.toLong() == ref.length && ContentId.of(it) == ref.contentId }
    }

    /** A source is returned only when its bytes and metadata still match one committed slot. */
    private suspend fun verified(b: Backing, id: String): Pair<borg.trikeshed.couch.CouchHeadSlot, ByteArray>? {
        currentCoroutineContext().ensureActive()
        val physical = b.prefix + id
        val slot = b.store.head.slot(physical) ?: return null
        if (slot.deleted || slot.attachmentDeleted) return null
        check(!slot.ambiguous) { "Ambiguous attachment metadata: ${b.project}/$id" }
        val cid = ContentId(slot.contentId ?: return null)
        val declared = slot.length?.toLongOrNull() ?: return null
        if (declared < 0) return null
        val bytes = if (b.primary) {
            val ref = borg.trikeshed.util.oroboros.OroborosAttachmentRef(physical, slot.contentType.orEmpty(), declared, cid, "", "", slot.sequence)
            val loaded = primaryBytes(b, ref)
            currentCoroutineContext().ensureActive()
            loaded ?: return null
        } else {
            val attachment = b.gateway.getAttachment(physical)
            currentCoroutineContext().ensureActive()
            val (ref, loaded) = attachment ?: return null
            if (ref.contentId != cid || ref.contentType != slot.contentType.orEmpty()) return null
            if (ref.length != declared || loaded.size.toLong() != declared) return null
            if (ContentId.of(loaded) != cid) return null
            loaded
        }
        currentCoroutineContext().ensureActive()
        if (backing(b.project)?.store !== b.store || b.store.head.slot(physical) != slot) return null
        return Pair(slot, bytes)
    }

    /** Missing extraction evidence may be mined; present but unverifiable evidence is an error. */
    private suspend fun evidence(b: Backing, id: String): Pair<borg.trikeshed.couch.CouchHeadSlot, ByteArray>? {
        val slot = b.store.head.slot(b.prefix + id) ?: return null
        if (slot.deleted || slot.attachmentDeleted) return null
        return verified(b, id) ?: error("Extraction evidence failed verification: ${b.project}/$id")
    }

    private fun document(backing: Backing, id: String, verifyBytes: Boolean = true): ProjectDoc? {
        val physical = backing.prefix + id
        val slot = backing.store.head.slot(physical) ?: return null
        if (slot.deleted || slot.attachmentDeleted) return null
        check(!slot.ambiguous) { "Ambiguous attachment metadata: ${backing.project}/$id" }
        val cid = ContentId(slot.contentId ?: return null)
        val length = slot.length?.toLongOrNull() ?: 0L
        val type = slot.contentType.orEmpty()
        if (backing.primary && verifyBytes) {
            val declared = slot.length?.toLongOrNull() ?: return null
            if (declared < 0) return null
            val ref = borg.trikeshed.util.oroboros.OroborosAttachmentRef(physical, type, declared, cid, "", "", slot.sequence)
            if (primaryBytes(backing, ref) == null) return null
            if (this.backing(backing.project)?.store !== backing.store || backing.store.head.slot(physical) != slot) return null
        }
        return ProjectDoc(backing.project, id, cid.value, slot.rev, slot.sequence, length, type,
            contentStatus = if (backing.primary) (if (verifyBytes) "verified-bytes" else "unchecked-metadata") else null)
    }

    override suspend fun projects(): List<ProjectRef> = scopes.list().map {
        if (primary?.name == it.name) backing(it.name)
        ProjectRef(it.name, it.kind, it.path, registry.get(it.name)?.docCount ?: it.docs)
    }

    override suspend fun docs(project: String, prefix: String, glob: String, limit: Int): List<ProjectDoc> = withContext(Dispatchers.IO) {
        require(limit >= 0) { "Requested element count $limit is less than zero." }
        val b = backing(project) ?: return@withContext emptyList()
        val result = mutableListOf<Pair<String, borg.trikeshed.couch.CouchHeadSlot>>()
        slots(b, { it.startsWith(prefix) && ProjectGlob.matches(glob, it) }) { id, slot ->
            // The gateway validates all present CIDs before sorting/taking; a missing
            // primary CID is rejected only if that row is selected.
            ContentId(slot.contentId.orEmpty().ifEmpty { EMPTY_CID })
            result.add(Pair(id, slot))
        }
        result.sortedBy { it.first }.take(limit).map { (id, slot) -> listed(b, id, slot) }
    }

    override suspend fun visit(project: String, emit: suspend (ProjectDoc) -> Unit) {
        val b = backing(project) ?: throw ProjectVisitUnavailable(project)
        slots(b, { true }) { id, slot -> emit(listed(b, id, slot)) }
    }

    private fun listed(b: Backing, id: String, frame: borg.trikeshed.couch.CouchHeadSlot): ProjectDoc {
        val declaredCid = frame.contentId
        val rawCid = if (b.primary) (declaredCid ?: error("Primary document changed while listing: ${b.project}/$id"))
            else declaredCid.orEmpty().ifEmpty { EMPTY_CID }
        val cid = ContentId(rawCid)
        return ProjectDoc(b.project, id, cid.value, frame.rev, frame.sequence, frame.length?.toLongOrNull() ?: 0L,
            frame.contentType.orEmpty(), contentStatus = if (b.primary) "unchecked-metadata" else null)
    }

    private suspend fun slots(b: Backing, accepts: (String) -> Boolean,
                              emit: suspend (String, borg.trikeshed.couch.CouchHeadSlot) -> Unit) {
        val project = b.project
        // Slots are stable by document id: updates/tombstones replace a slot, while
        // new ids append beyond the captured count. Each frame is read atomically.
        val slots = b.store.head.slots()
        for (i in 0 until slots.a) {
            currentCoroutineContext().ensureActive()
            if ((i and 255) == 0) yield()
            if (backing(project)?.store !== b.store) throw ProjectVisitUnavailable(project)
            val frame = slots.b(i)
            if (frame.deleted || !frame.docId.startsWith(b.prefix)) continue
            val id = frame.docId.removePrefix(b.prefix)
            if (ProjectNodes.twin(id) || !accepts(id)) continue
            if (frame.attachmentDeleted) continue
            check(!frame.ambiguous) { "Ambiguous attachment metadata: $project/$id" }
            emit(id, frame)
        }
        currentCoroutineContext().ensureActive()
        if (backing(project)?.store !== b.store) throw ProjectVisitUnavailable(project)
    }

    override suspend fun visit(project: String, emit: suspend (ProjectDoc) -> Unit) {
        val b = backing(project) ?: throw ProjectVisitUnavailable(project)
        // Slots are stable by document id: updates/tombstones replace a slot, while
        // new ids append beyond the captured count. Each frame is read atomically.
        val slots = b.store.head.slots()
        for (i in 0 until slots.a) {
            currentCoroutineContext().ensureActive()
            if ((i and 255) == 0) yield()
            if (backing(project)?.store !== b.store) throw ProjectVisitUnavailable(project)
            val frame = slots.b(i)
            if (frame.deleted || !frame.docId.startsWith(b.prefix)) continue
            val id = frame.docId.removePrefix(b.prefix)
            if (ProjectNodes.twin(id)) continue
            if (frame.attachmentDeleted) continue
            check(!frame.ambiguous) { "Ambiguous attachment metadata: $project/$id" }
            val declaredCid = frame.contentId
            val rawCid = if (b.primary) (declaredCid ?: error("Primary document changed while listing: $project/$id"))
                else declaredCid.orEmpty().ifEmpty { "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" }
            val cid = ContentId(rawCid)
            val length = frame.length?.toLongOrNull() ?: 0L
            val type = frame.contentType.orEmpty()
            emit(ProjectDoc(project, id, cid.value, frame.rev, frame.sequence, length, type,
                contentStatus = if (b.primary) "unchecked-metadata" else null))
        }
        currentCoroutineContext().ensureActive()
        if (backing(project)?.store !== b.store) throw ProjectVisitUnavailable(project)
    }

    override suspend fun document(project: String, id: String): ProjectDoc? = withContext(Dispatchers.IO) {
        val b = backing(project) ?: return@withContext null
        val result = document(b, id)
        currentCoroutineContext().ensureActive()
        result
    }

    override suspend fun bytes(project: String, id: String): ByteArray? = withContext(Dispatchers.IO) {
        val b = backing(project) ?: return@withContext null
        verified(b, id)?.second
    }

    override suspend fun read(project: String, id: String, maxChars: Int): ProjectText? = withContext(Dispatchers.IO) {
        val b = backing(project) ?: return@withContext null
        val physical = b.prefix + id
        val metadata = b.store.head.slot(physical) ?: return@withContext null
        if (!ProjectGlob.isTextual(metadata.contentType.orEmpty(), id)) return@withContext null
        val (slot, bytes) = verified(b, id) ?: return@withContext null
        if (!ProjectGlob.isTextual(slot.contentType.orEmpty(), id)) return@withContext null
        // A text larger than asked for is read to its head, never refused: maxChars bounds the answer, not the document.
        val text = bytes.decodeToString(0, minOf(bytes.size.toLong(), 4L * maxChars).toInt())
        currentCoroutineContext().ensureActive()
        // binary decodes to replacement chars at a high rate; OCR/extraction leaves a few unmapped glyphs
        if (text.count { it == '\uFFFD' } * 100 > text.length) return@withContext null
        val result = ProjectText(project, id, requireNotNull(slot.contentId), slot.rev, slot.sequence, text.take(maxChars))
        fun current(): Boolean = backing(project)?.store === b.store && b.store.head.slot(physical) == slot
        if (!current()) return@withContext null
        // The primary miner and its completion/lineage validation are not wired here.
        if (b.primary) return@withContext result
        if (!id.endsWith(ProjectNodes.EXTRACT_SUFFIX)) return@withContext result
        val lineageId = id.removeSuffix(ProjectNodes.EXTRACT_SUFFIX) + ProjectNodes.EXTRACTION_SUFFIX
        val (lineageSlot, lineageBytes) = verified(b, lineageId) ?: return@withContext result.takeIf { current() }
        val lineage = reifyStrict(lineageBytes.decodeToString(throwOnInvalidSequence = true)) as? Map<*, *>
            ?: return@withContext result.takeIf { current() }
        currentCoroutineContext().ensureActive()
        if (lineage["extractedTextCid"] != result.cid) return@withContext result.takeIf { current() }
        val sourceId = id.removeSuffix(ProjectNodes.EXTRACT_SUFFIX)
        val sourcePhysical = b.prefix + sourceId
        val sourceSlot = verified(b, sourceId)?.first ?: return@withContext result.takeIf { current() }
        if (sourceSlot.deleted || sourceSlot.attachmentDeleted || sourceSlot.ambiguous ||
            lineage["originalCid"] != sourceSlot.contentId || lineage["originalRev"] != sourceSlot.rev)
            return@withContext result.takeIf { current() }
        val originId = sourceId + ProjectNodes.EXTRACT_ORIGIN_SUFFIX
        val (originSlot, originBytes) = verified(b, originId) ?: return@withContext result.takeIf { current() }
        val origin = originBytes.decodeToString(throwOnInvalidSequence = true).split('\t')
        if (origin.size != 2 || origin[0] != sourceSlot.contentId || origin[1] != slot.contentId)
            return@withContext result.takeIf { current() }
        val footnotesId = sourceId + ProjectNodes.FOOTNOTES_SUFFIX
        val footnotes = if (sourceId.lowercase().endsWith(".pdf"))
            verified(b, footnotesId) ?: return@withContext result.takeIf { current() } else null
        if (footnotes != null && footnotes.second.isEmpty()) return@withContext result.takeIf { current() }
        if (!current() ||
            b.store.head.slot(b.prefix + lineageId) != lineageSlot || b.store.head.slot(sourcePhysical) != sourceSlot ||
            b.store.head.slot(b.prefix + originId) != originSlot ||
            (footnotes != null && b.store.head.slot(b.prefix + footnotesId) != footnotes.first)) return@withContext null
        result.copy(originalCid = lineage["originalCid"] as? String, originalRev = lineage["originalRev"] as? String,
            anchors = (lineage["anchors"] as? List<*>).orEmpty().map(DocumentAnchor::fromMap),
            unresolved = (lineage["unresolved"] as? List<*>)?.map { it as String } ?: result.unresolved)
    }

    /** The twin when mined; else the miner reads the document now (text layer, else OCR) and the twin is read back. */
    override suspend fun extract(project: String, id: String, maxChars: Int): ProjectText? =
        extracting.computeIfAbsent(project to id) { Mutex() }.withLock {
        // Reading the primary is supported; mining and curation admission remain separate capabilities.
        if (backing(project)?.primary == true) return@withLock null
        val original = document(project, id) ?: return@withLock null
        if (ProjectGlob.isTextual(original.contentType, id) && id.substringAfterLast('.', "").lowercase() !in ProjectMiner.MINEABLE) {
            val text = read(project, id, maxChars) ?: return@withLock null
            if (text.cid != original.cid || text.rev != original.rev || text.seq != original.seq) return@withLock null
            val current = document(project, id) ?: return@withLock null
            if (current.cid != original.cid || current.rev != original.rev || current.seq != original.seq) return@withLock null
            return@withLock text.copy(originalCid = original.cid, originalRev = original.rev)
        }
        val twin = id + ProjectNodes.EXTRACT_SUFFIX
        suspend fun current(): ProjectText? {
            val b = backing(project) ?: return null
            if (b.primary) return null
            val source = verified(b, id)?.first ?: return null
            if (source.contentId != original.cid || source.rev != original.rev || source.sequence != original.seq) return null
            val originId = id + ProjectNodes.EXTRACT_ORIGIN_SUFFIX
            val completionId = id + ProjectNodes.EXTRACTION_SUFFIX
            val footnotesId = id + ProjectNodes.FOOTNOTES_SUFFIX
            val isPdf = id.lowercase().endsWith(".pdf")
            val head = b.store.head
            val originBefore = head.slot(b.prefix + originId)
            val completionBefore = head.slot(b.prefix + completionId)
            val textBefore = head.slot(b.prefix + twin)
            val notesBefore = if (isPdf) head.slot(b.prefix + footnotesId) else null
            val originEvidence = evidence(b, originId)
            val completionEvidence = evidence(b, completionId)
            val textEvidence = evidence(b, twin)
            val footnotes = if (isPdf) evidence(b, footnotesId) else null
            fun unchanged(): Boolean = backing(project)?.store === b.store && head.slot(b.prefix + id) == source &&
                head.slot(b.prefix + originId) == originBefore && head.slot(b.prefix + completionId) == completionBefore &&
                head.slot(b.prefix + twin) == textBefore && (!isPdf || head.slot(b.prefix + footnotesId) == notesBefore)
            fun incomplete(): ProjectText? {
                if (!unchanged()) throw EvidenceChanged()
                return null
            }
            if (!unchanged()) throw EvidenceChanged()
            val (origin, originBytes) = originEvidence ?: return incomplete()
            val (completion, completionBytes) = completionEvidence ?: return incomplete()
            val (textSlot, textBytes) = textEvidence ?: return incomplete()
            if (isPdf && (footnotes == null || footnotes.second.isEmpty())) return incomplete()
            val originParts = originBytes.decodeToString(throwOnInvalidSequence = true).split('\t')
            if (originParts.size != 2 || originParts[0] != original.cid || originParts[1] != textSlot.contentId) return incomplete()
            val lineage = reifyStrict(completionBytes.decodeToString(throwOnInvalidSequence = true)) as? Map<*, *> ?: return incomplete()
            if (lineage["originalCid"] != original.cid || lineage["originalRev"] != original.rev) return incomplete()
            if (lineage["extractedTextCid"] != textSlot.contentId || !ProjectGlob.isTextual(textSlot.contentType.orEmpty(), twin)) return incomplete()
            val text = textBytes.decodeToString(0, minOf(textBytes.size.toLong(), 4L * maxChars).toInt())
            currentCoroutineContext().ensureActive()
            if (text.count { it == '\uFFFD' } * 100 > text.length) return incomplete()
            if (backing(project)?.store !== b.store || b.store.head.slot(b.prefix + id) != source ||
                b.store.head.slot(b.prefix + originId) != origin || b.store.head.slot(b.prefix + completionId) != completion ||
                b.store.head.slot(b.prefix + twin) != textSlot ||
                (footnotes != null && b.store.head.slot(b.prefix + footnotesId) != footnotes.first)) throw EvidenceChanged()
            return ProjectText(project, twin, requireNotNull(textSlot.contentId), textSlot.rev, textSlot.sequence, text.take(maxChars),
                originalCid = original.cid, originalRev = original.rev,
                anchors = (lineage["anchors"] as? List<*>).orEmpty().map(DocumentAnchor::fromMap),
                unresolved = (lineage["unresolved"] as? List<*>)?.map { it as String }
                    ?: listOf("page", "folio", "section", "table", "diagram", "coordinates"))
        }
        try { current()?.let { return@withLock it } } catch (_: EvidenceChanged) { return@withLock null }
        val b = backing(project) ?: return@withLock null
        val source = verified(b, id)?.first ?: return@withLock null
        if (source.contentId != original.cid || source.rev != original.rev || source.sequence != original.seq) return@withLock null
        val mine = ingest ?: return@withLock null
        currentCoroutineContext().ensureActive()
        mine(project, id)
        currentCoroutineContext().ensureActive()
        try { current() } catch (_: EvidenceChanged) { null }
    }
}
