package borg.trikeshed.server.generated

import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.htx.client.HtxKey
import borg.trikeshed.htx.client.HtxElementCompat
import borg.trikeshed.quic.QuicKey
import borg.trikeshed.quic.QuicElement
import borg.trikeshed.sctp.SctpKey
import borg.trikeshed.sctp.SctpElement

object Keys {
    val htx: AsyncContextKey<HtxElementCompat> = HtxKey
    val quic: AsyncContextKey<QuicElement> = QuicKey
    val sctp: AsyncContextKey<SctpElement> = SctpKey
    const val operationId: CharSequence = "getHealth"
}
