package org.xvm.activejs.ccek

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.CoroutineContext

/**
 * CCEK SPI Elements for Pointcut Event Transport.
 * 
 * Replaces Channel<PointcutEvent> + CoroutineScope fanout with zero-copy
 * CCEK bus via NioSupervisor service registry.
 */
interface PointcutEventProducer : CoroutineContext.Element {
    companion object Key : AsyncContextKey<PointcutEventProducer>()
    override val key: CoroutineContext.Key<*> get() = Key
    
    /** Emit a field synapse record to all registered consumers. */
    fun emit(synapse: FieldSynapse)
    
    /** Register a consumer for pointcut events. */
    fun registerConsumer(consumer: PointcutEventConsumer)
    
    /** Unregister a consumer. */
    fun unregisterConsumer(consumer: PointcutEventConsumer)
}

interface PointcutEventConsumer : CoroutineContext.Element {
    companion object Key : AsyncContextKey<PointcutEventConsumer>()
    override val key: CoroutineContext.Key<*> get() = Key
    
    /** Called when a pointcut event is emitted. */
    fun onEvent(synapse: FieldSynapse)
}

/**
 * Synapse record for field pointcut events (P_GET, P_SET, L_GET, L_SET).
 * Wireproto record (24 bytes, little-endian):
 *   offset  0: opcode       u8
 *   offset  1: phase        u8     — 0=BEFORE, 1=AFTER
 *   offset  2: methodIdx    u16    — InternPool index
 *   offset  4: addr         i32
 *   offset  8: seq          i32
 *   offset 12: nano         i64
 *   offset 20: callsiteHash u16
 *   offset 22: templateIdx  u16
 */
data class FieldSynapse(
    val phase: Byte,         // 0=BEFORE, 1=AFTER
    val opcode: Byte,        // 0xA5=L_GET, 0xA6=L_SET, 0xA7=P_GET, 0xA8=P_SET
    val methodIdx: Int,      // InternPool index
    val addr: Int,           // PC address
    val seq: Int,            // monotonic sequence
    val nano: Long,          // System.nanoTime() at publish
    val callsiteHash: Int,   // FNV-1a hash of (opcode, methodIdx, addr)
    val templateIdx: Int,    // InternPool index of format template
)

/**
 * Concrete implementation of PointcutEventProducer.
 * Registered in NioSupervisor for CCEK SPI resolution.
 */
class PointcutEventProducerImpl : AsyncContextElement(), PointcutEventProducer {
    private val consumers = mutableListOf<PointcutEventConsumer>()
    
    override suspend fun open() {
        super.open()
        state = ElementState.ACTIVE
    }
    
    override fun close() {
        consumers.clear()
        super.close()
    }
    
    override fun emit(synapse: FieldSynapse) {
        // Zero-copy fanout: direct call to each consumer
        for (consumer in consumers) {
            consumer.onEvent(synapse)
        }
    }
    
    override fun registerConsumer(consumer: PointcutEventConsumer) {
        if (consumer !in consumers) consumers += consumer
    }
    
    override fun unregisterConsumer(consumer: PointcutEventConsumer) {
        consumers -= consumer
    }
}

/**
 * Default implementation of PointcutEventConsumer.
 * Can be subclassed for custom behavior.
 */
open class PointcutEventConsumerImpl(private val producer: PointcutEventProducer) : AsyncContextElement(), PointcutEventConsumer {
    override suspend fun open() {
        super.open()
        state = ElementState.ACTIVE
    }
    
    override fun close() {
        producer.unregisterConsumer(this)
        super.close()
    }
    
    override fun onEvent(synapse: FieldSynapse) {
        // Base implementation does nothing; override in subclasses
    }
}

/**
 * Helper to resolve PointcutEventProducer from coroutine context.
 */
fun CoroutineContext.getPointcutEventProducer(): PointcutEventProducer? =
    this[PointcutEventProducer.Key]

/**
 * Helper to resolve PointcutEventConsumer from coroutine context.
 */
fun CoroutineContext.getPointcutEventConsumer(): PointcutEventConsumer? =
    this[PointcutEventConsumer.Key]

/**
 * CCEK SPI Elements for Confix Observation Transport.
 * 
 * Replaces Channel<BlackBoardEntry> + manual coroutine launch with
 * CCEK bus fanout for Confix OBSERVATION entries.
 */
interface ConfixObservationProducer : CoroutineContext.Element {
    companion object Key : AsyncContextKey<ConfixObservationProducer>()
    override val key: CoroutineContext.Key<*> get() = Key
    
    fun emit(entry: org.xvm.activejs.BlackBoardEntry)
    fun registerConsumer(consumer: ConfixObservationConsumer)
    fun unregisterConsumer(consumer: ConfixObservationConsumer)
}

interface ConfixObservationConsumer : CoroutineContext.Element {
    companion object Key : AsyncContextKey<ConfixObservationConsumer>()
    override val key: CoroutineContext.Key<*> get() = Key
    
    fun onObservation(entry: org.xvm.activejs.BlackBoardEntry)
}

class ConfixObservationProducerImpl : AsyncContextElement(), ConfixObservationProducer {
    private val consumers = mutableListOf<ConfixObservationConsumer>()
    
    override suspend fun open() {
        super.open()
        state = ElementState.ACTIVE
    }
    
    override fun close() {
        consumers.clear()
        super.close()
    }
    
    override fun emit(entry: org.xvm.activejs.BlackBoardEntry) {
        for (consumer in consumers) {
            consumer.onObservation(entry)
        }
    }
    
    override fun registerConsumer(consumer: ConfixObservationConsumer) {
        if (consumer !in consumers) consumers += consumer
    }
    
    override fun unregisterConsumer(consumer: ConfixObservationConsumer) {
        consumers -= consumer
    }
}

fun CoroutineContext.getConfixObservationProducer(): ConfixObservationProducer? =
    this[ConfixObservationProducer.Key]

fun CoroutineContext.getConfixObservationConsumer(): ConfixObservationConsumer? =
    this[ConfixObservationConsumer.Key]

/**
 * CCEK SPI for Taxonomy Observer.
 * 
 * Replaces Delegates.observable on rows MutableSeries with CCEK facet-driven
 * notification via ObserverDelegateRegistration.
 */
interface TaxonomyObserver : CoroutineContext.Element {
    companion object Key : AsyncContextKey<TaxonomyObserver>()
    override val key: CoroutineContext.Key<*> get() = Key
    
    fun onRowRegistered(row: org.xvm.activejs.CoordinateRow)
    fun onRowUpdated(row: org.xvm.activejs.CoordinateRow, old: org.xvm.activejs.CoordinateRow)
    fun onRowRemoved(row: org.xvm.activejs.CoordinateRow)
}

class TaxonomyObserverImpl : AsyncContextElement(), TaxonomyObserver {
    override suspend fun open() {
        super.open()
        state = ElementState.ACTIVE
    }
    
    override fun onRowRegistered(row: org.xvm.activejs.CoordinateRow) = Unit
    override fun onRowUpdated(row: org.xvm.activejs.CoordinateRow, old: org.xvm.activejs.CoordinateRow) = Unit
    override fun onRowRemoved(row: org.xvm.activejs.CoordinateRow) = Unit
}

fun CoroutineContext.getTaxonomyObserver(): TaxonomyObserver? =
    this[TaxonomyObserver.Key]
