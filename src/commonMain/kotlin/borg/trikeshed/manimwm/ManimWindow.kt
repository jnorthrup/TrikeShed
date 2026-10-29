package borg.trikeshed.manimwm

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job

/**
 * Common window representation for manim-wm, adhering to PRELOAD.md concepts.
 * Uses SPIs instead of expect/actual to decouple native window bindings.
 */
class ManimWindowElement(
    parentJob: Job? = null
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ManimWindowElement>
    override val key = Key
}
