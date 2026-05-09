package borg.trikeshed.htx.client

import kotlinx.coroutines.runBlocking

suspend fun main() {
    val element = HtxElement()
    element.registerTransport(HtxTransport.HTTPS, createHttpsHandler())
    val resp = element.request("GET", "https://api.coinbase.com/api/v3/brokerage/accounts")
    println("TLS OK: ${resp.status}")
    println("Body: ${resp.body.take(200)}")
}
