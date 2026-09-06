package borg.trikeshed.forge.server

import borg.trikeshed.lcnc.ProjectCorpus
import borg.trikeshed.lcnc.ProjectDoc
import borg.trikeshed.lcnc.ProjectGlob
import borg.trikeshed.lcnc.ProjectNodes
import borg.trikeshed.lcnc.ProjectRef
import borg.trikeshed.lcnc.ProjectText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
) : ProjectCorpus {

    override suspend fun projects(): List<ProjectRef> =
        scopes.list().map { ProjectRef(it.name, it.kind, it.path, registry.get(it.name)?.docCount ?: it.docs) }

    override suspend fun docs(project: String, prefix: String, glob: String, limit: Int): List<ProjectDoc> = withContext(Dispatchers.IO) {
        val pdb = registry.get(project) ?: return@withContext emptyList()
        val head = pdb.store.head
        pdb.gateway.listAttachments(prefix).asSequence()
            .filter { !it.path.endsWith(ProjectNodes.EXTRACT_SUFFIX) && ProjectGlob.matches(glob, it.path) }
            .sortedBy { it.path }
            .take(limit)
            // seq is the store's own commit sequence (0-based, as `_changes` numbers it), never the
            // attachment doc's `sequence` field, which writers stamp with a clock or a count. A listed
            // document always has a head frame; -1 marks the impossible unknown rather than a lie.
            .map { ProjectDoc(project, it.path, it.contentId.value, head.getRev(it.path).orEmpty(), head.sequenceOf(it.path) ?: -1L, it.length, it.contentType) }
            .toList()
    }

    override suspend fun read(project: String, id: String, maxChars: Int): ProjectText? = withContext(Dispatchers.IO) {
        val pdb = registry.get(project) ?: return@withContext null
        val (ref, bytes) = pdb.gateway.getAttachment(id) ?: return@withContext null
        if (!ProjectGlob.isTextual(ref.contentType, id)) return@withContext null
        if (bytes.size > 4L * maxChars) return@withContext null
        val text = bytes.decodeToString()
        if ('�' in text) return@withContext null
        val head = pdb.store.head
        ProjectText(project, id, ref.contentId.value, head.getRev(id).orEmpty(), head.sequenceOf(id) ?: -1L, text.take(maxChars))
    }
}
