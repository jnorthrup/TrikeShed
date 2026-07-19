package borg.trikeshed.lcnc.reactor

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.nuid.*
import borg.trikeshed.lcnc.ccek.IngestStateElement
import borg.trikeshed.lcnc.isam.LcncEntity
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/**
 * LcncIngestPipeline implementing IngestCodec.decode().
 * Parses IngestSource with IngestFormat, emits Series<LcncEntity>,
 * and wires through IngestStateElement lifecycle with Channel<ReactorAction> fanout.
 */
class LcncIngestPipeline(
    parentJob: Job? = null,
    val ingestId: String,
    override val supportedFormats: Set<IngestFormat> = IngestFormat.entries.toSet()
) : AsyncContextElement(ElementState.CREATED, parentJob), IngestCodec {

    companion object Key : kotlin.coroutines.CoroutineContext.Key<LcncIngestPipeline>
    override val key = Key

    override suspend fun decode(source: IngestSource, format: IngestFormat): Series<LcncEntity> {
        requireState(ElementState.ACTIVE) // Should be active to decode

        // Emulate some parsing (stub for now, until actual parsing is specified)
        // Normally this would parse the source based on the format.
        val entities = mutableListOf<LcncEntity>()
        val entity = object : LcncEntity {
            override val id = "dummy-id"
        }
        entities.add(entity)

        val context = currentCoroutineContext()
        val stateElement = context[IngestStateElement]
        if (stateElement != null) {
            val payload: Any? = entity
            val mockNuid = nuid(Capability.Custom("lcnc", "ingest"), Nonce.RandomBytes(), Subnet.core)
            val action: ReactorAction = mockNuid j ("PUBLISH" j payload)
            stateElement.publishEntity(action)
        }

        return entities.size j { i -> entities[i] }
    }
}
