package borg.trikeshed.userspace.nio

import borg.trikeshed.context.UserspaceNioSpi
import borg.trikeshed.userspace.context.AsyncContextKey
import borg.trikeshed.userspace.context.NioUserspaceElement
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class UserspaceNioProvider : UserspaceNioSpi {
    interface EventListener {
        suspend fun onEvent(event: Any)
    }

    class NioElement(val fd: Int) : NioUserspaceElement(), EventListener {
        override val key get() = AsyncContextKey.NioUserspaceKey

        override suspend fun onEvent(event: Any) {
            // Minimal marker hook for structured fanout.
        }
    }

    override suspend fun open(fd: Int): NioUserspaceElement =
        NioElement(fd).also { it.open() }

    override suspend fun close(element: NioUserspaceElement) {
        element.close()
    }

    override suspend fun fanout(event: Any, listeners: List<NioUserspaceElement>) {
        coroutineScope {
            listeners.forEach { listener ->
                launch {
                    (listener as? EventListener)?.onEvent(event)
                }
            }
        }
    }
}
