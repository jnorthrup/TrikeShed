package borg.trikeshed.htx.client.ipfs

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.*

/**
 * HTX Client IPFS Integration Demo Test
 */
class IpfsIntegrationDemo {

    @Test
    fun demoContentAddressableStorage() = runBlocking {
        println("\n═══════════════════════════════════════════════════════════════")
        println("  HTX Client IPFS Integration Demo")
        println("═══════════════════════════════════════════════════════════════")
        
        val blockStore = MemoryBlockStore()
        val cak = CakManager(blockStore)
        val dht = DhtService()
        
        // ── 1. Content-Addressable Storage ──
        println("\n▶ 1. Content-Addressable Put/Get")
        val data = "Hello, IPFS! This is content-addressable storage.".toByteArray()
        val cid = cak.put(data)
        println("  PUT: $cid")
        println("  Size: ${data.size} bytes")
        println("  SHA-256: ${cid.hex()}")
        
        val retrieved = cak.get(cid) ?: byteArrayOf()
        println("  GET: ${String(retrieved)}")
        assertEquals(data.contentToString(), retrieved.contentToString())
        assertTrue(cid == CID.sha256(retrieved))
        
        // ── 2. Multiple blocks ──
        println("\n▶ 2. Multiple Blocks & Pinning")
        val blocks = listOf(
            "Block 1: First piece of data".toByteArray(),
            "Block 2: Second piece of data".toByteArray(),
            "Block 3: Third piece of data".toByteArray(),
        )
        val cids = blocks.map { cak.put(it) }
        cids.forEach { println("  Pinning $it") }
        cids.forEach { cak.pin(it) }
        println("  All ${cids.size} blocks pinned")
        
        // ── 3. DHT Provider Announcement ──
        println("\n▶ 3. DHT Provider Discovery")
        cids.forEach { cid -> dht.announceProvider(cid, "local-peer-1") }
        val providers = dht.findProviders(cids[0])
        println("  Providers for ${cids[0]}: $providers")
        assertTrue(providers.contains("local-peer-1"))
        
        // ── 4. CAR Archive ──
        println("\n▶ 4. CAR Archive (v2)")
        val carData = cak.exportCar(cids)
        println("  Exported CAR: ${carData.size} bytes")
        println("  Magic: 0x${"%04x".format(carData[0].toInt() and 0xFF | (carData[1].toInt() and 0xFF) shl 8)}")
        println("  Version: ${carData[2]}")
        
        val parsed = cak.importCar(carData)
        println("  Imported: ${parsed.blockCount} blocks, version ${parsed.version}")
        
        // ── 5. Content Verification ──
        println("\n▶ 5. Integrity Verification")
        val allVerified = cids.map { cid ->
            val data = cak.get(cid) ?: byteArrayOf()
            val ok = CID.sha256(data).bytes.contentEquals(cid.bytes)
            println("  ${cid.hex().take(16)}... → ${if (ok) "✓ VERIFIED" else "✗ FAILED"}")
            ok
        }
        assertTrue(allVerified.all { it })
        println("  All ${allVerified.count { it }} blocks verified")
        
        // ── Summary ──
        println("\n═══════════════════════════════════════════════════════════════")
        println("  HTX Client IPFS Summary:")
        println("  • CID (SHA-256): Content-addressable identifiers")
        println("  • BlockStore: Memory-backed, deduplicated by CID")
        println("  • DHT: Kademlia 160-bit, iterative FIND_PROVIDERS")
        println("  • CAR: v1/v2 archives with varint encoding")
        println("  • CAK Manager: Unified synchronous interface")
        println("═══════════════════════════════════════════════════════════════")
    }
}

class MemoryBlockStore : BlockStore {
    private val store = mutableMapOf<String, ByteArray>()
    private val mutex = Mutex()
    
    override suspend fun put(cid: CID, data: ByteArray) = mutex.withLock { store[cid.hex()] = data }
    override suspend fun get(cid: CID): ByteArray? = mutex.withLock { store[cid.hex()] }
}