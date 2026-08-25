package borg.trikeshed.memory

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.view
import borg.trikeshed.util.oroboros.CouchAttachmentGateway

/**
 * Direct projection from reconciled Couch attachments into the repository
 * taxonomy route. Raw repository blobs remain distinct from MemoryStore
 * documents and incur no line-spine rewrite.
 */
class CouchIndexBridge(
    private val gateway: CouchAttachmentGateway,
    private val indexLayer: MemoryIndexLayer,
) {
    fun indexReconciliation(
        prefix: String,
        paths: Series<String>,
        /** Project dbs index through their OWN gateway; default = the primary. */
        via: CouchAttachmentGateway = gateway,
        /** Maps the INDEXED path to the doc id in [via] (project dbs strip their name prefix). */
        docId: (String) -> String = { it },
    ) {
        val entries = mutableListOf<CouchIndexEntry>()
        for (path in paths.view) {
            val stored = via.getAttachment(docId(path)) ?: continue
            val segments = path.split('/').filter(String::isNotEmpty)
            val taxonomySize = (segments.size - 1).coerceAtLeast(1)
            val taxonomy = taxonomySize j { i: Int ->
                if (segments.size > 1) segments[i] else "root"
            }
            entries.add(
                CouchIndexEntry(
                    path = path,
                    hash = stored.first.contentId,
                    taxonomy = taxonomy,
                    timestamp = stored.first.sequence,
                )
            )
        }
        indexLayer.replaceRepositoryBatch(prefix, entries.size j { i: Int -> entries[i] })
    }
}