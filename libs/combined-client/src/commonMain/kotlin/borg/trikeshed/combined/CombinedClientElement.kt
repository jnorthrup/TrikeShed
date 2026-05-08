package borg.trikeshed.combined

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.client.HtxElement
import borg.trikeshed.htx.client.HtxTransport
import borg.trikeshed.quic.QuicElement
import borg.trikeshed.sctp.SctpElement
import borg.trikeshed.ipfs.IpfsElement
import borg.trikeshed.ipfs.CID

/**
 * A combined client element that composes Quic, SCTP, HTX, IPFS, and Couch transports
 * under one SupervisorJob. The root assembly for the cross-module reactor.
 *
 * Protocol Elements (quic, sctp, htx, ipfs) are composed as CCEK siblings in the
 * coroutine context. HtxElement uses registered transport handlers to dispatch
 * requests to QuicElement, SctpElement, or TCP depending on the URI scheme.
 *
 * IpfsElement can access the CouchElement through cross-context key lookup,
 * enabling DHT peer discovery from couch collections.
 */
open class CombinedClientElement(
    val quic: QuicElement = QuicElement(),
    val sctp: SctpElement = SctpElement(),
    val htx: HtxElement = HtxElement(),
    val ipfs: IpfsElement? = null,
    val couch: Any? = null,
) : AsyncContextElement() {
    companion object Key : AsyncContextKey<CombinedClientElement>()

    override val key: AsyncContextKey<CombinedClientElement> get() = Key

    override suspend fun open() {
        super.open()
        quic.open()
        sctp.open()
        htx.open()

        htx.registerTransport(HtxTransport.QUIC) { request ->
            throw UnsupportedOperationException("QUIC transport forwarding not yet implemented — htx-general-client on QUIC")
        }
        htx.registerTransport(HtxTransport.SCTP) { request ->
            throw UnsupportedOperationException("SCTP transport forwarding not yet implemented — htx-general-client on ngSCTP")
        }
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
            "ipfs" -> {
                val uri = args.firstOrNull() ?: "/"
                ipfs?.let { svc ->
                    val cid = CID(uri.toByteArray())
                    val data = svc.get(cid)
                    if (data != null) {
                        "IPFS: found ${'$'}{data.size} bytes"
                    } else {
                        val response = htx.request(method = "GET", path = uri)
                        "HTX/IPFS target executed. Status: ${'$'}{response.status}"
                    }
                } ?: run {
                    val response = htx.request(method = "GET", path = uri)
                    "HTX/IPFS target executed. Status: ${'$'}{response.status}"
                }
            }
            "htx" -> {
                val response = htx.request(method = "GET", path = args.firstOrNull() ?: "/")
                "HTX target executed. Status: ${'$'}{response.status}"
            }
            "couch" -> {
                val path = args.firstOrNull() ?: "/"
                val response = htx.request(method = "GET", path = path)
                "Couch target executed via HTX. Status: ${'$'}{response.status}"
            }
            else -> "Unknown command: $command"
        }
    }
}
