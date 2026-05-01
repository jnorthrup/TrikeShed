package borg.trikeshed.userspace.nio

import borg.trikeshed.context.AsyncContextElement as CtxAsyncContextElement
import borg.trikeshed.context.NioUserspaceElement as CtxNioUserspaceElement
import borg.trikeshed.context.UserspaceNioSpi as CtxUserspaceNioSpi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class UserspaceNioProvider : CtxUserspaceNioSpi {
    interface EventListener {
        suspend fun onEvent(event: Any)
    }

    class NioElement(val fd: Int) : CtxNioUserspaceElement(), EventListener {
        override suspend fun onEvent(event: Any) {
            // Minimal marker hook for structured fanout.
        }
    }

    override suspend fun open(fd: Int): CtxNioUserspaceElement =
        NioElement(fd).also { it.open() }

    override suspend fun close(element: CtxNioUserspaceElement) {
        element.close()
    }

    override suspend fun fanout(event: Any, listeners: List<CtxAsyncContextElement>) {
        coroutineScope {
            listeners.forEach { listener ->
                launch {
                    (listener as? EventListener)?.onEvent(event)
                }
            }
        }
    }
}
