package borg.trikeshed.forge.server

import borg.trikeshed.lcnc.ProjectCorpus
import borg.trikeshed.lcnc.ProjectDoc
import borg.trikeshed.lcnc.ProjectGlob
import borg.trikeshed.lcnc.ProjectNodes
import borg.trikeshed.lcnc.ProjectRef
import borg.trikeshed.lcnc.ProjectText
import borg.trikeshed.couch.CouchStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.narsese.DocumentAnchor
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** An explicitly bound primary worktree, stored under a physical prefix in the daemon's main store. */
data class PrimaryProjectBinding(val name: String, val prefix: String, val store: CouchStore,
    val gateway: CouchAttachmentGateway, val path: String) {
    init {
        require(name.isNotBlank() && '/' !in name && '\u0000' !in name)
        require(prefix == "projects/$name/") { "Primary prefix must identify the bound project" }
        require(gateway.uses(store)) { "Primary gateway and store must share a backing store" }
        require(path.isNotBlank()) { "Primary project path is required" }
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
    private val extracting = ConcurrentHashMap<Pair<String, String>, Mutex>()
    private data class Backing(val project: String, val store: CouchStore, val gateway: CouchAttachmentGateway,
        val prefix: String, val primary: Boolean)
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
            return Backing(project, binding.store, binding.gateway, binding.prefix, true)
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
        val ref = if (verifyBytes) {
            val (ref, bytes) = backing.gateway.getAttachment(physical) ?: return null
            if (ContentId.of(bytes) != ref.contentId) return null
            ref
        } else backing.gateway.getAttachmentRef(physical) ?: return null
        if (!stillCurrent(backing, physical, before)) return null
        return ProjectDoc(backing.project, id, ref.contentId.value, before.rev, before.seq, ref.length, ref.contentType)
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
        val (ref, bytes) = b.gateway.getAttachment(physical) ?: return@withContext null
        if (b.primary && ContentId.of(bytes) != ref.contentId) return@withContext null
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
        val (ref, bytes) = b.gateway.getAttachment(physical) ?: return@withContext null
        if (b.primary && ContentId.of(bytes) != ref.contentId) return@withContext null
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
