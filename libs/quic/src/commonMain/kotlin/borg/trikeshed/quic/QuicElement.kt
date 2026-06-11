package borg.trikeshed.quic

import kotlin.coroutines.CoroutineContext

// Stub element until proper integration
class QuicElement : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = QuicKey

    suspend fun open() {}
    suspend fun drain() {}
    suspend fun close() {}
}

suspend fun openQuicElement(): QuicElement {
    val element = QuicElement()
    element.open()
    return element
}

object QuicKey : CoroutineContext.Key<QuicElement>
