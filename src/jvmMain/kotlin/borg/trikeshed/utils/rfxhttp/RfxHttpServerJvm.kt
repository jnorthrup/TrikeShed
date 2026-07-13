package borg.trikeshed.utils.rfxhttp

import borg.trikeshed.couch.ConfixDocStore
import borg.trikeshed.couch.ConfixDocStoreFactory
import borg.trikeshed.couch.ViewServer
import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxResponse
import borg.trikeshed.htx.HtxMethod
import borg.trikeshed.htx.HtxStatus
import borg.trikeshed.htx.HtxHeader
import borg.trikeshed.htx.HtxHeaders
import borg.trikeshed.htx.htxHeaders
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.j
import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.htx.openHtxReactorElement
import borg.trikeshed.userspace.nio.channels.spi.NioSupervisor
import borg.trikeshed.reactor.TlsApplicationProtocol
import borg.trikeshed.reactor.TlsConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class RfxHttpServerJvm(
    override val store: ConfixDocStore = ConfixDocStoreFactory.create(),
    override val viewServer: ViewServer = ViewServer(),
    private val rfAdapter: RelaxFactoryAdapter = RelaxFactoryAdapter(store, viewServer),
    private val nioSupervisor: NioSupervisor? = null
) : RfxHttpServer {

    private var reactor: HtxReactorElement? = null
    private var job: Job? = null

    override suspend fun handleRequest(request: HtxRequest): HtxResponse {
        return try {
            when (request.method) {
                HtxMethod.POST -> handlePost(request)
                HtxMethod.GET -> handleGet(request)
                else -> createResponse(405, "Method Not Allowed")
            }
        } catch (e: Exception) {
            // Generalize the server error to prevent information leakage
            createResponse(500, "Internal Server Error")
        }
    }

    private fun handlePost(request: HtxRequest): HtxResponse {
        val payload = request.body?.asString() ?: ""
        val responsePayload = rfAdapter.processIncomingRF(payload)
        return createResponse(200, responsePayload, "application/json")
    }

    private fun handleGet(request: HtxRequest): HtxResponse {
        return createResponse(200, "RFX HTTP Server running. Connect via RequestFactory GWT clients.")
    }

    private fun createResponse(statusCode: Int, body: String, contentType: String = "text/plain"): HtxResponse {
        return HtxResponse(
            status = statusCode,
            headers = htxHeaders("Content-Type" j contentType),
            body = ByteSeries(body)
        )
    }

    /**
     * Start underlying Reactor HtxElement to handle connections.
     * We initialize a full polyglot reactor configuration allowing HTTP 1/2/3 ALPN configurations.
     *
     * (Note: SCTP/QUIC implementation resides in external QUIC reactor module, so we enable HTTP/3 ALPN intent here)
     */
    override fun start() {
        val supervisorJob = SupervisorJob()
        job = supervisorJob
        val scope = CoroutineScope(supervisorJob)

        scope.launch {
            // Configure TLS / ALPN for HTTP/1.1, HTTP/2, and HTTP/3
            val tlsConfig = TlsConfig(
                alpnProtocols = borg.trikeshed.lib.seriesOf(
                    TlsApplicationProtocol.HTTP_1_1,
                    TlsApplicationProtocol.HTTP_2,
                    TlsApplicationProtocol.HTTP_3
                )
            )

            // Open the HTX Reactor via the NioSupervisor.
            reactor = openHtxReactorElement(
                nioSupervisor = nioSupervisor,
                tlsConfig = tlsConfig,
                parentJob = supervisorJob
            )

            // Note: In the Trikeshed architecture, actual port binding and routing rules
            // are configured in the Polyglot/RouteService layer. This component provides
            // the configured Reactor element prepared to accept those route assignments.
        }
    }

    override fun stop() {
        job?.cancel()
        reactor?.close()
    }
}
