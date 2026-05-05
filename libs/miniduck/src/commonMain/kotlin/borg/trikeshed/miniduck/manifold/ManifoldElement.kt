package borg.trikeshed.miniduck.manifold

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.lib.Series
import borg.trikeshed.manifold.ManifoldConcept
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * ManifoldElement — CCEK element that is the seating foundation for the NARS3 deriver.
 *
 * Three independent SupervisorJob branches, one per axis:
 *   shapeBranch  — RowVec family routing, block-level I/O dispatch
 *   timeBranch   — WAL seq / lifecycle sealing / NARS3 belief revision
 *   accessBranch — cache-aligned span seeks, index topology
 *
 * Invariant: no axis owns another.
 *   - Sibling branch failure is isolated (SupervisorJob semantics).
 *   - Shared parent lifecycle (open → drain → close) governs all three together.
 *
 * NARS3 deriver: derive() fires inference coroutines under timeBranch.
 * Each NAL operation becomes a child coroutine; failures don't cascade.
 * Results flow back via [onDerived] — the caller decides what to do with them.
 *
 * For full NAL-level fanout, pass a Series<DeriverStep> to deriveAll() which
 * fans out across NAL levels as concurrent children of timeBranch.
 */
object ManifoldSupervisorKey : CoroutineContext.Key<ManifoldElement>

class ManifoldElement(parentJob: Job? = null) : AsyncContextElement(parentJob = parentJob) {

    override val key: CoroutineContext.Key<*> get() = ManifoldSupervisorKey

    /** Shape branch: RowVec family / block dispatch */
    val shapeBranch: CompletableJob = SupervisorJob(supervisor)

    /** Time branch: WAL sealing + NARS3 belief deriver */
    val timeBranch: CompletableJob = SupervisorJob(supervisor)

    /** Access branch: topology, cache spans, index seeks */
    val accessBranch: CompletableJob = SupervisorJob(supervisor)

    /**
     * Dispatch a MiniDuckPoint to its branches.
     * Each handler runs as an independent child coroutine of its branch.
     * A null handler means that axis is not participating for this point.
     */
    suspend fun dispatch(
        point: MiniDuckPoint,
        scope: CoroutineScope,
        onShape: (suspend (MiniDuckPoint) -> Unit)? = null,
        onTime: (suspend (MiniDuckPoint) -> Unit)? = null,
        onAccess: (suspend (MiniDuckPoint) -> Unit)? = null,
    ) {
        if (onShape != null) scope.launch(shapeBranch) { onShape(point) }
        if (onTime  != null) scope.launch(timeBranch)  { onTime(point) }
        if (onAccess != null) scope.launch(accessBranch) { onAccess(point) }
    }

    /**
     * NARS3 single-step deriver: apply one decay+reinforce cycle under timeBranch.
     * Concepts that drop below energy threshold are not forwarded.
     *
     * This is the minimal deriver atom — deriveAll() fans across NAL steps.
     */
    suspend fun <P> derive(
        concept: ManifoldConcept<P>,
        scope: CoroutineScope,
        energyFloor: Float = 0.05f,
        decayFactor: Float = 0.9f,
        onDerived: (ManifoldConcept<P>) -> Unit,
    ) {
        scope.launch(timeBranch) {
            val derived = concept.decay(decayFactor)
            if (derived.budget.energy() >= energyFloor) {
                onDerived(derived)
            }
        }
    }

    /**
     * NARS3 multi-step deriver: fan out across a Series of deriver steps,
     * each running as an independent child of timeBranch.
     *
     * A DeriverStep transforms a concept and calls back with results.
     * NAL levels, revision, choice, deduction etc. each become one step.
     */
    suspend fun <P> deriveAll(
        concept: ManifoldConcept<P>,
        steps: Series<DeriverStep<P>>,
        scope: CoroutineScope,
        onDerived: (ManifoldConcept<P>) -> Unit,
    ) {
        for (i in 0 until steps.a) {
            val step = steps.b(i)
            scope.launch(timeBranch) {
                step.apply(concept, onDerived)
            }
        }
    }

    override suspend fun close() {
        shapeBranch.complete()
        timeBranch.complete()
        accessBranch.complete()
        super.close()
    }
}

/**
 * A single NARS3 inference step: transforms a concept and emits derived concepts.
 * Kept as a functional interface so NAL levels compose without coupling to ManifoldElement.
 */
fun interface DeriverStep<P> {
    suspend fun apply(concept: ManifoldConcept<P>, emit: (ManifoldConcept<P>) -> Unit)
}

/** Built-in step: temporal decay — models belief aging over time. */
fun <P> decayStep(factor: Float = 0.9f, floor: Float = 0.05f): DeriverStep<P> =
    DeriverStep { concept, emit ->
        val next = concept.decay(factor)
        if (next.budget.energy() >= floor) emit(next)
    }

/** Built-in step: angular projection — remaps concept to a different identity region. */
fun <P> angularShiftStep(shift: Long): DeriverStep<P> =
    DeriverStep { concept, emit ->
        emit(ManifoldConcept(concept.angular xor shift, concept.budget, concept.payload))
    }
