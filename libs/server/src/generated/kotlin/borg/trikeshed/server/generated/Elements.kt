package borg.trikeshed.server.generated

import borg.trikeshed.htx.HtxElement
import borg.trikeshed.htx.openHtxElement as openHtxElementRuntime
import borg.trikeshed.quic.QuicElement
import borg.trikeshed.quic.openQuicElement as openQuicElementRuntime
import borg.trikeshed.sctp.SctpElement
import borg.trikeshed.sctp.openSctpElement as openSctpElementRuntime

object Elements {
    suspend fun htx(): HtxElement = openHtxElementRuntime()
    suspend fun quic(): QuicElement = openQuicElementRuntime()
    suspend fun sctp(): SctpElement = openSctpElementRuntime()
}
