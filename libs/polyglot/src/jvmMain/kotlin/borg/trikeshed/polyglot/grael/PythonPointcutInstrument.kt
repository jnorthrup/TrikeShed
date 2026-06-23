package borg.trikeshed.polyglot.grael

import borg.trikeshed.polyglot.ccek.FieldSynapse
import borg.trikeshed.polyglot.ccek.PointcutEventProducer
import borg.trikeshed.polyglot.graal.PointcutProducerService
import com.oracle.truffle.api.instrumentation.EventContext
import com.oracle.truffle.api.instrumentation.ExecutionEventListener
import com.oracle.truffle.api.instrumentation.SourceSectionFilter
import com.oracle.truffle.api.instrumentation.StandardTags
import com.oracle.truffle.api.instrumentation.TruffleInstrument
import com.oracle.truffle.api.frame.VirtualFrame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Truffle Instrument for Python attribute access pointcuts.
 * Uses GraalVM's ExecutionEventListener API to intercept READ/WRITE variable operations
 * at the VM level, emitting FieldSynapse events for L_GET/L_SET/P_GET/P_SET.
 * 
 * This replaces the peripheral monkey-patch approach (pointcut_instrument.py)
 * with native VM-level instrumentation that works for ALL Python code,
 * including built-in types, dataclasses, slots, properties, etc.
 * 
 * The instrument retrieves the PointcutEventProducer from PointcutProducerService
 * which is set by GraalPointcutHarness when the context is created.
 * 
 * META-INF/services entry for automatic instrument discovery:
 * src/jvmMain/resources/META-INF/services/com.oracle.truffle.api.instrumentation.TruffleInstrument
 * with content: borg.trikeshed.polyglot.grael.PythonPointcutInstrument
 */
class PythonPointcutInstrument : TruffleInstrument() {

    private val sequenceCounter = AtomicLong(0)
    private val methodIndexCache = ConcurrentHashMap<String, Int>()

    override fun onCreate(env: Env) {
        // Get producer from service (set by GraalPointcutHarness)
        val producer = PointcutProducerService.getProducer() ?: run {
            println("[PythonPointcutInstrument] No producer registered, skipping")
            return
        }

        val instrumenter = env.instrumenter
        val filter = SourceSectionFilter.newBuilder()
            .tagIs(StandardTags.ReadVariableTag::class.java)
            .build()

        val readListener = PointcutExecutionListener(producer, isWrite = false, sequenceCounter, methodIndexCache)
        instrumenter.attachExecutionEventListener(filter, readListener)

        val writeFilter = SourceSectionFilter.newBuilder()
            .tagIs(StandardTags.WriteVariableTag::class.java)
            .build()

        val writeListener = PointcutExecutionListener(producer, isWrite = true, sequenceCounter, methodIndexCache)
        instrumenter.attachExecutionEventListener(writeFilter, writeListener)

        println("[PythonPointcutInstrument] Attached read/write variable listeners")
    }

    override fun onDispose(env: Env) {
        println("[PythonPointcutInstrument] Disposed")
    }

    companion object {
        const val OP_L_GET = 0xA5.toByte()
        const val OP_L_SET = 0xA6.toByte()
        const val OP_P_GET = 0xA7.toByte()
        const val OP_P_SET = 0xA8.toByte()
        const val PHASE_BEFORE = 0.toByte()
        const val PHASE_AFTER = 1.toByte()
    }
}

/**
 * ExecutionEventListener implementation that emits FieldSynapse pointcuts.
 * Bound to ReadVariableTag/WriteVariableTag via SourceSectionFilter.
 */
private class PointcutExecutionListener(
    private val producer: PointcutEventProducer,
    private val isWrite: Boolean,
    private val sequenceCounter: AtomicLong,
    private val methodIndexCache: ConcurrentHashMap<String, Int>,
) : ExecutionEventListener {

    override fun onEnter(eventContext: EventContext, frame: VirtualFrame) {
        emitSynapse(eventContext, PHASE_BEFORE)
    }

    override fun onReturnValue(eventContext: EventContext, frame: VirtualFrame, result: Any?) {
        emitSynapse(eventContext, PHASE_AFTER)
    }

    override fun onReturnExceptional(eventContext: EventContext, frame: VirtualFrame, exception: Throwable) {
        emitSynapse(eventContext, PHASE_AFTER)
    }

    private fun emitSynapse(eventContext: EventContext, phase: Byte) {
        val sourceSection = eventContext.instrumentedSourceSection
        val sourceLocation = sourceSection?.toString() ?: "unknown"

        // Default className/fieldName - GraalPy source sections typically have the variable name
        val chars = sourceSection?.characters?.toString().orEmpty()
        var className = "UnknownClass"
        var fieldName = if (chars.isNotEmpty()) chars else "unknownField"
        var isStatic = false

        // Heuristic: check if this is a class-level access
        if (chars.startsWith("self.") || chars.startsWith("cls.")) {
            isStatic = false
            fieldName = chars.substringAfter('.')
        }

        val opcode = when {
            isStatic && !isWrite -> PythonPointcutInstrument.OP_P_GET
            isStatic && isWrite -> PythonPointcutInstrument.OP_P_SET
            !isStatic && !isWrite -> PythonPointcutInstrument.OP_L_GET
            else -> PythonPointcutInstrument.OP_L_SET
        }

        val callsiteKey = "$className.$fieldName${if (isStatic) " static" else " instance"}${if (isWrite) " write" else " read"}"

        val seq = sequenceCounter.incrementAndGet()
        val methodIdx = methodIndexCache.computeIfAbsent(callsiteKey) { methodIndexCache.size }

        val synapse = FieldSynapse(
            phase = phase,
            opcode = opcode,
            methodIdx = methodIdx,
            addr = sourceLocation.hashCode(),
            seq = seq.toInt(),
            nano = System.nanoTime(),
            callsiteHash = callsiteKey.hashCode(),
            templateIdx = methodIdx
        )

        producer.emit(synapse)
    }

    companion object {
        private const val PHASE_BEFORE: Byte = 0
        private const val PHASE_AFTER: Byte = 1
    }
}