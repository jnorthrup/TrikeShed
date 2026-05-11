package borg.trikeshed.htx.client

import kotlinx.coroutines.runBlocking

/** Standalone runner to test QUIC against google.com */
fun main() = runBlocking {
    val elem = HtxElement()

    // HTTPS control
    elem.registerTransport(HtxTransport.HTTPS, createHttpsHandler())
    val httpsResp = elem.request("GET", "https://www.google.com/")
    println("HTTPS: status=${httpsResp.status}")

    // QUIC
    elem.registerTransport(HtxTransport.QUIC, createQuicHandler())
    val quicResp = elem.request("GET", "quic://www.google.com:443/", transport = HtxTransport.QUIC)
    println("QUIC: status=${quicResp.status}, body='${quicResp.body}'")
}