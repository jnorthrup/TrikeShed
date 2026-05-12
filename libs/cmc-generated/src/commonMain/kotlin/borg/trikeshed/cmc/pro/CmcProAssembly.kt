package borg.trikeshed.cmc

import borg.trikeshed.cmc.api.CoinMarketCapAPIApi
import borg.trikeshed.cmc.api.DefaultCoinMarketCapAPIApi
import borg.trikeshed.cmc.infrastructure.GeneratedRequest
import borg.trikeshed.htx.client.HtxClientRequest
import borg.trikeshed.htx.client.HtxElement
import borg.trikeshed.htx.client.HtxKey

/**
 * CCEK assembly for CoinMarketCap Pro API.
 *
 * Wires the generated CmcProApi to HtxElement from htx-client lib.
 * Injects X-CMC_PRO_API_KEY header from environment variable CMC_PRO_API_KEY.
 * The generated API expects: suspend (GeneratedRequest) -> CharSequence
 * HtxElement provides: suspend request(method, path, query, body, headers) -> HtxClientMessage
 */
object CmcProAssembly {

 private val apiKey: CharSequence
 get() = System.getenv("CMC_PRO_API_KEY")
 ?: error("CMC_PRO_API_KEY environment variable not set")

 /**
 * Open a CmcProApi bound to HtxElement from the current CCEK context.
 * Automatically injects X-CMC_PRO_API_KEY header on every request.
 */
 suspend fun openCmcProApi(): borg.trikeshed.cmc.pro.generated.api.CmcProApi {
 val ctx = org.jetbrains.kotlinx.coroutines.currentCoroutine.context
 val htx = HtxKey[ctx] ?: error("HtxElement not found in coroutine context")

 return borg.trikeshed.cmc.pro.generated.api.DefaultCmcProApi { req ->
 val response = htx.request(
 method = req.method.name,
 path = req.path,
 queryParams = req.queryParams,
 body = req.body ?: "",
 headers = mapOf("X-CMC_PRO_API_KEY" to apiKey)
 )
 response.body
 }
 }

 /** Convenience: open both CmcProApi and HtxElement together. */
 suspend fun openCmcProWithElement(
 requestHandler: suspend (HtxClientRequest) -> borg.trikeshed.htx.client.HtxClientMessage,
 ): Pair<borg.trikeshed.cmc.pro.generated.api.CmcProApi, HtxElement> {
 val htx = borg.trikeshed.htx.client.openHtxElement(requestHandler)
 val api = openCmcProApi()
 return Pair(api, htx)
 }
}
