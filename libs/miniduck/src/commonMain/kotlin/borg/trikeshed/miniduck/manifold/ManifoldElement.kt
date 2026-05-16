package borg.trikeshed.miniduck.manifold

import borg.trikeshed.lib.Series
import borg.trikeshed.manifold.ManifoldConcept
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * ManifoldElement — CCEK element that is the seating foundation for the NARS3 deriver.
 *
 * Three independent SupervisorJob branches, one per axis:
 *   - timeBranch: temporal reasoning cycles
 *   - shapeBranch: spatial/conceptual clustering
 *   - accessBranch: attention/priority filtering
 *
 * Each branch operates with its own CoroutineContext derived from the
 * element's base context and the axis-specific seed.
 */
class ManifoldElement<P>(
    private val elementContext: CoroutineContext,
    private val elementConcept: ManifoldConcept<P>,
) {

    private val timeBranchJob: CompletableJob = SupervisorJob()
    private val shapeBranchJob: CompletableJob = SupervisorJob()
    private val accessBranchJob: CompletableJob = SupervisorJob()

    val timeBranchContext: CoroutineContext = elementContext + timeBranchJob
    val shapeBranchContext: CoroutineContext = elementContext + shapeBranchJob
    val accessBranchContext: CoroutineContext = elementContext + accessBranchJob

    /**
     * NAL levels, revision, choice, deduction etc. each become one step.
     */
    suspend fun deriveAll(
        steps: Series<DeriverStep<P>>,
        scope: CoroutineScope,
        onDerived: (ManifoldConcept<P>) -> Unit,
    ) {
        supervisorScope {
            val branch = branchContext(scope, timeBranchJob)
            val n = steps.a
            val stepList: List<DeriverStep<P>> = buildList {
                for (i in 0 until n) add(steps.b(i))
            }
            for (i in 0 until n) {
                val step: DeriverStep<P> = stepList[i]
                val deferred = async(branch) {
                    step.apply(elementConcept, onDerived)
                }
                runCatching { deferred.await() }
            }
        }
    }

    fun close() {
        shapeBranchJob.cancel()
        timeBranchJob.cancel()
        accessBranchJob.cancel()
    }

    private fun branchContext(scope: CoroutineScope, branch: Job): CoroutineContext =
        scope.coroutineContext + branch

    suspend fun <T> withTimeBranch(block: suspend CoroutineScope.() -> T): T =
        withContext(timeBranchContext, block)

    suspend fun <T> withShapeBranch(block: suspend CoroutineScope.() -> T): T =
        withContext(shapeBranchContext, block)

    suspend fun <T> withAccessBranch(block: suspend CoroutineScope.() -> T): T =
        withContext(accessBranchContext, block)
}

/** Deriver step — one inference cycle. */
interface DeriverStep<P> {
    fun apply(concept: ManifoldConcept<P>, onDerived: (ManifoldConcept<P>) -> Unit)
}