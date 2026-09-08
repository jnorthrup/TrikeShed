package borg.trikeshed.lcnc.ccek

import borg.trikeshed.ccek.ArticulatedNode
import borg.trikeshed.ccek.CCEK
import borg.trikeshed.ccek.ForgeSignal
import borg.trikeshed.ccek.ProjectionKind
import borg.trikeshed.forge.ForgeBlockId
import borg.trikeshed.forge.ForgeBlockKind
import borg.trikeshed.forge.ForgeDoc
import borg.trikeshed.lcnc.LcncProgram
import borg.trikeshed.lcnc.LcncRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * P1 — one LCNC ring program hosted as a first-class CCEK assembly.
 *
 * Invocation elements compose with the binding's authoritative reactor. The caller
 * owns the run when supplied; reactor cancellation also stops it. The result owns
 * both the walker and receipt node and completes only after their work drains.
 *
 * Start/finish/failure receipts are real ForgeSignals delivered to a choreographed
 * [ArticulatedNode]. Its existing Semaphore bounds agent fan-out; observers consume the
 * ordinary document/board/markdown projections — no parallel receipt bus.
 */
class LcncCcekAssembly(
    private val binding: CCEK.CcekReactorBinding,
    private val runner: LcncRunner,
) {
    data class Run(
        val name: String,
        val scope: CoroutineScope,
        val node: ArticulatedNode,
        val result: Deferred<LcncRunner.ScopeResult>,
    ) {
        fun cancel(reason: String = "LCNC assembly cancelled") {
            scope.cancel(reason)
        }
    }

    /** [context] supplies invocation elements and its parent job; absent context uses the binding. */
    fun launch(
        name: String,
        program: LcncProgram,
        args: Map<String, Any?> = emptyMap(),
        recordSignals: Boolean = true,
        context: CoroutineContext = EmptyCoroutineContext,
    ): Run {
        val reactorContext = binding.reactorScope.coroutineContext
        val reactorJob = reactorContext[Job]
        val parent = context[Job] ?: reactorJob
        val owner = SupervisorJob(parent)
        val child = CoroutineScope(
            reactorContext + context + binding.reactor + owner + CoroutineName("CCEK-lcnc:$name")
        )
        // A leaf job observes reactor cancellation without replacing the caller parent.
        val reactorLink = if (parent !== reactorJob) Job(reactorJob) else null
        reactorLink?.invokeOnCompletion { cause ->
            if (cause != null) owner.cancel(CancellationException("LCNC reactor closed", cause))
        }
        owner.invokeOnCompletion { reactorLink?.complete() }

        val doc = ForgeDoc.page(ForgeBlockId("lcnc-run-$name"), "LCNC run: $name")
        lateinit var node: ArticulatedNode
        // Install the node before returning, even when the parent is already cancelled.
        val deferred = child.async(start = CoroutineStart.UNDISPATCHED) {
            node = ArticulatedNode(
                initialDoc = doc,
                scope = this,
                record = recordSignals,
                enabledProjections = setOf(ProjectionKind.DOCUMENT, ProjectionKind.MARKDOWN),
                maxConcurrency = 8,
            )
            var failure: Throwable? = null
            try {
                yield()
                withContext(node) {
                    node.signalIn.send(receipt(name, "started"))
                    val out = runner.runProcedure(program, args)
                    node.signalIn.send(receipt(name, "finished", mapOf(
                        "outputs" to out.nodeOutputs.size.toString(),
                        "returns" to out.returns.size.toString(),
                    )))
                    out
                }
            } catch (t: Throwable) {
                failure = t
                // A cancelled or closed receipt channel must not replace the run failure.
                node.signalIn.trySend(receipt(name, "failed", mapOf(
                    "error" to (t.message ?: t::class.simpleName.orEmpty()),
                )))
                if (t is CancellationException) node.abort(t)
                throw t
            } finally {
                try {
                    withContext(NonCancellable) { node.drain() }
                } catch (cleanup: Throwable) {
                    val original = failure
                    if (original == null) throw cleanup
                    if (cleanup !== original) original.addSuppressed(cleanup)
                }
            }
        }
        deferred.invokeOnCompletion { owner.complete() }
        return Run(name, CoroutineScope(child.coroutineContext + deferred + node), node, deferred)
    }

    private fun receipt(
        name: String,
        state: String,
        extra: Map<String, String> = emptyMap(),
    ): ForgeSignal = ForgeSignal.AppendBlock(
        kind = ForgeBlockKind.TEXT,
        text = "lcnc:$name:$state",
        properties = mapOf("program" to name, "state" to state) + extra,
    )
}
