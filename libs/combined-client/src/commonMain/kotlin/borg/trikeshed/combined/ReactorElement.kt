package borg.trikeshed.combined

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState

/**
 * A placeholder element for Reactor targets.
 */
class ReactorElement : AsyncContextElement() {
    companion object Key : AsyncContextKey<ReactorElement>()

    override val key: AsyncContextKey<ReactorElement> get() = Key

    fun process(arg: String) {
        requireState(ElementState.OPEN)
        // Reactor processing logic
    }
}
