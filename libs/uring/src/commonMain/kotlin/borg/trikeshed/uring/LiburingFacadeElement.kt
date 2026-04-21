package borg.trikeshed.uring

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState

class LiburingFacadeElement : AsyncContextElement() {
    override val key get() = Key

    companion object Key : AsyncContextKey<LiburingFacadeElement>()

    override suspend fun open() {
        requireState(ElementState.CREATED)
        state = ElementState.OPEN
    }

    override suspend fun close() {
        requireState(ElementState.OPEN)
        state = ElementState.CLOSING
        state = ElementState.CLOSED
    }
}
