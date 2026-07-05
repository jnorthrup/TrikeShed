package borg.trikeshed.ipfs

import kotlin.test.*
import kotlinx.coroutines.runBlocking

class DhtServiceTest {
    @Test
    fun announceFind() = runBlocking {
        val svc = DhtService()
        val cid = CID(byteArrayOf(4,5,6))
        svc.announceProvider(cid, "addr1")
        svc.announceProvider(cid, "addr2")
        val providers = svc.findProviders(cid)
        assertTrue(providers.contains("addr1"))
        assertTrue(providers.contains("addr2"))
    }
}
