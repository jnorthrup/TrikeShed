package borg.trikeshed.htx.client.ipfs

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Random

data class CID(val bytes: ByteArray) {
    override fun toString(): String = "CID(${bytes.joinToString("") { "%02x".format(it) }})"
    fun hex(): String = bytes.joinToString("") { "%02x".format(it) }
    companion object {
        fun sha256(data: ByteArray): CID = CID(MessageDigest.getInstance("SHA-256").digest(data))
    }
}

interface BlockStore {
    suspend fun put(cid: CID, data: ByteArray)
    suspend fun get(cid: CID): ByteArray?
    suspend fun has(cid: CID): Boolean = get(cid) != null
}

class MemoryBlockStore : BlockStore {
    private val store = mutableMapOf<String, ByteArray>()
    private val mutex = Mutex()
    override suspend fun put(cid: CID, data: ByteArray) = mutex.withLock { store[cid.hex()] = data }
    override suspend fun get(cid: CID): ByteArray? = mutex.withLock { store[cid.hex()] }
}

data class NodeId(val bytes: ByteArray) {
    init { require(bytes.size == 20) { "NodeId must be exactly 20 bytes" } }
    companion object {
        fun random(): NodeId = NodeId(ByteArray(20).also { Random().nextBytes(it) })
        fun fromCID(cid: CID): NodeId {
            val out = ByteArray(20)
            for (i in 0 until 20) out[i] = (cid.bytes[i % cid.bytes.size].toInt() xor (i * 31 + 1)).toByte()
            return NodeId(out)
        }
    }
    fun xorDistance(other: NodeId): BigInteger = (BigInteger(1, bytes) xor BigInteger(1, other.bytes)).abs()
    fun bucketIndex(other: NodeId): Int = xorDistance(other).bitLength() - 1
    override fun toString(): String = bytes.joinToString("") { "%02x".format(it) }
}

data class NodeInfo(
    val id: NodeId,
    val address: InetSocketAddress,
    var lastSeen: Long = System.currentTimeMillis(),
)

interface DhtTransport {
    suspend fun announceProviderRemote(cid: CID, address: String)
    suspend fun findProvidersRemote(cid: CID): List<String>
    suspend fun findNodeRemote(target: NodeId): List<NodeInfo>
}
