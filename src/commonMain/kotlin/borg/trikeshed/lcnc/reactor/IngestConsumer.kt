package borg.trikeshed.lcnc.reactor

import borg.trikeshed.context.nuid.Nuid
import borg.trikeshed.lcnc.isam.LcncBlock
import borg.trikeshed.lcnc.isam.LcncDatabase
import borg.trikeshed.lcnc.isam.LcncEntity
import borg.trikeshed.lcnc.ccek.IngestStateElement
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Consumes the ingested entities and writes them to a theoretical store.
 */
class IngestConsumer(val ingestStateElement: IngestStateElement) {
    
    val savedDatabases = mutableListOf<LcncDatabase>()
    val savedBlocks = mutableListOf<LcncBlock>()
    
    suspend fun startConsuming() {
        for (action in ingestStateElement.fanout) {
            when (action) {
                is ReactorAction.PublishEntity -> {
                    val entity = action.entity
                    when (entity) {
                        is LcncDatabase -> savedDatabases.add(entity)
                        is LcncBlock -> savedBlocks.add(entity)
                        // Other entities can be handled here
                    }
                }
                is ReactorAction.Closed -> {
                    // Finished
                }
                else -> {
                    // Log or handle other lifecycle events
                }
            }
        }
    }
}
