package borg.trikeshed.context

import borg.trikeshed.chronicle.Chronicle
import borg.trikeshed.chronicle.TransitionSplat
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.splat.SplatModel

abstract class SplatAsyncContextElement : AsyncContextElement() {

    open val stateSplatModel: SplatModel<ElementState, ElementState>? = null

    suspend fun transitionWithSplat(to: ElementState) {
        val from = lifecycleState
        val splat = stateSplatModel?.predict(from)

        Chronicle.emit(
            TransitionSplat(
                elementKey = this::class.simpleName ?: "unknown",
                from = from,
                splat = splat,
                actual = to,
                composition = captureComposition()
            )
        )

        @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
        state = to
    }

    abstract fun captureComposition(): Join<String, Series<String>>
}
