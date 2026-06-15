package borg.trikeshed.activejs

import kotlinx.coroutines.CoroutineContext

/**
 * ActiveJS — GraalVM ECMA launcher with pointcut integration.
 * 
 * Single responsibility: launch GraalVM Polyglot context (JS) and install
 * pointcut hooks that feed events into the local TrikeShed system.
 */
expect interface GraalEcmaLauncher {
    /**
     * Initialize and return a GraalVM Polyglot Context configured for pointcutting.
     * The context has pointcut hooks installed that emit to the local CCEK bus.
     */
    fun initialize(context: CoroutineContext): GraalEcmaContext
    
    /** Shutdown the GraalVM context and clean up resources. */
    fun shutdown()
}

/** Wrapper around GraalVM Polyglot Context with pointcut hooks installed. */
expect interface GraalEcmaContext {
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
 * Platform-specific implementations in jvmMain/jsMain/etc.
 */
expect interface PointcutEventProducer : CoroutineContext.Element {
    companion object Key : kotlinx.coroutines.AsyncContextKey<PointcutEventProducer>()
    override val key: CoroutineContext.Key<*> get() = Key
    fun emit(event: PointcutEvent)
    fun registerConsumer(consumer: PointcutEventConsumer)
    fun unregisterConsumer(consumer: PointcutEventConsumer)
}

expect interface PointcutEventConsumer : CoroutineContext.Element {
    companion object Key : kotlinx.coroutines.AsyncContextKey<PointcutEventConsumer>()
    override val key: CoroutineContext.Key<*> get() = Key
    fun onEvent(event: PointcutEvent)
}

expect fun CoroutineContext.getPointcutEventProducer(): PointcutEventProducer?
expect fun CoroutineContext.getPointcutEventConsumer(): PointcutEventConsumer?