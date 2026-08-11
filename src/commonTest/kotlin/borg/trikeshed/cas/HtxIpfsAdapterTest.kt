package borg.trikeshed.cas

import borg.trikeshed.htx.HtxExchangeResult
import borg.trikeshed.htx.HtxExchangeState
import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxResponse
import borg.trikeshed.htx.HtxRouteService
import borg.trikeshed.htx.htxFrames
import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.ByteSeries
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HtxIpfsAdapterTest {

    @Test
    fun testFallbackWhenNoHtx() = runTest {
        val cas = CasStore.inMemory()
        val bridge = IpfsBridge(cas)
        val adapter = HtxIpfsAdapter(bridge)

        val data = "hello world".encodeToByteArray()
        val cid = adapter.putBlock(data)

        val read = adapter.getBlock(cid)
        assertNotNull(read)
        assertEquals("hello world", read.decodeToString())
    }

    @Test
    fun testNetworkWhenHtxPresent() = runTest {
        val cas = CasStore.inMemory()
        val bridge = IpfsBridge(cas)
        val adapter = HtxIpfsAdapter(bridge)

        val fakeCid = "sha256:fakecid123123123123123123123123123123123123123123123123123123123"

        val mockService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val path = request.target.requestPath
                val responseBody = when {
                    path.endsWith("/block/put") -> """{"Key":"$fakeCid","Size":11}"""
                    path.contains("/block/get") -> "hello network"
                    path.endsWith("/name/publish") -> """{"Name":"myname","Value":"$fakeCid"}"""
                    path.contains("/name/resolve") -> """{"Path":"/ipfs/$fakeCid"}"""
                    else -> "{}"
                }
                val response = HtxResponse(200, ByteSeries(responseBody.encodeToByteArray()))
                return HtxExchangeResult(state.copy(response = response), htxFrames())
            }
        }

        withContext(openHtxElement(routeService = mockService)) {
            val cid = adapter.putBlock("hello network".encodeToByteArray())
            assertEquals(ContentId(fakeCid), cid)

            val read = adapter.getBlock(cid)
            assertNotNull(read)
            assertEquals("hello network", read.decodeToString())

            adapter.publishIpns("myname", cid)
            val resolved = adapter.resolveIpns("myname")
            assertEquals(ContentId(fakeCid), resolved)
        }
    }
}
