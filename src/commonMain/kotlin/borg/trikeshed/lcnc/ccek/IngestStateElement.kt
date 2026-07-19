package borg.trikeshed.lcnc.ccek

import borg.trikeshed.lcnc.isam.LcncEntity
import borg.trikeshed.lib.Series
import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.lcnc.reactor.ReactorAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
    val ingestId: String,
    parentJob: Job? = null
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    // Fanout channel for reactor actions, replacing mutableListOf accumulator
    val fanout = Channel<ReactorAction>(Channel.BUFFERED)

    /**
     * Called by codecs to publish a newly parsed entity into the context scope.
     */
    suspend fun publishEntity(action: ReactorAction) {
        fanout.send(action)
    }

    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<IngestStateElement>
}

/**
 * Helper to easily emit entities from within an ingestion coroutine context.
 */
suspend inline fun publishIngestedEntity(
    context: CoroutineContext,
    action: ReactorAction
) {
    context[IngestStateElement]?.publishEntity(action)
}
