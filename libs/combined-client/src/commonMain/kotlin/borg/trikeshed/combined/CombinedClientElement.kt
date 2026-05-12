package borg.trikeshed.combined

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.client.HtxElementCompat
import borg.trikeshed.quic.QuicElement
import borg.trikeshed.sctp.SctpElement

open class CombinedClientElement(
    val quic: QuicElement = QuicElement(),
    val sctp: SctpElement = SctpElement(),
    val htx: HtxElementCompat? = null,
) : AsyncContextElement() {
    companion object Key : AsyncContextKey<CombinedClientElement>()
    override val key: AsyncContextKey<CombinedClientElement> get() = Key

    override suspend fun open() {
        super.open()
        quic.open()
        sctp.open()
        htx?.open()
    }

    override suspend fun close() {
        htx?.close()
        sctp.close()
        quic.close()
        super.close()
    }

    suspend fun executeRpc(target: CharSequence, args: List<CharSequence>): CharSequence {
        return when (target) {
            "htx" -> {
                val h = htx ?: error("HTX element not configured")
                h.request("GET", args.firstOrNull() ?: "/health").body
            }
            "quic" -> {
                quic.openStream()
                "QUIC stream opened (${quic.activeStreams} active)"
            }
            "sctp" -> {
                sctp.openStream()
                "SCTP stream opened (${sctp.activeStreams} active)"
            }
            else -> error("Unknown RPC target: $target")
        }
    }
}
