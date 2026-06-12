package borg.trikeshed.polyglot.graal

import borg.trikeshed.lib.Series
import borg.trikeshed.polyglot.ccek.FieldSynapse
import borg.trikeshed.polyglot.ccek.PointcutEventProducer
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// Opcode constants (matching xvm FieldSynapse wire protocol) - top-level for accessibility
const val OP_L_GET = 0xA5.toByte()
const val OP_L_SET = 0xA6.toByte()
const val OP_P_GET = 0xA7.toByte()
const val OP_P_SET = 0xA8.toByte()

// Phase constants
const val PHASE_BEFORE = 0.toByte()
const val PHASE_AFTER = 1.toByte()

/**
 * Compatibility trace for the existing misspelled test helper `capturedSynapes()`.
 * The real capture path is still the caller's [PointcutEventProducer].
 */
internal object PolyglotPointcutTrace {
    private val synapses = Collections.synchronizedList(mutableListOf<FieldSynapse>())

    fun reset() = synapses.clear()

    fun record(synapse: FieldSynapse) {
        synapses.add(synapse)
    }

    fun snapshot(): List<FieldSynapse> = synchronized(synapses) { synapses.toList() }
}

@Suppress("FunctionName")
fun capturedSynapes(): List<FieldSynapse> = PolyglotPointcutTrace.snapshot()

/**
 * GraalPointcutHarness - extends pointcut capturing to Graal polyglot languages.
 *
 * The current polyglot layer exposes an explicit host-bound emitter. JVM classfile
 * instrumentation can feed the same [PointcutEventProducer] contract, so JS/GraalPy
 * probes and JVM bytecode probes share one FieldSynapse wire shape.
 */
class GraalPointcutHarness(
    private val pointcutProducer: PointcutEventProducer? = null,
    private val enableInstrumentation: Boolean = true,
) {
    val graalContext: Context = Context.newBuilder()
        .allowAllAccess(true)
        .allowHostAccess(HostAccess.ALL)
        .allowHostClassLookup { true }
        .build()

    /** Backward-compatible alias used by existing tests and demos. */
    val context: Context get() = graalContext

    private val sequenceCounter = AtomicLong(0)
    private val methodIndexCache = ConcurrentHashMap<String, Int>()
    private val templateIndexCache = ConcurrentHashMap<String, Int>()

    init {
        PolyglotPointcutTrace.reset()
        if (pointcutProducer != null) {
            graalContext.bindPointcutEmitter(this, pointcutProducer)
        }
        if (enableInstrumentation) {
            installPythonInstrumentation()
        }
    }

    /** Install Python-side pointcut instrumentation module from resources. */
    private fun installPythonInstrumentation() {
        try {
            val module = GraalPointcutHarness::class.java
                .getResource("/pointcut_instrument.py")
                ?.readText()
                ?: return
            graalContext.eval("python", module)
        } catch (_: Exception) {
            // Python runtime is optional in local Graal distributions.
        }
    }

    /** Bind pointcut emitter to context manually (for custom setup). */
    fun bindPointcutEmitter(producer: PointcutEventProducer) {
        graalContext.bindPointcutEmitter(this, producer)
    }

    /**
     * Evaluate code in a Graal language.
     * Language IDs: "js", "ruby", "python", "wasm", "llvm".
     */
    fun eval(languageId: String, source: String): Any? {
        val value = graalContext.eval(languageId, source)
        if (value == null || value.isNull) return null

        if (value.isNumber) {
            try { return value.asInt() } catch (_: Exception) {}
            try { return value.asLong() } catch (_: Exception) {}
            try { return value.asDouble() } catch (_: Exception) {}
        }

        return when {
            value.isString -> value.asString()
            value.isBoolean -> value.asBoolean()
            value.isHostObject -> value.asHostObject<Any?>()
            else -> value.toString()
        }
    }

    /** Close the Graal context and clean up. */
    fun close() {
        graalContext.close()
    }

    /** Get or assign a method index for a callsite. */
    fun getMethodIndex(callsiteKey: String): Int =
        methodIndexCache.computeIfAbsent(callsiteKey) { methodIndexCache.size }

    /** Get or assign a template index for a pattern. */
    fun getTemplateIndex(patternKey: String): Int =
        templateIndexCache.computeIfAbsent(patternKey) { templateIndexCache.size }

    /** Get next sequence number. */
    fun nextSeq(): Long = sequenceCounter.incrementAndGet()

    /** Bind the emitter to Python's pointcut_instrument module. */
    fun bindPythonInstrumentation(producer: PointcutEventProducer) {
        try {
            graalContext.bindPointcutEmitter(this, producer)
            graalContext.eval("python", "import pointcut_instrument")
            graalContext.eval("python", "pointcut_instrument.set_emitter(pointcutEmitter)")
        } catch (e: Exception) {
            println("Python instrumentation bind failed: ${e.message}")
        }
    }
}

/**
 * Host-accessible emitter that Graal polyglot code can call to emit pointcuts.
 * This is bound into the Graal context as "pointcutEmitter".
 */
class PolyglotPointcutEmitter(
    private val producer: PointcutEventProducer,
    private val harness: GraalPointcutHarness,
) {
    @HostAccess.Export
    fun emit(
        phase: Int,
        opcode: Int,
        methodName: String,
        sourceLocation: String,
        seq: Long,
        callsiteHash: Int,
        templateIdx: Int,
    ) {
        emitSynapse(
            phase = phase.toByte(),
            opcode = opcode.toByte(),
            methodKey = methodName,
            sourceLocation = sourceLocation,
            seq = seq,
            callsiteHash = callsiteHash,
            templateIdx = templateIdx,
        )
    }

    @HostAccess.Export
    fun emitFieldAccess(
        phase: Int,  // Int instead of Byte for JS/Python compatibility
        isStatic: Boolean,
        isWrite: Boolean,
        className: String,
        fieldName: String,
        sourceLocation: String,
        seq: Long,
    ) {
        val opcode = when {
            isStatic && !isWrite -> OP_P_GET
            isStatic && isWrite -> OP_P_SET
            !isStatic && !isWrite -> OP_L_GET
            else -> OP_L_SET
        }
        val callsiteKey = buildString {
            append(className)
            append('.')
            append(fieldName)
            append(if (isStatic) " static" else " instance")
            append(if (isWrite) " write" else " read")
        }
        emitSynapse(
            phase = phase.toByte(),
            opcode = opcode,
            methodKey = callsiteKey,
            sourceLocation = sourceLocation,
            seq = seq,
            callsiteHash = callsiteKey.hashCode(),
            templateIdx = harness.getTemplateIndex(callsiteKey),
        )
    }

    private fun emitSynapse(
        phase: Byte,
        opcode: Byte,
        methodKey: String,
        sourceLocation: String,
        seq: Long,
        callsiteHash: Int,
        templateIdx: Int,
    ) {
        val synapse = FieldSynapse(
            phase = phase,
            opcode = opcode,
            methodIdx = harness.getMethodIndex(methodKey),
            addr = sourceLocation.hashCode(),
            seq = seq.toInt(),
            nano = System.nanoTime(),
            callsiteHash = callsiteHash,
            templateIdx = templateIdx,
        )
        PolyglotPointcutTrace.record(synapse)
        producer.emit(synapse)
    }
}

/**
 * Extension function to bind pointcut emitter to Graal context.
 * Usage: context.bindPointcutEmitter(harness, producer)
 */
fun Context.bindPointcutEmitter(harness: GraalPointcutHarness, producer: PointcutEventProducer) {
    val emitter = PolyglotPointcutEmitter(producer, harness)
    listOf("js", "ruby", "python", "R").forEach { lang ->
        try {
            initialize(lang)
            getBindings(lang).putMember("pointcutEmitter", emitter)
        } catch (_: Exception) {
            // Language not available in this Graal distribution.
        }
    }
}
