package borg.trikeshed.ipfs

import kotlin.test.*
import kotlinx.coroutines.runBlocking

class NioUringDhtTransportTest {
    @Test
    fun announceAndFindAcrossServices() = runBlocking {
        val transport = NioUringDhtTransport()
        val svcA = DhtService(transport)
        val svcB = DhtService(transport)

        val cid = CID(byteArrayOf(9,9,9))
        svcA.announceProvider(cid, "addr-A")

        val providers = svcB.findProviders(cid)
        assertTrue(providers.contains("addr-A"))
    }
}
