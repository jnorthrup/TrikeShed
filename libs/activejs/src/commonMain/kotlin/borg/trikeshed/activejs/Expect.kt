package borg.trikeshed.activejs

import kotlin.coroutines.CoroutineContext

/**
 * ActiveJS — GraalVM ECMA launcher with pointcut integration.
 * 
 * Single responsibility: launch GraalVM Polyglot context (JS) and install
 * pointcut hooks that feed events into the local TrikeShed system.
 */
interface GraalEcmaLauncher {
    /**
     * Initialize and return a GraalVM Polyglot Context configured for pointcutting.
     * The context has pointcut hooks installed that emit to the local CCEK bus.
     */
    fun initialize(): GraalEcmaContext
    
    /** Shutdown the GraalVM context and clean up resources. */
    fun shutdown()
}

/** Wrapper around GraalVM Polyglot Context with pointcut hooks installed. */
interface GraalEcmaContext {
    /** The underlying GraalVM Polyglot Context. */
    val polyglotContext: Any
    
    /** Evaluate JavaScript code in the context. */
    fun eval(script: String): Any
    
    /** Get a binding from the global scope. */
    fun getBinding(name: String): Any?
    
    /** Set a binding in the global scope. */
    fun putBinding(name: String, value: Any)
    
    /** Install pointcut hooks for a specific target class/object. */
    fun installPointcutHooks(target: Any, eventHandler: (PointcutEvent) -> Unit)
}

/** Pointcut event emitted from GraalVM ECMA context. */
data class PointcutEvent(
    val seq: Long,
    val nano: Long,
    val opcode: Int,
    val phase: String,    // "BEFORE" or "AFTER"
    val target: String,   // target identifier (class#method, field name, etc.)
    val value: Any?,      // field value for L_GET/P_GET/L_SET/P_SET
)

/**
 * Pointcut opcodes for pointcut kinds
 */
object PointcutOpcode {
    const val L_GET = 0xA5
    const val L_SET = 0xA6
    const val P_GET = 0xA7
    const val P_SET = 0xA8
    const val CALL = 0x10
    const val NVOK = 0x20
    const val CONSTR = 0x34
    const val RETURN = 0x4C
}

/**
 * CCEK SPI for Pointcut Event Transport (local to TrikeShed).
 * Replaces xvm Channel with zero-copy CCEK bus via NioSupervisor.
 */
interface PointcutEventProducer : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<PointcutEventProducer>
    fun emit(event: PointcutEvent)
    fun registerConsumer(consumer: PointcutEventConsumer)
    fun unregisterConsumer(consumer: PointcutEventConsumer)
}

interface PointcutEventConsumer : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<PointcutEventConsumer>
    fun onEvent(event: PointcutEvent)
}

/**
 * Factory for creating platform-specific PointcutEventProducer/Consumer implementations.
 * On JVM, returns implementations that integrate with CoroutineContext.
 * On other platforms, returns no-op implementations.
 */
expect object PointcutEventFactory {
    fun createProducer(): PointcutEventProducer
    fun createConsumer(producer: PointcutEventProducer): PointcutEventConsumer
    fun getProducer(): PointcutEventProducer?
    fun getConsumer(): PointcutEventConsumer?
}

/**
 * Default no-op implementations for platforms without CoroutineContext support.
 */
class NoOpPointcutEventProducer : PointcutEventProducer {
    override val key: CoroutineContext.Key<PointcutEventProducer> get() = Key
    companion object Key : CoroutineContext.Key<PointcutEventProducer>
    override fun emit(event: PointcutEvent) {}
    override fun registerConsumer(consumer: PointcutEventConsumer) {}
    override fun unregisterConsumer(consumer: PointcutEventConsumer) {}
}

class NoOpPointcutEventConsumer(private val producer: PointcutEventProducer) : PointcutEventConsumer {
    override val key: CoroutineContext.Key<PointcutEventConsumer> get() = Key
    companion object Key : CoroutineContext.Key<PointcutEventConsumer>
    override fun onEvent(event: PointcutEvent) {}
}