package borg.trikeshed.polyglot.graal

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Value
import borg.trikeshed.polyglot.ccek.PointcutEventProducer
import borg.trikeshed.polyglot.ccek.FieldSynapse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * GraalPointcutHarness - extends pointcut capturing to any Graal polyglot language.
 * 
 * Uses manual pointcut emission via bound emitter (PolyglotPointcutEmitter).
 * Supports JS, Ruby, Python, WASM via GraalVM SDK.
 * 
 * Pointcut types:
 * - L_GET (0xA5): instance field / local variable read
 * - L_SET (0xA6): instance field / local variable write
 * - P_GET (0xA7): static field read
 * - P_SET (0xA8): static field write
 * 
 * Two modes supported:
 * - Same-VM: runs in current JVM (current implementation)
 * - Parent-process VM: communicates via external process (future)
 */
class GraalPointcutHarness(
    private val pointcutProducer: PointcutEventProducer? = null,
    private val enableInstrumentation: Boolean = true
) {

    val context: Context = Context.newBuilder()
        .allowAllAccess(true)
        .allowHostAccess(HostAccess.ALL)
        .allowHostClassLookup { className -> true }
        .build()

    private val sequenceCounter = AtomicLong(0)
    private val methodIndexCache = ConcurrentHashMap<String, Int>()
    private val templateIndexCache = ConcurrentHashMap<String, Int>()

    init {
        // Auto-bind pointcut emitter if producer provided
        if (pointcutProducer != null) {
            context.bindPointcutEmitter(this, pointcutProducer)
        }
    }

    /** Bind pointcut emitter to context manually (for custom setup). */
    fun bindPointcutEmitter(producer: PointcutEventProducer) {
        context.bindPointcutEmitter(this, producer)
    }

    /** 
     * Evaluate code in a Graal language.
     * Language IDs: "js", "ruby", "python", "wasm", "llvm"
     * Returns the result converted to a Kotlin/JVM type.
     */
    fun eval(languageId: String, source: String): Any? {
        val value = context.eval(languageId, source)
        if (value == null || value.isNull) return null
        
        // Try numeric conversions in order of preference
        if (value.isNumber) {
            try { return value.asInt() } catch (e: Exception) {}
            try { return value.asLong() } catch (e: Exception) {}
            try { return value.asDouble() } catch (e: Exception) {}
        }
        
        return when {
            value.isString -> value.asString()
            value.isBoolean -> value.asBoolean()
            value.isHostObject -> value.asHostObject()
            else -> value.toString()
        }
    }

    /** Close the Graal context and clean up. */
    fun close() {
        context.close()
    }

    /** Get or assign a method index for a callsite. */
    fun getMethodIndex(callsiteKey: String): Int {
        return methodIndexCache.computeIfAbsent(callsiteKey) { methodIndexCache.size }
    }

    /** Get or assign a template index for a pattern. */
    fun getTemplateIndex(patternKey: String): Int {
        return templateIndexCache.computeIfAbsent(patternKey) { templateIndexCache.size }
    }

    /** Get next sequence number. */
    fun nextSeq(): Long = sequenceCounter.incrementAndGet()

    companion object {
        // Phase constants
        const val PHASE_BEFORE = 0.toByte()
        const val PHASE_AFTER = 1.toByte()

        // Opcode constants (matching xvm FieldSynapse wire protocol)
        const val OP_L_GET = 0xA5.toByte()
        const val OP_L_SET = 0xA6.toByte()
        const val OP_P_GET = 0xA7.toByte()
        const val OP_P_SET = 0xA8.toByte()
    }
}

/**
 * Host-accessible emitter that Graal polyglot code can call to emit pointcuts.
 * This is bound into the Graal context as "pointcutEmitter".
 */
class PolyglotPointcutEmitter(
    private val producer: PointcutEventProducer,
    private val harness: GraalPointcutHarness
) {

    @HostAccess.Export
    fun emit(
        phase: Byte,
        opcode: Byte,
        methodName: String,
        sourceLocation: String,
        seq: Long,
        callsiteHash: Int,
        templateIdx: Int
    ) {
        val methodIdx = harness.getMethodIndex(methodName)
        val synapse = FieldSynapse(
            phase = phase,
            opcode = opcode,
            methodIdx = methodIdx,
            addr = sourceLocation.hashCode(),
            seq = seq.toInt(),
            nano = System.nanoTime(),
            callsiteHash = callsiteHash,
            templateIdx = templateIdx
        )
        producer.emit(synapse)
    }

    @HostAccess.Export
    fun emitFieldAccess(
        phase: Int,  // Use Int instead of Byte for JS compatibility
        isStatic: Boolean,
        isWrite: Boolean,
        className: String,
        fieldName: String,
        sourceLocation: String,
        seq: Long
    ) {
        val opcode = when {
            isStatic && !isWrite -> GraalPointcutHarness.OP_P_GET
            isStatic && isWrite -> GraalPointcutHarness.OP_P_SET
            !isStatic && !isWrite -> GraalPointcutHarness.OP_L_GET
            else -> GraalPointcutHarness.OP_L_SET
        }

        val callsiteKey = "$className.$fieldName${if (isStatic) " static" else ""}${if (isWrite) " write" else " read"}"
        val methodIdx = harness.getMethodIndex(callsiteKey)
        val templateIdx = harness.getTemplateIndex(callsiteKey)

        val synapse = FieldSynapse(
            phase = phase.toByte(),
            opcode = opcode,
            methodIdx = methodIdx,
            addr = className.hashCode() + fieldName.hashCode(),
            seq = seq.toInt(),
            nano = System.nanoTime(),
            callsiteHash = callsiteKey.hashCode(),
            templateIdx = templateIdx
        )
        producer.emit(synapse)
    }
}

/**
 * Extension function to bind pointcut emitter to Graal context.
 * Usage: context.bindPointcutEmitter(harness, producer)
 */
fun Context.bindPointcutEmitter(harness: GraalPointcutHarness, producer: PointcutEventProducer) {
    val emitter = PolyglotPointcutEmitter(producer, harness)
    // Bind to all available languages - initialize each language first
    listOf("js", "ruby", "python", "R").forEach { lang ->
        try {
            // Initialize the language first
            initialize(lang)
            getBindings(lang).putMember("pointcutEmitter", emitter)
        } catch (e: Exception) {
            // Language not available, skip
        }
    }
}