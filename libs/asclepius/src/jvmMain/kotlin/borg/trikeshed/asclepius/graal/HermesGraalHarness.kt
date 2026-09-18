package borg.trikeshed.asclepius.graal

import borg.trikeshed.lib.MetaSeries
import borg.trikeshed.polyglot.ccek.FieldSynapse
import borg.trikeshed.polyglot.ccek.PointcutEventProducer
import borg.trikeshed.polyglot.graal.GraalPointcutHarness
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

const val ASC_PHASE_BEFORE: Byte = 0
const val ASC_PHASE_AFTER: Byte = 1

const val OP_TOOL_CALL: Byte = 0xB0.toByte()
const val OP_TOOL_RESULT: Byte = 0xB1.toByte()
const val OP_AGENT_TURN_START: Byte = 0xB2.toByte()
const val OP_AGENT_TURN_END: Byte = 0xB3.toByte()
const val OP_SKILL_LOAD: Byte = 0xB4.toByte()
const val OP_SKILL_EXEC: Byte = 0xB5.toByte()
const val OP_CONTEXT_COMPRESS: Byte = 0xB6.toByte()
const val OP_MEMORY_READ: Byte = 0xB7.toByte()
const val OP_MEMORY_WRITE: Byte = 0xB8.toByte()
const val OP_CRON_TICK: Byte = 0xB9.toByte()
const val OP_GATEWAY_MSG: Byte = 0xBA.toByte()

/**
 * HermesGraalHarness composes the existing polyglot pointcut harness with
 * Hermes-specific taxonomy. It does not subclass GraalPointcutHarness because
 * the existing harness is a concrete compatibility shim; Asclepius is the
 * host blackboard and taxonomy layer above it.
 */
class HermesGraalHarness(
    private val pointcutProducer: PointcutEventProducer? = null,
    enableHermesInstrumentation: Boolean = true,
    private val confixBlackboard: ConfixBlackboard? = null,
) {
    private val base = GraalPointcutHarness(
        pointcutProducer = pointcutProducer,
        enableInstrumentation = false,
    )

    val graalContext: Context get() = base.graalContext
    val context: Context get() = base.context

    private val methodIndexCache = ConcurrentHashMap<String, Int>()
    private val templateIndexCache = ConcurrentHashMap<String, Int>()
    private val sequenceCounter = AtomicLong(0)

    init {
        confixBlackboard?.registerHarness(this)
        graalContext.bindHermesPointcuts(this)
        if (enableHermesInstrumentation) installHermesInstrumentation()
    }

    private fun installHermesInstrumentation() {
        runCatching {
            val module = HermesGraalHarness::class.java
                .getResource("/hermes_instrument.py")
                ?.readText()
                ?: return
            graalContext.eval("python", module)
        }
    }

    fun eval(languageId: String, source: String): Any? = base.eval(languageId, source)
    fun evalHermes(source: String): Any? = eval("python", source)
    fun evalHermesJS(source: String): Any? = eval("js", source)

    fun nextSeq(): Long = sequenceCounter.incrementAndGet()

    fun methodIndex(callsiteKey: String): Int =
        methodIndexCache.computeIfAbsent(callsiteKey) { methodIndexCache.size }

    fun templateIndex(patternKey: String): Int =
        templateIndexCache.computeIfAbsent(patternKey) { templateIndexCache.size }

    fun emitToolCall(
        phase: Byte,
        toolName: String,
        argsHash: Int,
        seq: Long = nextSeq(),
    ) = emit(
        phase = phase,
        opcode = if (phase == ASC_PHASE_BEFORE) OP_TOOL_CALL else OP_TOOL_RESULT,
        methodKey = toolName,
        addr = argsHash,
        seq = seq,
        callsiteHash = toolName.hashCode(),
        templateKey = "tool:$toolName",
    )

    fun emitAgentTurn(
        phase: Byte,
        turnId: String,
        seq: Long = nextSeq(),
    ) = emit(
        phase = phase,
        opcode = if (phase == ASC_PHASE_BEFORE) OP_AGENT_TURN_START else OP_AGENT_TURN_END,
        methodKey = "AgentTurn",
        addr = turnId.hashCode(),
        seq = seq,
        callsiteHash = turnId.hashCode(),
        templateKey = "agent:turn",
    )

    fun emitSkillExec(
        phase: Byte,
        skillName: String,
        seq: Long = nextSeq(),
    ) = emit(
        phase = phase,
        opcode = if (phase == ASC_PHASE_BEFORE) OP_SKILL_LOAD else OP_SKILL_EXEC,
        methodKey = skillName,
        addr = skillName.hashCode(),
        seq = seq,
        callsiteHash = skillName.hashCode(),
        templateKey = "skill:$skillName",
    )

    fun emitContextCompress(
        phase: Byte,
        compressionRatio: Double,
        seq: Long = nextSeq(),
    ) = emit(
        phase = phase,
        opcode = OP_CONTEXT_COMPRESS,
        methodKey = "ContextCompress",
        addr = compressionRatio.hashCode(),
        seq = seq,
        callsiteHash = "context_compress".hashCode(),
        templateKey = "context:compress",
    )

    fun emitMemoryOp(
        phase: Byte,
        isWrite: Boolean,
        keyHash: Int,
        seq: Long = nextSeq(),
    ) = emit(
        phase = phase,
        opcode = if (isWrite) OP_MEMORY_WRITE else OP_MEMORY_READ,
        methodKey = if (isWrite) "MemoryWrite" else "MemoryRead",
        addr = keyHash,
        seq = seq,
        callsiteHash = keyHash,
        templateKey = if (isWrite) "memory:write" else "memory:read",
    )

    fun emitGatewayMessage(
        phase: Byte,
        channelHash: Int,
        seq: Long = nextSeq(),
    ) = emit(
        phase = phase,
        opcode = OP_GATEWAY_MSG,
        methodKey = "GatewayMessage",
        addr = channelHash,
        seq = seq,
        callsiteHash = channelHash,
        templateKey = "gateway:message",
    )

    private fun emit(
        phase: Byte,
        opcode: Byte,
        methodKey: String,
        addr: Int,
        seq: Long,
        callsiteHash: Int,
        templateKey: String,
    ) {
        val synapse = FieldSynapse(
            phase = phase,
            opcode = opcode,
            methodIdx = methodIndex(methodKey),
            addr = addr,
            seq = seq.toInt(),
            nano = System.nanoTime(),
            callsiteHash = callsiteHash,
            templateIdx = templateIndex(templateKey),
        )
        pointcutProducer?.emit(synapse)
        confixBlackboard?.routeSynapse(synapse)
    }

    fun close() {
        confixBlackboard?.unregisterHarness(this)
        base.close()
    }
}

/** Host object bound into Graal languages as `hermesPointcuts`. */
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

    @HostAccess.Export
    fun emitGatewayMessage(phase: Int, channel: String, seq: Long) {
        harness.emitGatewayMessage(phase.toByte(), channel.hashCode(), seq)
    }
}

fun Context.bindHermesPointcuts(harness: HermesGraalHarness) {
    val emitter = HermesPointcutEmitter(harness)
    for (language in listOf("python", "js")) {
        runCatching {
            initialize(language)
            getBindings(language).putMember("hermesPointcuts", emitter)
        }
    }
}

/**
 * ConfixBlackboard is the central taxonomy fabric for pointcuts and CRMS.
 *
 * It deliberately stores raw CRMS handles rather than imposing another object
 * model: Confix offsets, FieldSynapse frames, Cursor rows, and lambda specs all
 * stay as MetaSeries-compatible shapes that consumers can project with α.
 */
class ConfixBlackboard {
    private val harnesses = mutableListOf<HermesGraalHarness>()
    private val synapseRoutes = mutableMapOf<Byte, MutableList<(FieldSynapse) -> Unit>>()
    private val crmsRegistry = mutableMapOf<String, Any>()

    fun registerHarness(harness: HermesGraalHarness) {
        harnesses.add(harness)
    }

    fun unregisterHarness(harness: HermesGraalHarness) {
        harnesses.remove(harness)
    }

    fun route(opcode: Byte, handler: (FieldSynapse) -> Unit) {
        synapseRoutes.getOrPut(opcode) { mutableListOf() }.add(handler)
    }

    fun routeSynapse(synapse: FieldSynapse) {
        synapseRoutes[synapse.opcode]?.forEach { it(synapse) }
    }

    fun registerCrms(key: String, metaSeries: Any) {
        crmsRegistry[key] = metaSeries
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getCrms(key: String): T? = crmsRegistry[key] as? T

    @Suppress("UNCHECKED_CAST")
    fun <I, T> getMetaSeries(key: String): MetaSeries<I, T>? = crmsRegistry[key] as? MetaSeries<I, T>

    fun getHarnesses(): List<HermesGraalHarness> = harnesses.toList()
}
