package borg.trikeshed.htx.client.ipfs

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import java.security.MessageDigest
import kotlin.system.measureNanoTime

/**
 * HTX Client IPFS Performance Benchmarks
 */
class IpfsBenchmarks {

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    // ── Benchmark: CAK put throughput ─────────────────────────────────────

    @Test
    fun benchmarkCakPutThroughput() = runBlocking {
        val blockStore = MemoryBlockStore()
        val cak = CakManager(blockStore)

        val chunk = ByteArray(1024 * 1024)
        java.util.Random(42).nextBytes(chunk)

        val iterations = 50
        val elapsedNs = measureNanoTime {
            repeat(iterations) { cak.put(chunk) }
        }
        val elapsedMs = elapsedNs / 1_000_000.0
        val throughputMbps = (iterations * chunk.size * 8.0) / (elapsedNs / 1e9) / 1e6

        println("\n═══════════════════════════════════════════════════════════════")
        println("  CAK Put: 1MB × $iterations")
        println("  Total: ${"%.2f".format(elapsedMs)} ms, Per put: ${"%.2f".format(elapsedMs / iterations)} ms")
        println("  Throughput: ${"%.1f".format(throughputMbps)} Mbps")
        println("═══════════════════════════════════════════════════════════════")

        assertTrue(throughputMbps > 1)
    }

    // ── Benchmark: CAK get throughput (cached) ──────────────────────────────

    @Test
    fun benchmarkCakGetThroughput() = runBlocking {
        val blockStore = MemoryBlockStore()
        val cak = CakManager(blockStore)

        val chunk = ByteArray(1024 * 1024)
        java.util.Random(42).nextBytes(chunk)
        cak.put(chunk)
        val cid = CID(sha256(chunk))

        val iterations = 100
        val elapsedNs = measureNanoTime {
            repeat(iterations) { cak.get(cid) }
        }
        val elapsedMs = elapsedNs / 1_000_000.0
        val throughputMbps = (iterations * chunk.size * 8.0) / (elapsedNs / 1e9) / 1e6

        println("\n═══════════════════════════════════════════════════════════════")
        println("  CAK Get: 1MB × $iterations (cached)")
        println("  Total: ${"%.2f".format(elapsedMs)} ms, Per get: ${"%.2f".format(elapsedMs / iterations)} ms")
        println("  Throughput: ${"%.1f".format(throughputMbps)} Mbps")
        println("═══════════════════════════════════════════════════════════════")

        assertTrue(throughputMbps > 10)
    }

    // ── Benchmark: CAR export/import ────────────────────────────────────────

    @Test
    fun benchmarkCarExportImport() = runBlocking {
        val blockStore = MemoryBlockStore()
        val cak = CakManager(blockStore)

        val blockSize = 100 * 1024
        val numBlocks = 10
        val cids = mutableListOf<CID>()

        repeat(numBlocks) { i ->
            val block = ByteArray(blockSize)
            java.util.Random(i.toLong()).nextBytes(block)
            cids.add(cak.put(block))
        }

        val exportNs = measureNanoTime { cak.exportCar(cids) }
        val exportMs = exportNs / 1_000_000.0
        val totalSize = numBlocks * blockSize
        val exportMbps = (totalSize * 8.0) / (exportNs / 1e9) / 1e6

        val carData = cak.exportCar(cids)
        val importNs = measureNanoTime { cak.importCar(carData) }
        val importMs = importNs / 1_000_000.0
        val importMbps = (carData.size * 8.0) / (importNs / 1e9) / 1e6

        println("\n═══════════════════════════════════════════════════════════════")
        println("  CAR Export: $numBlocks × ${blockSize / 1024}KB")
        println("  ${"%.2f".format(exportMs)} ms, ${"%.1f".format(exportMbps)} Mbps")
        println("  CAR Import: ${carData.size} bytes")
        println("  ${"%.2f".format(importMs)} ms, ${"%.1f".format(importMbps)} Mbps")
        println("═══════════════════════════════════════════════════════════════")

        assertTrue(exportMbps > 1)
        assertTrue(importMbps > 1)
    }

    // ── Benchmark: DHT findProviders ────────────────────────────────────────

    @Test
    fun benchmarkDhtFindProviders() = runBlocking {
        val dht = DhtService()

        val numCids = 1000
        repeat(numCids) { i ->
            val data = ByteArray(32) { i.toByte() }
            val cid = CID(sha256(data))
            dht.announceProvider(cid, "peer-$i")
        }

        val targetCid = CID(sha256(ByteArray(32) { 42 }))

        val iterations = 100
        val elapsedNs = measureNanoTime {
            repeat(iterations) { dht.findProviders(targetCid) }
        }
        val elapsedMs = elapsedNs / 1_000_000.0
        val nsPerOp = elapsedNs / iterations

        println("\n═══════════════════════════════════════════════════════════════")
        println("  DHT findProviders: $numCids CIDs, $iterations lookups")
        println("  Total: ${"%.2f".format(elapsedMs)} ms, Per lookup: ${"%.0f".format(nsPerOp / 1e6)} ms")
        println("═══════════════════════════════════════════════════════════════")

        assertTrue(nsPerOp < 1_000_000)
    }

    // ── Benchmark: end-to-end in-memory fetch ─────────────────────────────

    @Test
    fun benchmarkEndToEndFetch() = runBlocking {
        val blockStore = MemoryBlockStore()
        val cak = CakManager(blockStore)

        val data = ByteArray(1024 * 1024)
        java.util.Random(99).nextBytes(data)
        val cid = cak.put(data)

        val iterations = 20
        val elapsedNs = measureNanoTime {
            repeat(iterations) { cak.fetch(cid) }
        }
        val elapsedMs = elapsedNs / 1_000_000.0
        val throughputMbps = (iterations * data.size * 8.0) / (elapsedNs / 1e9) / 1e6

        println("\n═══════════════════════════════════════════════════════════════")
        println("  End-to-end Fetch: 1MB × $iterations (in-memory)")
        println("  Total: ${"%.2f".format(elapsedMs)} ms, Per fetch: ${"%.2f".format(elapsedMs / iterations)} ms")
        println("  Throughput: ${"%.1f".format(throughputMbps)} Mbps")
        println("═══════════════════════════════════════════════════════════════")

        assertTrue(throughputMbps > 100)
    }
}
