package borg.trikeshed.cmc

import borg.trikeshed.cmc.api.CoinMarketCapAPIApi
import borg.trikeshed.cmc.api.DefaultCoinMarketCapAPIApi
import borg.trikeshed.cmc.infrastructure.GeneratedRequest
import borg.trikeshed.htx.client.HtxElement
import borg.trikeshed.htx.client.HtxKey

/**
 * CCEK assembly for CoinMarketCap API (non-pro).
 * 
 * Wires the generated CoinMarketCapAPIApi to HtxElement from htx-client lib.
 * The generated API expects: suspend (GeneratedRequest) -> String
 * HtxElement provides: suspend request(method, path, query, body) -> HtxClientMessage
 */
object CmcAssembly {
    
    /** 
     * Open a CoinMarketCapAPIApi bound to HtxElement from the current CCEK context.
     */
    suspend fun openCmcApi(): CoinMarketCapAPIApi {
        val ctx = org.jetbrains.kotlinx.coroutines.currentCoroutine.context
        val htx = HtxKey[ctx] ?: error("HtxElement not found in coroutine context")
        
        return DefaultCoinMarketCapAPIApi { req ->
            val response = htx.request(
                method = req.method.name,
                path = req.path,
                body = req.body ?: "",
            )
            response.body
        }
    }
    
    /** Convenience: open both CoinMarketCapAPIApi and HtxElement together. */
    suspend fun openCmcWithElement(
        requestHandler: suspend (borg.trikeshed.htx.client.HtxClientRequest) -> borg.trikeshed.htx.client.HtxClientMessage,
    ): Pair<CoinMarketCapAPIApi, HtxElement> {
        val htx = borg.trikeshed.htx.client.openHtxElement(requestHandler)
        val api = openCmcApi()
        return Pair(api, htx)
    }
}