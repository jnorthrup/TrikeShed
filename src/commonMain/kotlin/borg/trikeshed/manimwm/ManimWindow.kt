package borg.trikeshed.manimwm

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.Job

/**
 * Common window representation for manim-wm, adhering to PRELOAD.md concepts.
 * Uses SPIs instead of expect/actual to decouple native window bindings.
 */
class ManimWindowElement(
    parentJob: Job? = null
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<ManimWindowElement>()
    override val key = Key
}
