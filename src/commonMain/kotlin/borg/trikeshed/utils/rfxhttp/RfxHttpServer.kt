package borg.trikeshed.utils.rfxhttp

import borg.trikeshed.couch.ConfixDocStore
import borg.trikeshed.couch.ViewServer
import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxResponse
import borg.trikeshed.htx.HtxMethod

interface RfxHttpServer {
    val store: ConfixDocStore
    val viewServer: ViewServer

    suspend fun handleRequest(request: HtxRequest): HtxResponse
    fun start()
    fun stop()
}

interface RequestFactoryHandler {
    suspend fun processRequest(payload: String): String
}
