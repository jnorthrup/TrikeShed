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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel

/**
 * P1 — one LCNC ring program hosted as a first-class CCEK assembly.
 *
 * The assembly scope is a structured child of [CCEK.CcekReactorBinding.reactorScope].
 * Therefore a node runner sees BOTH the reactor element and the LcncScopeFrame that
 * LcncRunner installs at ring entry in one currentCoroutineContext(). Cancelling the
 * returned [Run] cancels the walk and any in-flight suspend runner.
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

    fun launch(
        name: String,
        program: LcncProgram,
        args: Map<String, Any?> = emptyMap(),
        recordSignals: Boolean = true,
    ): Run {
        val child = CCEK.childScope("lcnc:$name", binding.reactorScope)
        val doc = ForgeDoc.page(ForgeBlockId("lcnc-run-$name"), "LCNC run: $name")
        val node = binding.choreograph(
            doc = doc,
            record = recordSignals,
            enabledProjections = setOf(ProjectionKind.DOCUMENT, ProjectionKind.MARKDOWN),
            maxConcurrency = 8,
        )
        val deferred = child.async {
            receipt(node.signalIn, name, "started")
            try {
                val out = runner.runProcedure(program, args)
                receipt(node.signalIn, name, "finished", mapOf(
                    "outputs" to out.nodeOutputs.size.toString(),
                    "returns" to out.returns.size.toString(),
                ))
                out
            } catch (t: Throwable) {
                receipt(node.signalIn, name, "failed", mapOf(
                    "error" to (t.message ?: t::class.simpleName.orEmpty()),
                ))
                throw t
            }
        }
        return Run(name, child, node, deferred)
    }

    private suspend fun receipt(
        sink: SendChannel<ForgeSignal>,
        name: String,
        state: String,
        extra: Map<String, String> = emptyMap(),
    ) {
        sink.send(
            ForgeSignal.AppendBlock(
                kind = ForgeBlockKind.TEXT,
                text = "lcnc:$name:$state",
                properties = mapOf("program" to name, "state" to state) + extra,
            )
        )
    }
}
