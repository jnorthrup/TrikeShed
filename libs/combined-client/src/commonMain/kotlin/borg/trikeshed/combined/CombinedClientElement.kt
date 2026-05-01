package borg.trikeshed.combined

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.client.HtxElement
import borg.trikeshed.htx.client.HtxClientRequest
import borg.trikeshed.htx.client.Aria2Switches
import borg.trikeshed.quic.QuicElement
import borg.trikeshed.sctp.SctpElement

/**
 * A combined client element that composes Quic, SCTP, and HTX (HTTP) clients.
 * It also supports dispatching IPFS commands by translating them into
 * underlying tasks, primarily leveraging Aria2c for HTX/IPFS multi-protocol transfers.
 *
 * This element acts as a SupervisorJob articulation assembly context.
 * Note: the child elements (quic, sctp, htx) have their lifecycles managed
 * by this combined element. However, because they are constructed internally by default,
 * they are not individually addressable in a `CoroutineContext` hierarchy unless
 * explicitly overridden and bound by the caller.
 */
open class CombinedClientElement(
    val quic: QuicElement = QuicElement(),
    val sctp: SctpElement = SctpElement(),
    val htx: HtxElement = HtxElement()
) : AsyncContextElement() {
    companion object Key : AsyncContextKey<CombinedClientElement>()

    override val key: AsyncContextKey<CombinedClientElement> get() = Key

    override suspend fun open() {
        super.open()
        quic.open()
        sctp.open()
        htx.open()
    }

    override suspend fun close() {
        htx.close()
        sctp.close()
        quic.close()
        super.close()
    }

    suspend fun executeRpc(command: String, args: List<String>): String {
        requireState(ElementState.OPEN)
        return when (command.lowercase()) {
            "quic" -> throw UnsupportedOperationException("QUIC transport layer not mature yet")
            "sctp" -> throw UnsupportedOperationException("SCTP transport layer not mature yet")
            "ipfs", "htx" -> {
                // IPFS and HTX use aria2c premise
                val response = htx.request(
                    method = "GET",
                    path = args.firstOrNull() ?: "/",
                    // uris and switches decoupled from HtxClientRequest
                )
                "HTX/IPFS target executed. Status: ${response.status}"
            }
            "reactor" -> throw UnsupportedOperationException("Reactor target has been removed")
            else -> "Unknown command: $command"
        }
    }
}
