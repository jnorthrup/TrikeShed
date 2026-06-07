package borg.trikeshed.userspace

import borg.trikeshed.chronicle.Chronicle
import borg.trikeshed.chronicle.TransitionSplat
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.SplatAsyncContextElement
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.splat.EmpiricalMotionModel
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext

interface Channel {
    val pendingOps: Int
    val pendingCount: Int
    val waiters: Int
    fun submit()
    suspend fun wait(minComplete: Int)
}

abstract class NamedSplatAsyncContextElement(val explicitName: String) : SplatAsyncContextElement() {
    override val key: CoroutineContext.Key<*> = object : CoroutineContext.Key<SplatAsyncContextElement> {}

    suspend fun transitionWithExplicitSplat(to: ElementState) {
        val from = lifecycleState
        val splat = stateSplatModel?.predict(from)

        Chronicle.emit(
            TransitionSplat(
                elementKey = explicitName,
                from = from,
                splat = splat,
                actual = to,
                composition = captureComposition()
            )
        )

        @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
        state = to
    }
}

class SplatChannelRunner(
    private val channel: Channel,
    private val scope: CoroutineScope,
    private val motionModel: EmpiricalMotionModel<Join<String, Int>, ChannelOutcome>
) {
    enum class ChannelOutcome { SUBMIT_MORE, WAIT_COMPLETE, DRAIN, ERROR }

    suspend fun start() {
        val element = object : NamedSplatAsyncContextElement("ChannelRunner") {
            override val stateSplatModel = motionModel.asStateAdapter()
            override fun captureComposition(): Join<String, Series<String>> =
                "ChannelRunner".j(channel.pendingOps.j { i: Int -> "op_$i" })
        }

        element.open()

        while (element.lifecycleState != ElementState.CLOSED) {
            val context = "ChannelRunner".j(channel.pendingCount)
            val outcome = when {
                channel.pendingCount > 0 -> ChannelOutcome.SUBMIT_MORE
                channel.waiters > 0 -> ChannelOutcome.WAIT_COMPLETE
                else -> ChannelOutcome.DRAIN
            }

            val actualState = when(outcome) {
                ChannelOutcome.DRAIN -> ElementState.DRAINING
                else -> ElementState.ACTIVE
            }

            element.transitionWithExplicitSplat(actualState)

            when (outcome) {
                ChannelOutcome.SUBMIT_MORE -> channel.submit()
                ChannelOutcome.WAIT_COMPLETE -> channel.wait(minComplete = 1)
                ChannelOutcome.DRAIN -> element.drain()
                else -> {}
            }
        }
    }
}
