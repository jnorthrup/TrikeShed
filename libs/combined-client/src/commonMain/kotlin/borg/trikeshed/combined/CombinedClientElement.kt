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
 * It also supports dispatching IPFS and Reactor commands by translating them into
 * underlying tasks, primarily leveraging Aria2c for HTX/IPFS multi-protocol transfers.
 *
 * This element acts as a SupervisorJob articulation assembly context.
 */
class CombinedClientElement(
    val quic: QuicElement = QuicElement(),
    val sctp: SctpElement = SctpElement(),
    val htx: HtxElement = HtxElement(),
    val reactor: ReactorElement = ReactorElement()
) : AsyncContextElement() {
    companion object Key : AsyncContextKey<CombinedClientElement>()

    override val key: AsyncContextKey<CombinedClientElement> get() = Key

    override suspend fun open() {
        super.open()
        quic.open()
        sctp.open()
        htx.open()
        reactor.open()
    }

    override suspend fun close() {
        reactor.close()
        htx.close()
        sctp.close()
        quic.close()
        super.close()
    }

    suspend fun executeRpc(command: String, args: List<String>): String {
        requireState(ElementState.OPEN)
        return when (command.lowercase()) {
            "quic" -> "QUIC target executed: " + args.joinToString(" ")
            "sctp" -> "SCTP target executed: " + args.joinToString(" ")
            "ipfs", "htx" -> {
                // IPFS and HTX use aria2c premise
                val request = HtxClientRequest(
                    method = "GET",
                    path = args.firstOrNull() ?: "/",
                    // uris and switches decoupled from HtxClientRequest
                )
                // Passing the correct request object
                val response = htx.requestHandler(request)
                "HTX/IPFS target executed. Status: ${response.status}"
            }
            "reactor" -> {
                reactor.process(args.firstOrNull() ?: "")
                "Reactor target executed: " + args.joinToString(" ")
            }
            else -> "Unknown command: $command"
        }
    }
}
