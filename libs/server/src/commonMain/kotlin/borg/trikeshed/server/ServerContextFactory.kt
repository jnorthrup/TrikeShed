package borg.trikeshed.server

import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.client.HtxKey
import borg.trikeshed.htx.client.openHtxElement
import borg.trikeshed.quic.QuicKey
import borg.trikeshed.quic.openQuicElement
import borg.trikeshed.sctp.SctpKey
import borg.trikeshed.sctp.openSctpElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

suspend fun buildServerContext(): CoroutineContext {
    val quic = openQuicElement()
    val sctp = openSctpElement()
    val htx = openHtxElement()
    return EmptyCoroutineContext + quic + sctp + htx
}

suspend fun closeServerContext(context: CoroutineContext) {
    context[HtxKey]?.takeIf { it.state == ElementState.OPEN }?.close()
    context[SctpKey]?.takeIf { it.state == ElementState.OPEN }?.close()
    context[QuicKey]?.takeIf { it.state == ElementState.OPEN }?.close()
}
