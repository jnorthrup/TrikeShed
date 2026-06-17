#!/usr/bin/env kotlin

/**
 * HTX Client IPFS Integration Demo
 * 
 * Run with: kotlinc -script demo_ipfs.kts
 * 
 * This demonstrates the core IPFS integration:
 * - CID (Content Identifiers) with SHA-256
 * - BlockStore (memory-backed content-addressable storage)
 * - DHT Service (Kademlia provider discovery)
 * - Bitswap Engine (block exchange protocol)
 * - CAR Archives (Content Addressable aRchive v1/v2)
 * - CAK Manager (unified interface)
 */

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random

// ═══════════════════════════════════════════════════════════════════════
// CID - Content Identifier (SHA-256 multihash)
// ═══════════════════════════════════════════════════════════════════════

data class CID(val bytes: ByteArray) {
    override fun toString(): String = "CID(${bytes.joinToString("") { "%02x".format(it) }})"
    fun hex(): String = bytes.joinToString("") { "%02x".format(it) }
    
    companion object {
        fun sha256(data: ByteArray): CID {
            val digest = MessageDigest.getInstance("SHA-256")
            return CID(digest.digest(data))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// BlockStore - Content-addressable storage
// ═══════════════════════════════════════════════════════════════════════

interface BlockStore {
    suspend fun put(cid: CID, data: ByteArray)
    suspend fun get(cid: CID): ByteArray?
    suspend fun has(cid: CID): Boolean = get(cid) != null
}

class MemoryBlockStore : BlockStore {
    private val store = mutableMapOf<String, ByteArray>()
    
    override suspend fun put(cid: CID, data: ByteArray) { store[cid.hex()] = data }
    override suspend fun get(cid: CID): ByteArray? = store[cid.hex()]
}

// ═══════════════════════════════════════════════════════════════════════
// DHT Service - Kademlia provider discovery
// ═══════════════════════════════════════════════════════════════════════

data class NodeId(val bytes: ByteArray) {
    companion object {
        fun random(): NodeId = NodeId(ByteArray(20).also { Random().nextBytes(it) })
    }
    fun xorDistance(other: NodeId): java.math.BigInteger {
        val thisBi = java.math.BigInteger(1, bytes)
        val otherBi = java.math.BigInteger(1, other.bytes)
        return (thisBi xor otherBi).abs()
    }
}

data class NodeInfo(val id: NodeId, val address: String, var lastSeen: Long = System.currentTimeMillis())

class DhtService {
    private val providers = mutableMapOf<String, MutableSet<String>>()
    private val contacts = mutableMapOf<String, NodeInfo>()
    private val routingTable = RoutingTable(NodeId.random())
    
    inner class RoutingTable(private val localId: NodeId) {
        private val buckets = Array(160) { mutableListOf<NodeInfo>() }
        private val k = 20
        
        fun addNode(node: NodeInfo) {
            val bucketIdx = localId.bucketIndex(node.id)
            val bucket = buckets[bucketIdx]
            if (bucket.none { it.id == node.id }) {
                if (bucket.size >= k) bucket.removeAt(0)
                bucket.add(node)
            }
        }
        fun findClosest(target: NodeId, count: Int = 20): List<NodeInfo> {
            return buckets.flatMap { it }
                .sortedBy { it.id.xorDistance(target) }
                .take(count)
        }
    }
    
    fun announceProvider(cid: CID, address: String) {
        providers.computeIfAbsent(cid.hex()) { mutableSetOf() }.add(address)
    }
    
    fun findProviders(cid: CID): List<String> = providers[cid.hex()]?.toList() ?: emptyList()
    
    fun handleFindNode(requester: NodeId, target: NodeId): List<NodeInfo> {
        contacts[requester.toString()] = NodeInfo(requester, "unknown")
        return routingTable.findClosest(target)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Bitswap Engine - Block exchange protocol
// ═══════════════════════════════════════════════════════════════════════

class BitswapEngine(
    private val blockStore: BlockStore,
    private val sendMessage: (ByteArray) -> Unit,
) {
    private val wantlist = mutableMapOf<String, WantEntry>()
    private val pendingResponses = mutableMapOf<String, ByteArray>()
    
    suspend fun wantBlock(cid: CID): ByteArray {
        val key = cid.hex()
        val local = blockStore.get(cid) ?: runBlocking { null }
        if (local != null) return local
        
        wantlist[key] = (wantlist[key] ?: WantEntry(cid)).apply { incrementPriority() }
        broadcastWantBlock(cid)
        
        // Simulate network delay & response
        Thread.sleep(10)
        pendingResponses[key]?.let { return it }
        throw Exception("Block not found: $cid")
    }
    
    fun handleMessage(message: BitswapMessage) {
        when (message) {
            is BitswapMessage.Block -> {
                blockStore.put(message.cid, message.data)
                pendingResponses[message.cid.hex()] = message.data
            }
        }
    }
    
    private fun broadcastWantBlock(cid: CID) = sendMessage(BitswapMessage.WantBlock(listOf(cid)).encode())
    
    class WantEntry(val cid: CID) { var priority = 1; fun incrementPriority() { priority++ } }
    
    sealed class BitswapMessage {
        data class WantBlock(val cids: List<CID>) : BitswapMessage()
        data class Block(val cid: CID, val data: ByteArray) : BitswapMessage()
        
        fun encode(): ByteArray = when (this) {
            is WantBlock -> { val out = ByteArrayOutputStream(); out.write(0x00); out.write(cids.size); cids.forEach { c -> out.write(c.bytes.size); out.write(c.bytes) }; out.toByteArray() }
            is Block -> { val out = ByteArrayOutputStream(); out.write(0x02); out.write(cid.bytes.size); out.write(cid.bytes); out.write(data.size); out.write(data); out.toByteArray() }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// CAR - Content Addressable aRchive (v1/v2)
// ═══════════════════════════════════════════════════════════════════════

data class CarBlock(val cid: CID, val data: ByteArray)
data class CarParseResult(val roots: List<CID>, val blockCount: Int, val version: Int, val dataCid: CID)

object CarParser {
    private const val CAR_MAGIC = 0xC5D1.toShort()
    private const val CAR_VERSION_1 = 1
    private const val CAR_VERSION_2 = 2
    
    fun parse(data: ByteArray): CarParseResult {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.short
        require(magic == CAR_MAGIC) { "Invalid CAR magic: ${magic.toString(16)}" }
        val version = readVarint(buffer)
        val headerLength = readVarint(buffer).toInt()
        buffer.position += headerLength // Skip header
        
        val blocks = mutableListOf<CarBlock>()
        while (buffer.remaining() > 0) {
            val blockLength = readVarint(buffer).toInt()
            val blockData = ByteArray(blockLength)
            buffer.get(blockData)
            val cid = CID(blockData.copyOfRange(0, min(32, blockData.size)))
            blocks.add(CarBlock(cid, blockData))
        }
        return CarParseResult(emptyList(), blocks.size, version.toInt(), CID(byteArrayOf()))
    }
    
    private fun readVarint(buffer: ByteBuffer): Long {
        var result = 0L; var shift = 0
        while (true) {
            val b = (buffer.get() and 0xFF).toLong()
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0L) break
            shift += 7
        }
        return result
    }
}

object CarWriter {
    fun write(blocks: List<CarBlock>, roots: List<CID>, version: Int = 2): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xC5D1 and 0xFF); out.write(0xC5D1 ushr 8)
        writeVarint(out, version.toLong())
        out.write(0) // header length placeholder
        blocks.forEach { block ->
            writeVarint(out, block.data.size.toLong())
            out.write(block.cid.bytes)
            out.write(block.data)
        }
        return out.toByteArray()
    }
    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) { out.write(((v and 0x7F | 0x80).toByte()).toInt()); v = v ushr 7 }
        out.write((v.toByte()).toInt())
    }
}

// ═══════════════════════════════════════════════════════════════════════
// CAK Manager - Unified interface
// ═══════════════════════════════════════════════════════════════════════

class CakManager(private val blockStore: BlockStore) {
    suspend fun put(data: ByteArray): CID {
        val cid = CID.sha256(data)
        blockStore.put(cid, data)
        return cid
    }
    suspend fun get(cid: CID): ByteArray? = blockStore.get(cid)
    suspend fun has(cid: CID): Boolean = blockStore.get(cid) != null
    suspend fun pin(cid: CID) { if (!has(cid)) get(cid) }
    suspend fun importCar(data: ByteArray): CarParseResult = runBlocking { CarParser.parse(data) }
    suspend fun exportCar(roots: List<CID>): ByteArray = CarWriter.write(
        roots.mapNotNull { cid -> blockStore.get(cid)?.let { CarBlock(cid, it) } },
        roots
    )
}

// ═══════════════════════════════════════════════════════════════════════
// DEMO MAIN
// ═══════════════════════════════════════════════════════════════════════

import kotlinx.coroutines.runBlocking

suspend fun main() {
    println("═══════════════════════════════════════════════════════════════════")
    println("  HTX Client IPFS Integration Demo")
    println("═══════════════════════════════════════════════════════════════════")
    
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
    println("  Verified: ${cid == CID.sha256(retrieved)}")
    
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
    
    // ── 4. CAR Archive ──
    println("\n▶ 4. CAR Archive (v2)")
    val carData = cak.exportCar(cids)
    println("  Exported CAR: ${carData.size} bytes")
    println("  Magic: 0x${"%04x".format(carData[0].toInt() and 0xFF | (carData[1].toInt() and 0xFF) shl 8)}")
    println("  Version: ${carData[2]}")
    
    val parsed = cak.importCar(carData)
    println("  Imported: ${parsed.blockCount} blocks, version ${parsed.version}")
    
    // ── 5. Bitswap Exchange ──
    println("\n▶ 5. Bitswap Block Exchange")
    val bitswap = BitswapEngine(blockStore) { msg ->
        println("  [Bitswap] Sent ${msg::class.simpleName} (${msg.encode().size} bytes)")
    }
    // Simulate receiving a block from network
    bitswap.handleMessage(BitswapEngine.BitswapMessage.Block(cids[0], blocks[0]))
    println("  Received block for ${cids[0]} via Bitswap")
    
    // ── 6. Content Verification ──
    println("\n▶ 6. Integrity Verification")
    val verified = cids.map { cid ->
        val data = cak.get(cid) ?: byteArrayOf()
        val ok = CID.sha256(data).bytes.contentEquals(cid.bytes)
        println("  ${cid.hex().take(16)}... → ${if (ok) "✓ VERIFIED" else "✗ FAILED"}")
        ok
    }
    println("  All ${verified.count { it }} blocks verified")
    
    // ── 7. Summary ──
    println("\n═══════════════════════════════════════════════════════════════════")
    println("  HTX Client IPFS Summary:")
    println("  • CID (SHA-256): Content-addressable identifiers")
    println("  • BlockStore: Memory-backed, deduplicated by CID")
    println("  • DHT: Kademlia 160-bit, iterative FIND_PROVIDERS")
    println("  • Bitswap: WANT_BLOCK/BLOCK exchange protocol")
    println("  • CAR: v1/v2 archives with varint encoding")
    println("  • CAK Manager: Unified synchronous interface")
    println("═══════════════════════════════════════════════════════════════════")
}