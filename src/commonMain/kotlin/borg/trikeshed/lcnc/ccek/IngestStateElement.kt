package borg.trikeshed.lcnc.ccek

import borg.trikeshed.lcnc.isam.NotionEntity
import borg.trikeshed.lib.Series
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * CCEK (Coroutine -> Context -> Element -> Key) implementations for the LCNC ingest pipeline.
 * These act as the state and fanout bones, binding execution scope and managing the lifecycle
 * of parsed entities.
 */

/**
 * A Context Element that holds the state of the current ingestion process.
 * Can be used by codecs to publish parsed entities, and by the reactor to fan out updates.
 */
class IngestStateElement(
    val ingestId: String
) : AbstractCoroutineContextElement(Key) {

    // Internal state holding the parsed entities so far.
    // In a real system, this might use a mutable thread-safe structure or Channel.
    private val parsedEntities = mutableListOf<NotionEntity>()

    /**
     * Called by codecs to publish a newly parsed entity into the context scope.
     */
    fun publishEntity(entity: NotionEntity) {
        parsedEntities.add(entity)
        // Here we could fan out to a PointcutEventProducer or other bus
    }

    /**
     * Retrieves all entities parsed within this context.
     */
    fun getEntities(): List<NotionEntity> = parsedEntities.toList()

    companion object Key : CoroutineContext.Key<IngestStateElement>
}

/**
 * Helper to easily emit entities from within an ingestion coroutine context.
 */
suspend inline fun publishIngestedEntity(
    context: CoroutineContext,
    entity: NotionEntity
) {
    context[IngestStateElement]?.publishEntity(entity)
}
