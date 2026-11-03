package borg.trikeshed.vast

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.torrent.percentDecode
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class VastCatalogTest {
    @Test fun allTiersUseThePublicApiAndRetainHardwareTariffsAndUnknownFields() = runBlocking {
        val requests = CopyOnWriteArrayList<HtxRequest>()
        val quote = _m[
            "id" j 12, "machine_id" j 34, "gpu_name" j "RTX 4090", "gpu_ram" j 24576,
            "dph_total" j 0.4, "storage_cost" j 0.15, "inet_up_cost" j 0.03,
            "internet_down_cost_per_tb" j 2.5, "new_vendor_tariff" j _m["value" j 9],
        ]
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                requests.add(request)
                val body = jsonOf(_m["offers" j _l[quote], "truncated" j false, "vendor_cursor" j "next"])
                return HtxExchangeResult(state.copy(response = HtxResponse(200, ByteSeries(body.encodeToByteArray()))))
            }
        })
        try {
            val catalog = withContext(htx) { VastCatalog.search("RTX 4090") }
            val tiers = catalog["tiers"] as List<*>
            assertEquals(VastTier.entries.size, tiers.size)
            assertEquals(_s["ondemand", "bid", "reserved"], requests.map { query(it)["type"] }.toSet())
            requests.forEach { request ->
                assertEquals("console.vast.ai", request.target.host)
                assertEquals(HtxMethod.GET, request.method)
                assertTrue(request.headers.view.none { it.a.equals("Authorization", ignoreCase = true) })
                assertFalse(request.target.requestPath.contains("api_key"))
                val query = query(request)
                assertEquals(_m["eq" j "RTX 4090"], query["gpu_name"])
                assertFalse(query.containsKey("verified"))
                assertFalse(query.containsKey("gpu_ram"))
                assertFalse(query.containsKey("num_gpus"))
            }
            tiers.forEach { value ->
                val tier = value as Map<*, *>
                val retained = (tier["offers"] as List<*>).single() as Map<*, *>
                assertEquals("RTX 4090", retained["gpu_name"])
                assertEquals(quote.keys, retained.keys)
                assertEquals(9.0, (retained["new_vendor_tariff"] as Map<*, *>)["value"])
                assertEquals("next", (tier["metadata"] as Map<*, *>)["vendor_cursor"])
                assertEquals(false, tier["possiblyLimited"])
            }
        } finally { htx.close() }
    }

    @Test fun tierTimeoutAndHttpFailureDoNotEraseHealthyQuotes() = runBlocking { withTimeout(5_000) {
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val tier = query(request)["type"]
                if (tier == "bid") delay(1000)
                val response = if (tier == "reserved") HtxResponse(503)
                    else HtxResponse(200, ByteSeries("""{"offers":[{"id":7}],"truncated":false}""".encodeToByteArray()))
                return HtxExchangeResult(state.copy(response = response))
            }
        })
        try {
            val result = withContext(htx) { VastCatalog.search(timeoutMs = 50) }
            val tiers = (result["tiers"] as List<*>).associate { value ->
                val tier = value as Map<*, *>; (tier["tier"] as String j tier).pair
            }
            assertEquals(1, (tiers.getValue("ondemand")["offers"] as List<*>).size)
            assertEquals("Vast request timed out", tiers.getValue("bid")["error"])
            assertEquals("Vast returned HTTP 503", tiers.getValue("reserved")["error"])
        } finally { htx.close() }
    }
    }

    @Test fun theObserved64CapIsNotPresentedAsACompleteInventory() = runBlocking {
        val offers = ((0 until 64).toSeries() α { _m["id" j it] }).view.toList()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult =
                HtxExchangeResult(state.copy(response = HtxResponse(200,
                    ByteSeries(jsonOf(_m["offers" j offers, "truncated" j false]).encodeToByteArray()))))
        })
        try {
            val result = withContext(htx) { VastCatalog.search() }
            (result["tiers"] as List<*>).forEach { value ->
                val tier = value as Map<*, *>
                assertEquals(false, tier["truncated"])
                assertEquals(true, tier["possiblyLimited"])
                assertEquals(64, (tier["offers"] as List<*>).size)
            }
        } finally { htx.close() }
    }

    @Test fun callerCancellationDrainsTheCatalogWorkers() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                entered.complete(Unit)
                delay(10000)
                error("cancelled request must not finish")
            }
        })
        try {
            val request = launch(htx) { VastCatalog.search() }
            entered.await()
            withTimeout(1000) { request.cancelAndJoin() }
            assertTrue(request.isCancelled)
            assertTrue(request.children.none())
        } finally { htx.close() }
    }

    fun query(request: HtxRequest): Map<*, *> =
        reifyStrict(percentDecode(request.target.requestPath.substringAfter("?q="))) as Map<*, *>
}
