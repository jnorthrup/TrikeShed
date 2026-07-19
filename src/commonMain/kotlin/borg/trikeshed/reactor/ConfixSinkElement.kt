package borg.trikeshed.reactor

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.couch.isam.ConfixIsamFactory
import borg.trikeshed.couch.isam.ConfixWal
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.job.ConfixFacetPlan
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * CCEK element that consumes facet-typed events from the reactor pipeline
 * and persists them through the unified storage layer (CAS → ISAM → WAL).
 *
 * This is the "projection of reification to the sink" — a reactor element
 * whose input channel carries ConfixFacetPlan-validated columns/rows and
 * whose output is a ContentId (CID) for each committed unit. CID deduplication
 * in CasStore provides structural sharing automatically: identical facet
 * projections → identical bytes → same CID → stored once.
 *
 * Lifecycle:
 *   CREATED → OPEN (validate schema, open stores) → ACTIVE (process events)
 *   → DRAINING (flush WAL, checkpoint ISAM) → CLOSED (close stores)
 *
 * Input: ConfixFacetEvent (facet-projected columns from PolyglotObservationElement,
 * ReteAlphaElement, or direct JobCommand).
 * Output: ContentId for each committed facet projection; causal edges recorded.
 */
class ConfixSinkElement(
    private val casStore: CasStore,
    private val isamFactory: ConfixIsamFactory.ConfixIsamStoreBuilder,
    private val wal: ConfixWal,
    parentJob: Job? = null,
    initialConfig: ConfixSinkConfig = ConfixSinkConfig(),
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    companion object Key : AsyncContextKey<ConfixSinkElement>()

    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key

    private var config: ConfixSinkConfig = initialConfig
    private val _committed = MutableSharedFlow<ContentId>(
        replay = 64,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val committed: SharedFlow<ContentId> = _committed.asSharedFlow()

    private val _errors = MutableSharedFlow<SinkError>(
        replay = 32,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errors: SharedFlow<SinkError> = _errors.asSharedFlow()

    private val inputChannel = Channel<ConfixFacetEvent>(config.channelCapacity)

    /** Submit a facet event for persistence. Non-blocking; returns immediately. */
    fun submit(event: ConfixFacetEvent): Boolean = inputChannel.trySend(event) == ChannelResult.Success

    override suspend fun open() {
        if (state == ElementState.CREATED) {
            super.open()
            // Validate facet plan against ISAM schema
            val bridge = isamFactory.build()
            config.facetPlan.validateAgainst(bridge.schema)
            // Initialize ISAM writer, WAL
            state = ElementState.ACTIVE
            launchProcessor()
        }
    }

    override suspend fun drain() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.DRAINING)) {
            state = ElementState.DRAINING
            inputChannel.close()
            // Flush WAL, checkpoint ISAM
            wal.checkpoint()
            super.drain()
            state = ElementState.CLOSED
        }
    }

    private fun launchProcessor() {
        supervisor.launch {
            for (event in inputChannel) {
                try {
                    val cid = processEvent(event)
                    _committed.emit(cid)
                } catch (e: Throwable) {
                    _errors.emit(SinkError(event, e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    /**
     * Process a single facet event:
     * 1. Reify facet columns to bytes via ConfixIndex (lazy, no full doc)
     * 2. CasStore.put(bytes) → ContentId (CID dedup is automatic)
     * 3. Append WalFrame with CID + facet metadata
     * 4. Update ISAM index via ConfixIsamFactory bridge
     * 5. Emit CID for downstream causal graph
     */
    private suspend fun processEvent(event: ConfixFacetEvent): ContentId {
        val facetBytes = event.facetProjection.toCanonicalCbor()
        val cid = casStore.put(facetBytes)

        // WAL frame: operation + CID + facet plan ID for replay
        wal.append(cid.value, "1", event.facetProjection.toConfixDoc())

        // ISAM index update — uses the real isomorphism when implemented
        // Currently throws NotImplementedError per ConfixIsamIsomorphism
        isamFactory.build().indexFacet(cid, event.facetProjection)

        return cid
    }

    fun updateConfig(newConfig: ConfixSinkConfig) {
        config = newConfig
        // Re-validate on config change
        if (state == ElementState.ACTIVE) {
            val bridge = isamFactory.build()
            config.facetPlan.validateAgainst(bridge.schema)
        }
    }
}

/** A facet-projected column set submitted for persistence. */
@Serializable
data class ConfixFacetEvent(
    val facetProjection: FacetProjection,
    val timestampMs: Long,
    val traceId: String,
)

/** Facet projection carrying columnar data matching ConfixFacetPlan schema. */
@Serializable
data class FacetProjection(
    val columns: Map<String, ColumnData>,
) {
    suspend fun toCanonicalCbor(): ByteArray = TODO("Canonical CBOR encoding of facet columns")

    fun toConfixDoc() = TODO("Build ConfixDoc from facet columns for WAL")
}

/** Column data variants matching IOMemento primitive types. */
@Serializable
sealed class ColumnData {
    @Serializable
    data class StringColumn(val values: Array<String>) : ColumnData()
    @Serializable
    data class IntColumn(val values: Array<Int>) : ColumnData()
    @Serializable
    data class LongColumn(val values: Array<Long>) : ColumnData()
    @Serializable
    data class DoubleColumn(val values: Array<Double>) : ColumnData()
    @Serializable
    data class BooleanColumn(val values: Array<Boolean>) : ColumnData()
    @Serializable
    data class BytesColumn(val values: Array<ByteArray>) : ColumnData()
}

/** Configuration for the sink element. */
@Serializable
data class ConfixSinkConfig(
    @Contextual val facetPlan: ConfixFacetPlan = ConfixFacetPlan(
        commandOperations = emptySet(),
        eventOperations = emptySet(),
        requiredFields = emptySet(),
        schemaText = "{}",
    ),
    val channelCapacity: Int = 512,
    val batchSize: Int = 1,
)

/** Error emitted when a facet event fails to persist. */
@Serializable
data class SinkError(
    val event: ConfixFacetEvent,
    val message: String,
    val timestampMs: Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
)