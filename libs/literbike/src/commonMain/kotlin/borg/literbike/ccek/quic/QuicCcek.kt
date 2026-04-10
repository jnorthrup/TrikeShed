package borg.literbike.ccek.quic

// ============================================================================
// QUIC CCEK -- ported from quic_ccek.rs
// Key graph for protocol transition reactor continuations
// ============================================================================

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** CCEK reactor context errors */
sealed class CcekError(override val message: String) : Exception(message) {
    class InvalidTransition(from: ULong, to: ULong) :
        CcekError("Invalid transition $from->$to")
    class ReactorContinuationFailed(detail: String) :
        CcekError("Reactor continuation failed: $detail")
    data object KeyGraphCorruption :
        CcekError("Key graph corruption detected")
}

/** Protocol transition with reactor continuation context */
data class ProtocolTransition(
    val fromState: ULong,
    val toState: ULong,
    val transitionGuard: (CcekContext) -> Boolean,
    val reactorContinuation: (CcekContext) -> Result<Unit>
)

/** CCEK reactor context with key graph navigation */
data class CcekContext(
    var currentState: ULong,
    var lastTransition: Long = System.currentTimeMillis(),
    val continuationStack: MutableList<ULong> = mutableListOf(),
    val protocolMetadata: MutableMap<String, List<UByte>> = mutableMapOf()
)

/**
 * QUIC CCEK -- Key graph for protocol transition reactor continuations.
 * Manages state transitions through a graph of guarded continuations.
 */
class QuicCcek(
    var policy: CcekPolicy = CcekPolicy(),
    val keyGraph: Pair<ULong, Map<ULong, ProtocolTransition>>,
    var context: CcekContext? = null
) {
    companion object {
        fun newWithKeyGraph(): QuicCcek {
            val initialState: ULong = 0x1000uL  // Initial QUIC handshake state
            val transitionMap = mutableMapOf<ULong, ProtocolTransition>()

            // Define protocol transition graph for reactor continuations
            transitionMap[0x1000uL] = ProtocolTransition(
                fromState = 0x1000uL,
                toState = 0x1001uL,  // Handshake -> Connected
                transitionGuard = { ctx -> ctx.currentState == 0x1000uL },
                reactorContinuation = { ctx ->
                    ctx.currentState = 0x1001uL
                    ctx.continuationStack.add(0x1001uL)
                    Result.success(Unit)
                }
            )

            transitionMap[0x1001uL] = ProtocolTransition(
                fromState = 0x1001uL,
                toState = 0x1002uL,  // Connected -> Data Transfer
                transitionGuard = { ctx -> ctx.currentState == 0x1001uL },
                reactorContinuation = { ctx ->
                    ctx.currentState = 0x1002uL
                    ctx.continuationStack.add(0x1002uL)
                    Result.success(Unit)
                }
            )

            val keyGraph = initialState to transitionMap

            return QuicCcek(
                policy = CcekPolicy(),
                keyGraph = keyGraph,
                context = CcekContext(
                    currentState = initialState,
                    lastTransition = System.currentTimeMillis(),
                    continuationStack = mutableListOf(initialState),
                    protocolMetadata = mutableMapOf()
                )
            )
        }
    }

    /** Execute reactor continuation based on key graph protocol transitions */
    fun executeReactorContinuation(targetState: ULong): Result<Unit> {
        val ctx = context ?: return Result.failure(CcekError.KeyGraphCorruption)
        val current = ctx.currentState

        val transition = keyGraph.second[current]
            ?: return Result.failure(CcekError.InvalidTransition(current, targetState))

        // Validate transition guard
        if (!transition.transitionGuard(ctx)) {
            return Result.failure(CcekError.InvalidTransition(current, targetState))
        }

        // Execute reactor continuation
        transition.reactorContinuation(ctx).getOrElse {
            return Result.failure(CcekError.ReactorContinuationFailed(
                "Transition $current->$targetState"
            ))
        }

        ctx.lastTransition = System.currentTimeMillis()
        return Result.success(Unit)
    }

    /** Key graph navigation with categorical composition */
    fun navigateKeyGraph(from: ULong, to: ULong): ProtocolTransition? {
        return keyGraph.second[from]?.takeIf { it.toState == to }
    }

    fun shouldEmitCover(last: Long, now: Long): Boolean {
        if (!policy.enableCover) return false
        val elapsed = now - last
        return elapsed > policy.cadence.idleMs.toLong()
    }

    fun coverDelay(): Duration = policy.cadence.burstMs.milliseconds

    /** Get current reactor continuation stack for protocol analysis */
    fun getContinuationStack(): List<ULong> {
        return context?.continuationStack?.toList() ?: emptyList()
    }
}
