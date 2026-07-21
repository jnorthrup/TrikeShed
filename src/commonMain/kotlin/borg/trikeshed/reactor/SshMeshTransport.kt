package borg.trikeshed.reactor

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.toSeries
import borg.trikeshed.litebike.taxonomy.Protocol
import borg.trikeshed.litebike.tunnel.Tunnel
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.src
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class SshMeshTransport(
    override val id: String,
    private val tlsEndpoint: TlsEndpoint,
    override val protocol: Protocol,
    override val remoteHost: String,
    override val remotePort: Int
) : Tunnel {

    override suspend fun connect() {
        tlsEndpoint.handshake()
    }

    override suspend fun close() {
        tlsEndpoint.close()
    }

    override fun read(): Flow<ByteArray> {
        return emptyFlow()
    }

    override suspend fun write(data: ByteArray) {
        tlsEndpoint.upstream(ByteSeries(data.toSeries()))
    }

    suspend fun sendConfixDoc(doc: ConfixDoc) {
        tlsEndpoint.upstream(ByteSeries(doc.src))
    }
}
