package borg.trikeshed.userspace.nio

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.UserspaceNioSpi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class UserspaceNioProvider : UserspaceNioSpi {
    interface EventListener {
        suspend fun onEvent(event: Any)
    }

    class NioElement(val fd: Int) : AsyncContextElement(), EventListener {
        override val key get() = Key

        companion object Key : AsyncContextKey<NioElement>()

        override suspend fun open() {
            requireState(ElementState.CREATED)
            state = ElementState.OPEN
        }

        override suspend fun close() {
            requireState(ElementState.OPEN)
            state = ElementState.CLOSING
            state = ElementState.CLOSED
        }

        override suspend fun onEvent(event: Any) {
            // Minimal marker hook for structured fanout.
        }
    }

    override suspend fun open(fd: Int): AsyncContextElement =
        NioElement(fd).also { it.open() }

    override suspend fun close(element: AsyncContextElement) {
        element.close()
    }

    override suspend fun fanout(event: Any, listeners: List<AsyncContextElement>) {
        coroutineScope {
            listeners.forEach { listener ->
                launch {
                    (listener as? EventListener)?.onEvent(event)
                }
            }
        }
    }
}
