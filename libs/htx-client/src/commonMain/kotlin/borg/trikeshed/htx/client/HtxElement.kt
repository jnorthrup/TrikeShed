package borg.trikeshed.htx.client

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState

// Compromise: this standalone lib does not depend on the root project to avoid composite-build cycles.
data class HtxClientMessage(val status: Int = 200, val body: String = "ok")

val HtxKey: AsyncContextKey<HtxElement> = HtxElement.Key

suspend fun openHtxElement(): AsyncContextElement =
    HtxElement().also { it.open() }

class HtxElement : AsyncContextElement() {
    companion object Key : AsyncContextKey<HtxElement>()

    override val key: AsyncContextKey<HtxElement>
        get() = Key

    override suspend fun open() {
        requireState(ElementState.CREATED)
        state = ElementState.OPEN
    }

    override suspend fun close() {
        requireState(ElementState.OPEN)
        state = ElementState.CLOSING
        state = ElementState.CLOSED
    }

    suspend fun request(
        method: String = "GET",
        path: String = "/",
        body: String = "",
    ): HtxClientMessage {
        requireState(ElementState.OPEN)
        return HtxClientMessage(status = if (method.isBlank() || path.isBlank()) 400 else 200, body = body.ifEmpty { "ok" })
    }
}