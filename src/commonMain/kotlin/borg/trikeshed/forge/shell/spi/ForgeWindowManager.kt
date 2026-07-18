package borg.trikeshed.forge.shell.spi

import kotlin.coroutines.CoroutineContext

interface ForgeWindowManager : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<ForgeWindowManager>

    fun bind(html: String)
    fun injectScript(script: String)
    fun dispatchEvent(event: String, payload: String)
    suspend fun captureSnapshot(): ByteArray
}
