package borg.trikeshed.asclepius.graal

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.polyglot.ccek.FieldSynapse
import borg.trikeshed.polyglot.ccek.PointcutEventProducer
import borg.trikeshed.polyglot.graal.GraalPointcutHarness
import borg.trikeshed.polyglot.graal.PolyglotPointcutEmitter
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Value
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * HermesGraalHarness — GraalVM pointcut instrumentation specialized for Hermes Agent fork.
 * 
 * Extends GraalPointcutHarness with:
 * - Hermes-specific pointcut taxonomy (tool calls, agent loops, skill execution)
 * - Confix blackboard integration for context-carried pointcut routing
 * - Cursor/Series algebra for analytical capture of agent trajectories
 * - Immutable off-heap capture via Arrow vectors
 * 
 * Wire Protocol: FieldSynapse (24B) — shared with JVM bytecode instrumentation
 *   phase: Byte (BEFORE=0/AFTER=1)
 *   opcode: Byte (L_GET/L_SET/P_GET/P_SET + Hermes extensions)
 *   methodIdx: Int (interned callsite)
 *   addr: Int (source location hash)
 *   seq: Int (sequence number)
 *   nano: Long (nanosecond timestamp)
 *   callsiteHash: Int
 *   templateIdx: Int
 */
class HermesGraalHarness(
    private val pointcutProducer: PointcutEventProducer? = null,
    private val enableHermesInstrumentation: Boolean = true,
    private val confixBlackboard: ConfixBlackboard? = null,
) : GraalPointcutHarness(pointcutProducer, enableHermesInstrumentation) {

    // Hermes-specific opcodes extending the base FieldSynapse set
    companion object {
        const val OP_TOOL_CALL = 0xB0.toByte()
        const val OP_TOOL_RESULT = 0xB1.toByte()
        const val OP_AGENT_TURN_START = 0xB2.toByte()
        const val OP_AGENT_TURN_END = 0xB3.toByte()
        const val OP_SKILL_LOAD = 0xB4.toByte()
        const val OP_SKILL_EXEC = 0xB5.toByte()
        const val OP_CONTEXT_COMPRESS = 0xB6.toByte()
        const val OP_MEMORY_READ = 0xB7.toByte()
        const val OP_MEMORY_WRITE = 0xB8.toByte()
        const val OP_CRON_TICK = 0xB9.toByte()
        const val OP_GATEWAY_MSG = 0xBA.toByte()
    }

    private val hermesMethodIndexCache = ConcurrentHashMap<String, Int>()
    private val hermesTemplateIndexCache = ConcurrentHashMap<String, Int>()
    private val hermesSequenceCounter = AtomicLong(0)

    init {
        if (enableHermesInstrumentation) {
            installHermesInstrumentation()
        }
        confixBlackboard?.registerHarness(this)
    }

    /** Install Hermes-specific Python instrumentation from resources. */
    private fun installHermesInstrumentation() {
        try {
            val module = HermesGraalHarness::class.java
                .getResource("/hermes_instrument.py")
                ?.readText()
                ?: return
            graalContext.eval("python", module)
            bindPythonInstrumentation(pointcutProducer!!)
        } catch (_: Exception) {
            // Python runtime optional
        }
    }

    /** Evaluate Hermes Python code with pointcut capture. */
    fun evalHermes(source: String): Any? = eval("python", source)

    /** Evaluate Hermes JavaScript code with pointcut capture. */
    fun evalHermesJS(source: String): Any? = eval("js", source)

    /** Get next Hermes sequence number. */
    fun nextHermesSeq(): Long = hermesSequenceCounter.incrementAndGet()

    /** Get or assign Hermes method index. */
    fun getHermesMethodIndex(callsiteKey: String): Int =
        hermesMethodIndexCache.computeIfAbsent(callsiteKey) { hermesMethodIndexCache.size }

    /** Get or assign Hermes template index. */
    fun getHermesTemplateIndex(patternKey: String): Int =
        hermesTemplateIndexCache.computeIfAbsent(patternKey) { hermesTemplateIndexCache.size }

    /** Emit a Hermes tool call pointcut. */
    fun emitToolCall(
        phase: Byte,
        toolName: String,
        argsHash: Int,
        seq: Long = nextHermesSeq(),
    ) {
        val synapse = FieldSynapse(
            phase = phase,
            opcode = if (phase == PHASE_BEFORE) OP_TOOL_CALL else OP_TOOL_RESULT,
            methodIdx = getHermesMethodIndex(toolName),
            addr = argsHash,
            seq = seq.toInt(),
            nano = System.nanoTime(),
            callsiteHash = toolName.hashCode(),
            templateIdx = getHermesTemplateIndex("tool:$toolName"),
        )
        emitSynapse(synapse)
    }

    /** Emit an agent turn boundary pointcut. */
    fun emitAgentTurn(
        phase: Byte,
        turnId: String,
        seq: Long = nextHermesSeq(),
    ) {
        val synapse = FieldSynapse(
            phase = phase,
            opcode = if (phase == PHASE_BEFORE) OP_AGENT_TURN_START else OP_AGENT_TURN_END,
            methodIdx = getHermesMethodIndex("AgentTurn"),
            addr = turnId.hashCode(),
            seq = seq.toInt(),
            nano = System.nanoTime(),
            callsiteHash = turnId.hashCode(),
            templateIdx = getHermesTemplateIndex("agent:turn"),
        )
        emitSynapse(synapse)
    }

    /** Emit a skill execution pointcut. */
    fun emitSkillExec(
        phase: Byte,
        skillName: String,
        seq: Long = nextHermesSeq(),
    ) {
        val synapse = FieldSynapse(
            phase = phase,
            opcode = if (phase == PHASE_BEFORE) OP_SKILL_LOAD else OP_SKILL_EXEC,
            methodIdx = getHermesMethodIndex(skillName),
            addr = skillName.hashCode(),
            seq = seq.toInt(),
            nano = System.nanoTime(),
            callsiteHash = skillName.hashCode(),
            templateIdx = getHermesTemplateIndex("skill:$skillName"),
        )
        emitSynapse(synapse)
    }

    /** Emit a context compression pointcut. */
    fun emitContextCompress(
        phase: Byte,
        compressionRatio: Double,
        seq: Long = nextHermesSeq(),
    ) {
        val synapse = FieldSynapse(
            phase = phase,
            opcode = OP_CONTEXT_COMPRESS,
            methodIdx = getHermesMethodIndex("ContextCompress"),
            addr = compressionRatio.hashCode(),
            seq = seq.toInt(),
            nano = System.nanoTime(),
            callsiteHash = "context_compress".hashCode(),
            templateIdx = getHermesTemplateIndex("context:compress"),
        )
        emitSynapse(synapse)
    }

    /** Emit a memory read/write pointcut. */
    fun emitMemoryOp(
        phase: Byte,
        isWrite: Boolean,
        keyHash: Int,
        seq: Long = nextHermesSeq(),
    ) {
        val synapse = FieldSynapse(
            phase = phase,
            opcode = if (isWrite) OP_MEMORY_WRITE else OP_MEMORY_READ,
            methodIdx = getHermesMethodIndex(if (isWrite) "MemoryWrite" else "MemoryRead"),
            addr = keyHash,
            seq = seq.toInt(),
            nano = System.nanoTime(),
            callsiteHash = keyHash,
            templateIdx = getHermesTemplateIndex(if (isWrite) "memory:write" else "memory:read"),
        )
        emitSynapse(synapse)
    }

    private fun emitSynapse(synapse: FieldSynapse) {
        pointcutProducer?.emit(synapse)
        // Also emit to Confix blackboard for context-carried routing
        confixBlackboard?.routeSynapse(synapse)
    }

    override fun close() {
        confixBlackboard?.unregisterHarness(this)
        super.close()
    }
}

/**
 * Hermes-specific pointcut emitter bound into Graal context as "hermesPointcuts".
 * Provides typed emit methods for Hermes instrumentation module.
 */
class HermesPointcutEmitter(
    private val harness: HermesGraalHarness,
) {
    @HostAccess.Export
    fun emitToolCall(phase: Int, toolName: String, argsJson: String, seq: Long) {
        harness.emitToolCall(phase.toByte(), toolName, argsJson.hashCode(), seq)
    }

    @HostAccess.Export
    fun emitAgentTurn(phase: Int, turnId: String, seq: Long) {
        harness.emitAgentTurn(phase.toByte(), turnId, seq)
    }

    @HostAccess.Export
    fun emitSkillExec(phase: Int, skillName: String, seq: Long) {
        harness.emitSkillExec(phase.toByte(), skillName, seq)
    }

    @HostAccess.Export
    fun emitContextCompress(phase: Int, compressionRatio: Double, seq: Long) {
        harness.emitContextCompress(phase.toByte(), compressionRatio, seq)
    }

    @HostAccess.Export
    fun emitMemoryOp(phase: Int, isWrite: Boolean, key: String, seq: Long) {
        harness.emitMemoryOp(phase.toByte(), isWrite, key.hashCode(), seq)
    }
}

/**
 * Extension to bind Hermes pointcut emitter to Graal context.
 * Usage: context.bindHermesPointcuts(harness)
 */
fun Context.bindHermesPointcuts(harness: HermesGraalHarness) {
    val emitter = HermesPointcutEmitter(harness)
    listOf("python", "js").forEach { lang ->
        try {
            initialize(lang)
            getBindings(lang).putMember("hermesPointcuts", emitter)
        } catch (_: Exception) {
            // Language not available
        }
    }
}

/**
 * ConfixBlackboard — Central taxonomy coordinator for pointcuts and CRMS.
 * 
 * The Confix parser produces a JsContext = Join<JsElement, Series<Char>> where
 * JsElement stores (open, close) offsets into source. This blackboard uses the
 * Confix parse tree as the central routing fabric for:
 * - FieldSynapse pointcut events (taxonomy by opcode/facet)
 * - CRMS MetaSeries composition (cursor → tensor → analytical query)
 * - SupervisorContext lifecycle events (element open/drain/close)
 * 
 * The blackboard IS the Confix context — zero-copy offsets into source text
 * serve as immutable keys for pointcut correlation.
 */
class ConfixBlackboard {
    private val harnesses = mutableListOf<HermesGraalHarness>()
    private val synapseRoutes = mutableMapOf<Byte, MutableList<(FieldSynapse) -> Unit>>()
    private val crmsRegistry = mutableMapOf<String, Any>() // MetaSeries handles

    fun registerHarness(harness: HermesGraalHarness) {
        harnesses.add(harness)
    }

    fun unregisterHarness(harness: HermesGraalHarness) {
        harnesses.remove(harness)
    }

    /** Register a route for a given opcode. */
    fun route(opcode: Byte, handler: (FieldSynapse) -> Unit) {
        synapseRoutes.getOrPut(opcode) { mutableListOf() }.add(handler)
    }

    /** Route a synapse to all registered handlers for its opcode. */
    fun routeSynapse(synapse: FieldSynapse) {
        synapseRoutes[synapse.opcode]?.forEach { it(synapse) }
    }

    /** Register a CRMS MetaSeries handle by taxonomic key. */
    fun registerCrms(key: String, metaSeries: Any) {
        crmsRegistry[key] = metaSeries
    }

    /** Retrieve a CRMS MetaSeries by key. */
    @Suppress("UNCHECKED_CAST")
    fun <T> getCrms(key: String): T? = crmsRegistry[key] as? T

    /** Get all registered harnesses. */
    fun getHarnesses(): List<HermesGraalHarness> = harnesses.toList()
}