package borg.trikeshed.ipfs

import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class NioUringDhtTransportTest {
    @Test
    fun announceAndFindAcrossServices() = runBlocking {
        val transport = NioUringDhtTransport()
        val svcA = DhtService(transport)
        val svcB = DhtService(transport)

        val cid = CID(byteArrayOf(9,9,9))
        svcA.announceProvider(cid, "addr-A")

        withTimeout(1_000) {
            while (!svcB.findProviders(cid).contains("addr-A")) {
                delay(10)
            }
        }

        val providers = svcB.findProviders(cid)
        assertTrue(providers.contains("addr-A"))
    }
}
