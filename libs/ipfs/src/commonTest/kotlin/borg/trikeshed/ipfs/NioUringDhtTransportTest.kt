package borg.trikeshed.ipfs

import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class NioUringDhtTransportTest {
    @Test
    fun announceAndFindAcrossServices() = runBlocking {
        // Skip on non-Linux platforms (io_uring is Linux-only)
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("linux")) {
            println("Skipping NioUringDhtTransportTest on $os (io_uring is Linux-only)")
        } else {
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
}
