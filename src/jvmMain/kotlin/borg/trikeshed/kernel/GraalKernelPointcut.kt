package borg.trikeshed.kernel

import borg.trikeshed.graal.subvm.*
import borg.trikeshed.pointcut.*
import borg.trikeshed.vm.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Graal executes the cellular admission predicate at every real task dispatch.
 * Root-return instrumentation lands the corresponding control envelope on the existing blackboard.
 * Guest code receives no task closure, source text, model response or credential.
 */
class GraalKernelPointcut(
    id: String,
    val adapter: PointcutBlackboardAdapter,
) : KernelPointcut {
    private val lock = Any()
    private val sequence = AtomicInteger()
    private var active: KernelTurn? = null
    private var fields: Map<String, Any?> = emptyMap()
    private var landingFailure: Throwable? = null
    private var closed = false
    private val guest = InProcessIsolate(id, VmFacet.GRAAL_JS, Budget(statements = 0, wallMillis = 2_000)) { observation ->
        val turn = active
        if (turn != null && observation.root in setOf("kernel_dispatch", "kernel_completion")) {
            try {
                adapter.accept(PointcutEvent(
                    VmFacet.GRAAL_JS,
                    "kernel.${turn.kernel.value}.${turn.seat.value}.${observation.root}",
                    null, turn.name, fields,
                    sequence.incrementAndGet(), observation.sourceName,
                    observation.line, observation.column, isRoot = true,
                ), isWrite = true)
            } catch (cause: Throwable) { landingFailure = cause }
        }
    }.also { it.eval(POLICY, "kernel-executive.js") }

    override fun dispatch(turn: KernelTurn): Unit = synchronized(lock) {
        check(!closed) { "Kernel pointcut is closed" }
        val envelope = fields(turn) + ("phase" to "dispatch")
        val result = invoke("kernel_dispatch", turn, envelope)
        check((result as? Teleported.Num)?.v == turn.sequence) { "Graal kernel admission denied" }
    }

    override fun complete(receipt: KernelReceipt): Unit = synchronized(lock) {
        check(!closed) { "Kernel pointcut is closed" }
        val envelope = fields(receipt.turn) + mapOf(
            "phase" to "completion", "outcome" to receipt.outcome.name,
            "elapsedMillis" to receipt.elapsedMillis, "failure" to receipt.failure,
        )
        check(invoke("kernel_completion", receipt.turn, envelope) == Teleported.Bool(true)) {
            "Graal kernel completion rejected"
        }
    }

    private fun invoke(root: String, turn: KernelTurn, envelope: Map<String, Any?>): Teleported {
        active = turn
        fields = envelope
        landingFailure = null
        try {
            val result = guest.call(root, Teleported.Str(Teleported.ofHost(envelope).canonical()))
            landingFailure?.let { throw it }
            return result
        } finally {
            active = null
            fields = emptyMap()
        }
    }

    private fun fields(turn: KernelTurn): Map<String, Any?> = mapOf(
        "kernel" to turn.kernel.value, "parent" to turn.parent?.value,
        "seat" to turn.seat.value, "sequence" to turn.sequence,
        "name" to turn.name, "capability" to turn.capability.name,
    )

    override fun close(): Unit = synchronized(lock) {
        if (!closed) {
            guest.close()
            closed = true
        }
    }

    companion object {
        const val POLICY = """
            function kernel_dispatch(encoded) {
                const turn = JSON.parse(encoded);
                if (turn.parent !== null && turn.capability !== "COMPUTE") {
                    throw new Error("child kernel capability denied");
                }
                if (!Number.isSafeInteger(turn.sequence) || turn.sequence < 0 || !turn.name) {
                    throw new Error("invalid kernel turn");
                }
                return turn.sequence;
            }
            function kernel_completion(encoded) {
                const receipt = JSON.parse(encoded);
                if (receipt.outcome !== "SUCCESS" && receipt.outcome !== "FAILURE") {
                    throw new Error("invalid kernel outcome");
                }
                return receipt.elapsedMillis >= 0;
            }
        """
    }
}
