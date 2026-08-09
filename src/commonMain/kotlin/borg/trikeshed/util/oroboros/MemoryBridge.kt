package borg.trikeshed.util.oroboros

import borg.trikeshed.cas.IpfsBridge
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.memory.memoryFile

/**
 * Projects memory-eligible working-tree attachments into [MemoryStore].
 *
 * [WorktreeCouchGateway] first places every source/document blob under Couch
 * metadata backed by CAS. This bridge then gives Markdown files the deeper
 * memory treatment: per-line CAS spines, line index ingestion, logical memory
 * metadata, and IPNS publication of the spine CID.
 *
 * Source attachments and memory projections use different Couch IDs. The
 * attachment remains at `projects/trikeshed/<relative>`; its projection lives
 * at `/memories/projects/trikeshed/<relative>`. This prevents memory metadata
 * from overwriting the source attachment's revision and content type.
 */
class MemoryBridge(
    private val memoryStore: MemoryStore,
    private val attachments: CouchAttachmentGateway,
    private val ipfsBridge: IpfsBridge,
) {
    fun bridge(snapshot: WorktreeCouchGateway.Snapshot, agentId: String): Int {
        var bridged = 0
        for (attachmentPath in snapshot.deletedPaths) {
            if (!isMemoryEligible(attachmentPath)) continue
            val relative = attachmentPath.removePrefix(WorktreeCouchGateway.WORKTREE_PREFIX)
            val memoryPath = "/memories/projects/trikeshed/$relative"
            if (memoryStore.delete(memoryPath)) bridged++
            ipfsBridge.unpublishIpns("memory:$memoryPath")
        }
        for (attachmentPath in snapshot.paths) {
            if (!isMemoryEligible(attachmentPath)) continue
            val stored = attachments.getAttachment(attachmentPath) ?: continue
            val relative = attachmentPath.removePrefix(WorktreeCouchGateway.WORKTREE_PREFIX)
            val memoryPath = "/memories/projects/trikeshed/$relative"
            val bytes = stored.second
            val currentCid = memoryStore.couch.get(memoryPath)
                ?.fields
                ?.find { it.name == "contentId" }
                ?.value as? String
            if (currentCid == stored.first.contentId.value) continue
            val description = extractDescription(bytes.decodeToString(), relative)

            memoryStore.put(
                memoryFile(memoryPath, description, bytes),
                agentId = agentId,
                kind = "repository-document",
            )
            val spineCid = memoryStore.spineCidOf(memoryPath) ?: continue
            ipfsBridge.publishIpns("memory:$memoryPath", spineCid)
            bridged++
        }
        return bridged
    }

    fun isMemoryEligible(path: String): Boolean =
        path.endsWith(".md", ignoreCase = true) ||
            path.endsWith(".markdown", ignoreCase = true)

    private fun extractDescription(content: String, path: String): String {
        val lines = content.lines()
        if (lines.firstOrNull()?.trim() == "---") {
            for (line in lines.drop(1)) {
                if (line.trim() == "---") break
                if (line.startsWith("description:")) {
                    return line.removePrefix("description:").trim()
                }
            }
        }
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("# ") && trimmed.length > 2) {
                return trimmed.removePrefix("# ").trim()
            }
        }
        return path.substringAfterLast('/').substringBeforeLast('.').replace('-', ' ')
    }
}
