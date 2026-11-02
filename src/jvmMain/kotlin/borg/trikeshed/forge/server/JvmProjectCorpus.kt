package borg.trikeshed.forge.server

import borg.trikeshed.lcnc.ProjectCorpus
import borg.trikeshed.lcnc.ProjectDoc
import borg.trikeshed.lcnc.ProjectGlob
import borg.trikeshed.lcnc.ProjectNodes
import borg.trikeshed.lcnc.ProjectRef
import borg.trikeshed.lcnc.ProjectText
import borg.trikeshed.couch.CouchStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.job.BoundedCasReader
import borg.trikeshed.narsese.DocumentAnchor
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

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
<<<<<<< HEAD
=======
    internal fun canRetainPrimaryText(row: ProjectDoc): Boolean {
        val b = backing(row.project) ?: return false
        return b.primary && b.reader is borg.trikeshed.job.PackCasStore && row.contentStatus == "verified-bytes" &&
            row.length in 0..(4L * 1024 * 1024) && row.rev.isNotBlank() && row.seq >= 0 &&
            row.id.substringAfterLast('.', "").lowercase() in setOf("txt", "md")
    }
>>>>>>> origin/codex/primary-manual-curation-20261102
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

    /** Primary worktrees are read-only here; only mounted project databases support intake. */
    internal fun intakeEligible(project: String): Boolean = project != primary?.name && registry.get(project) != null

    private val extracting = ConcurrentHashMap<Pair<String, String>, Mutex>()
    private data class Backing(val project: String, val store: CouchStore, val gateway: CouchAttachmentGateway,
        val prefix: String, val primary: Boolean, val reader: BoundedCasReader? = null, val maxBytes: Int = 0)
    private data class Stamp(val rev: String, val seq: Long)

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

    private fun stamp(backing: Backing, physical: String): Stamp? {
        val head = backing.store.head
        if (head.isDeleted(physical)) return null
        val rev = head.getRev(physical) ?: return null
        val seq = head.sequenceOf(physical) ?: return null
        return Stamp(rev, seq)
    }

    /** Verify the per-document head did not change across the primary attachment read. */
    private fun stillCurrent(b: Backing, physical: String, expected: Stamp): Boolean =
        (!b.primary || backing(b.project) != null) && stamp(b, physical) == expected

    /** The reader, not attachment metadata, enforces the allocation bound and verifies the CID. */
    private fun primaryBytes(b: Backing, ref: borg.trikeshed.util.oroboros.OroborosAttachmentRef): ByteArray? {
        if (ref.length < 0 || ref.length > b.maxBytes) return null
        val reader = b.reader ?: return null
        val bytes = try { reader.getBounded(ref.contentId, b.maxBytes) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return null }
        return bytes?.takeIf { it.size <= b.maxBytes && it.size.toLong() == ref.length && ContentId.of(it) == ref.contentId }
    }

    /** Do not treat missing or malformed declared lengths as a verified empty attachment. */
    private fun primaryRef(b: Backing, physical: String): borg.trikeshed.util.oroboros.OroborosAttachmentRef? {
        val ref = b.gateway.getAttachmentRef(physical) ?: return null
        val declared = (b.store.get(physical)?.fields?.find { it.name == "length" }?.value as? String)?.toLongOrNull()
        return ref.takeIf { declared != null && declared >= 0 && declared == it.length }
    }

    private fun document(backing: Backing, id: String, verifyBytes: Boolean = true): ProjectDoc? {
        val physical = backing.prefix + id
        if (!backing.primary) {
            if (backing.store.head.isDeleted(physical)) return null
            val ref = backing.gateway.getAttachmentRef(physical) ?: return null
            val head = backing.store.head
            return ProjectDoc(backing.project, id, ref.contentId.value, head.getRev(physical).orEmpty(),
                head.sequenceOf(physical) ?: -1L, ref.length, ref.contentType)
        }
        val before = stamp(backing, physical) ?: return null
        val ref = (if (verifyBytes) primaryRef(backing, physical) else backing.gateway.getAttachmentRef(physical)) ?: return null
        if (verifyBytes && primaryBytes(backing, ref) == null) return null
        if (!stillCurrent(backing, physical, before)) return null
        return ProjectDoc(backing.project, id, ref.contentId.value, before.rev, before.seq, ref.length, ref.contentType,
            contentStatus = if (verifyBytes) "verified-bytes" else "unchecked-metadata")
    }

    override suspend fun projects(): List<ProjectRef> = scopes.list().map {
        if (primary?.name == it.name) backing(it.name)
        ProjectRef(it.name, it.kind, it.path, registry.get(it.name)?.docCount ?: it.docs)
    }

    override suspend fun docs(project: String, prefix: String, glob: String, limit: Int): List<ProjectDoc> = withContext(Dispatchers.IO) {
        val b = backing(project) ?: return@withContext emptyList()
        val head = b.store.head
        b.gateway.listAttachments(b.prefix + prefix).asSequence()
            .filter { !head.isDeleted(it.path) && !ProjectNodes.twin(it.path.removePrefix(b.prefix)) && ProjectGlob.matches(glob, it.path.removePrefix(b.prefix)) }
            .sortedBy { it.path }
            .take(limit)
            // seq is the store's own commit sequence (0-based, as `_changes` numbers it), never the
            // attachment doc's `sequence` field, which writers stamp with a clock or a count. A listed
            // document always has a head frame; -1 marks the impossible unknown rather than a lie.
            .map {
                val id = it.path.removePrefix(b.prefix)
                if (b.primary) document(b, id, verifyBytes = false) ?: error("Primary document changed while listing: $project/$id")
                else ProjectDoc(project, id, it.contentId.value, head.getRev(it.path).orEmpty(), head.sequenceOf(it.path) ?: -1L, it.length, it.contentType)
            }
            .toList()
    }

    override suspend fun document(project: String, id: String): ProjectDoc? = withContext(Dispatchers.IO) {
        val b = backing(project) ?: return@withContext null
        document(b, id)
    }

    override suspend fun bytes(project: String, id: String): ByteArray? = withContext(Dispatchers.IO) {
        val b = backing(project) ?: return@withContext null
        val physical = b.prefix + id
        if (b.store.head.isDeleted(physical)) return@withContext null
        val before = if (b.primary) stamp(b, physical) ?: return@withContext null else null
        val bytes = if (b.primary) {
            val ref = primaryRef(b, physical) ?: return@withContext null
            primaryBytes(b, ref) ?: return@withContext null
        } else b.gateway.getAttachment(physical)?.second ?: return@withContext null
        if (before != null && !stillCurrent(b, physical, before)) return@withContext null
        bytes
    }

    override suspend fun read(project: String, id: String, maxChars: Int): ProjectText? = withContext(Dispatchers.IO) {
        val b = backing(project) ?: return@withContext null
        val physical = b.prefix + id
        if (b.store.head.isDeleted(physical)) return@withContext null
        val before = if (b.primary) stamp(b, physical) ?: return@withContext null else null
        if (before != null) {
            val metadata = b.gateway.getAttachmentRef(physical) ?: return@withContext null
            if (!ProjectGlob.isTextual(metadata.contentType, id) || !stillCurrent(b, physical, before)) return@withContext null
        }
        val (ref, bytes) = if (b.primary) {
            val ref = primaryRef(b, physical) ?: return@withContext null
            Pair(ref, primaryBytes(b, ref) ?: return@withContext null)
        } else b.gateway.getAttachment(physical) ?: return@withContext null
        if (!ProjectGlob.isTextual(ref.contentType, id)) return@withContext null
        // A text larger than asked for is read to its head, never refused: maxChars bounds the answer, not the document.
        val text = bytes.decodeToString(0, minOf(bytes.size.toLong(), 4L * maxChars).toInt())
        // binary decodes to replacement chars at a high rate; OCR/extraction leaves a few unmapped glyphs
        if (text.count { it == '\uFFFD' } * 100 > text.length) return@withContext null
        val head = b.store.head
        if (before != null && !stillCurrent(b, physical, before)) return@withContext null
        val result = ProjectText(project, id, ref.contentId.value, before?.rev ?: head.getRev(physical).orEmpty(),
            before?.seq ?: head.sequenceOf(physical) ?: -1L, text.take(maxChars))
        // The primary miner and its completion/lineage validation are not wired here.
        if (b.primary) return@withContext result
        if (!id.endsWith(ProjectNodes.EXTRACT_SUFFIX)) return@withContext result
        val lineageBytes = b.gateway.getAttachment(id.removeSuffix(ProjectNodes.EXTRACT_SUFFIX) + ProjectNodes.EXTRACTION_SUFFIX)?.second
            ?: return@withContext result
        val lineage = reifyStrict(lineageBytes.decodeToString(throwOnInvalidSequence = true)) as? Map<*, *> ?: return@withContext result
        if (lineage["extractedTextCid"] != result.cid) return@withContext result
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
        if (ProjectGlob.isTextual(original.contentType, id) && id.substringAfterLast('.', "").lowercase() !in ProjectMiner.MINEABLE)
            return@withLock read(project, id, maxChars)?.copy(
            originalCid = original.cid, originalRev = original.rev)
        val twin = id + ProjectNodes.EXTRACT_SUFFIX
        suspend fun current(): ProjectText? {
            val pdb = registry.get(project) ?: return null
            if (!ProjectMiner.currentExtraction(pdb, id)) return null
            val completion = read(project, id + ProjectNodes.EXTRACTION_SUFFIX, Int.MAX_VALUE / 4) ?: return null
            val lineage = reifyStrict(completion.text) as? Map<*, *> ?: return null
            if (lineage["originalCid"] != original.cid || lineage["originalRev"] != original.rev) return null
            val text = read(project, twin, maxChars) ?: return null
            if (lineage["extractedTextCid"] != text.cid) return null
            val source = document(project, id) ?: return null
            if (source.cid != original.cid || source.rev != original.rev || !ProjectMiner.currentExtraction(pdb, id) ||
                pdb.gateway.getAttachmentRef(twin)?.contentId?.value != text.cid) return null
            return text.copy(originalCid = original.cid, originalRev = original.rev,
                anchors = (lineage["anchors"] as? List<*>).orEmpty().map(DocumentAnchor::fromMap),
                unresolved = (lineage["unresolved"] as? List<*>)?.map { it as String }
                    ?: listOf("page", "folio", "section", "table", "diagram", "coordinates"))
        }
        current()?.let { return@withLock it }
        val mine = ingest ?: return@withLock null
        mine(project, id)
        current()
    }
}
