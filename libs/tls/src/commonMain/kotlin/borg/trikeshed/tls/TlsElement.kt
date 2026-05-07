package borg.trikeshed.tls

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey

open class TlsElement(val engine: TlsEngine? = null) : AsyncContextElement() {
    companion object Key : AsyncContextKey<TlsElement>()
    override val key: AsyncContextKey<TlsElement> get() = Key

    override suspend fun open() {
        super.open()
        // handshake would happen here in a real engine
    }

    override suspend fun close() {
        engine?.close()
        super.close()
    }
}
