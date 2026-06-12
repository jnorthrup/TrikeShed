package borg.trikeshed.ipfs.bitswap

import borg.trikeshed.ipfs.CID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Bitswap — IPFS Block Exchange Protocol
 *
 * Bitswap is a message-based protocol for requesting and sending blocks.
 * It operates over libp2p streams and uses a wantlist to track desired blocks.
 *
 * Message types:
 * - WANT_BLOCK (0x00): Request a block by CID
 * - WANT_HAVE (0x01): Request confirmation if peer has block
 * - BLOCK (0x01): Send a block (CID + data)
 * - HAVE (0x02): Notify peer we have a block
 * - DONT_HAVE (0x03): Notify peer we don't have a block
 * - CANCEL (0x04): Cancel a previous want
 */
class BitswapEngine(
    private val blockStore: BlockStore,
    private val sendMessage: (ByteArray) -> Unit, // Callback to send raw message to peer
) {

    // Wantlist: CID hex -> WantEntry
    private val wantlist = ConcurrentHashMap<String, WantEntry>()
    private val wantlistMutex = Mutex()

    // Pending responses: CID hex -> CompletableDeferred<ByteArray>
    private val pendingResponses = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<ByteArray>>()

    // Connected peers
    private val peers = ConcurrentHashMap<String, PeerState>()

    /**
     * Request a block from the network.
     * Returns the block data when received.
     */
    suspend fun wantBlock(cid: CID): ByteArray {
        val key = cidHex(cid)

        // Check local store first
        val local = blockStore.get(cid)
        if (local != null) return local

        // Add to wantlist
        wantlistMutex.withLock {
            val entry = wantlist.getOrPut(key) { WantEntry(cid) }
            entry.incrementPriority()
        }

        // Send WANT_BLOCK to connected peers
        broadcastWantBlock(cid)

        // Wait for response
        val deferred = kotlinx.coroutines.CompletableDeferred<ByteArray>()
        pendingResponses[key] = deferred

        return try {
            deferred.await()
        } finally {
            pendingResponses.remove(key)
            wantlistMutex.withLock {
                val entry = wantlist[key]
                if (entry != null) {
                    entry.decrementPriority()
                    if (entry.priority <= 0) wantlist.remove(key)
                }
            }
        }
    }

    /**
     * Cancel a block request.
     */
    fun cancelWant(cid: CID) {
        val key = cidHex(cid)
        wantlistMutex.withLock {
            wantlist.remove(key)
        }
        pendingResponses.remove(key)?.completeExceptionally(
            CancellationException("Want cancelled for $key")
        )
        broadcastCancel(cid)
    }

    /**
     * Handle incoming Bitswap message.
     */
    fun handleMessage(message: BitswapMessage) {
        when (message) {
            is BitswapMessage.WantBlock -> {
                handleWantBlock(message.cids)
            }
            is BitswapMessage.WantHave -> {
                handleWantHave(message.cids)
            }
            is BitswapMessage.Block -> {
                handleBlock(message.cid, message.data)
            }
            is BitswapMessage.Have -> {
                handleHave(message.cid)
            }
            is BitswapMessage.DontHave -> {
                handleDontHave(message.cid)
            }
            is BitswapMessage.Cancel -> {
                handleCancel(message.cid)
            }
        }
    }

    private fun handleWantBlock(cids: List<CID>) {
        cids.forEach { cid ->
            val data = blockStore.get(cid)
            if (data != null) {
                // We have it - send BLOCK
                sendBlock(cid, data)
            } else {
                // We don't have it - send DONT_HAVE
                sendDontHave(cid)
            }
        }
    }

    private fun handleWantHave(cids: List<CID>) {
        cids.forEach { cid ->
            val has = blockStore.get(cid) != null
            if (has) sendHave(cid) else sendDontHave(cid)
        }
    }

    private fun handleBlock(cid: CID, data: ByteArray) {
        val key = cidHex(cid)
        // Store locally
        blockStore.put(cid, data)
        // Complete any pending request
        pendingResponses.remove(key)?.complete(data)
    }

    private fun handleHave(cid: CID) {
        val key = cidHex(cid)
        // Peer has it - could request block from them
        val entry = wantlist[key]
        if (entry != null) {
            entry.markPeerHas(getCurrentPeer()!!)
        }
    }

    private fun handleDontHave(cid: CID) {
        val key = cidHex(cid)
        val entry = wantlist[key]
        if (entry != null) {
            entry.markPeerDontHave(getCurrentPeer()!!)
        }
    }

    private fun handleCancel(cid: CID) {
        val key = cidHex(cid)
        wantlist.remove(key)
        pendingResponses.remove(key)
    }

    private fun broadcastWantBlock(cid: CID) {
        val msg = BitswapMessage.WantBlock(listOf(cid))
        sendMessage(msg.encode())
    }

    private fun broadcastCancel(cid: CID) {
        val msg = BitswapMessage.Cancel(cid)
        sendMessage(msg.encode())
    }

    private fun sendBlock(cid: CID, data: ByteArray) {
        val msg = BitswapMessage.Block(cid, data)
        sendMessage(msg.encode())
    }

    private fun sendHave(cid: CID) {
        val msg = BitswapMessage.Have(cid)
        sendMessage(msg.encode())
    }

    private fun sendDontHave(cid: CID) {
        val msg = BitswapMessage.DontHave(cid)
        sendMessage(msg.encode())
    }

    private fun getCurrentPeer(): String? = null // Would be set by transport layer

    private fun cidHex(cid: CID): String = cid.bytes.joinToString("") { "%02x".format(it) }
}

// ─── Wantlist Entry ───

class WantEntry(val cid: CID) {
    var priority = 1
    private val peersHave = mutableSetOf<String>()
    private val peersDontHave = mutableSetOf<String>()

    fun incrementPriority() { priority++ }
    fun decrementPriority() { priority-- }
    fun markPeerHas(peer: String) { peersHave.add(peer) }
    fun markPeerDontHave(peer: String) { peersDontHave.add(peer) }
    fun peerHas(peer: String) = peersHave.contains(peer)
    fun peerDontHave(peer: String) = peersDontHave.contains(peer)
}

// ─── Peer State ───

class PeerState(val id: String) {
    var connected = true
    val wantlist = mutableMapOf<String, WantEntry>()
    var lastSeen = System.currentTimeMillis()
}

// ─── Bitswap Messages ───

sealed class BitswapMessage {
    data class WantBlock(val cids: List<CID>) : BitswapMessage()
    data class WantHave(val cids: List<CID>) : BitswapMessage()
    data class Block(val cid: CID, val data: ByteArray) : BitswapMessage()
    data class Have(val cid: CID) : BitswapMessage()
    data class DontHave(val cid: CID) : BitswapMessage()
    data class Cancel(val cid: CID) : BitswapMessage()

    fun encode(): ByteArray {
        // Simple binary encoding
        // In real impl: use protobuf or CBOR
        return when (this) {
            is WantBlock -> buildByteArray {
                writeByte(0x00)
                writeByte(cids.size)
                cids.forEach { cid ->
                    writeByte(cid.bytes.size)
                    write(cid.bytes)
                }
            }
            is WantHave -> buildByteArray {
                writeByte(0x01)
                writeByte(cids.size)
                cids.forEach { cid ->
                    writeByte(cid.bytes.size)
                    write(cid.bytes)
                }
            }
            is Block -> buildByteArray {
                writeByte(0x02)
                writeByte(cid.bytes.size)
                write(cid.bytes)
                writeInt(data.size)
                write(data)
            }
            is Have -> buildByteArray {
                writeByte(0x03)
                writeByte(cid.bytes.size)
                write(cid.bytes)
            }
            is DontHave -> buildByteArray {
                writeByte(0x04)
                writeByte(cid.bytes.size)
                write(cid.bytes)
            }
            is Cancel -> buildByteArray {
                writeByte(0x05)
                writeByte(cid.bytes.size)
                write(cid.bytes)
            }
        }
    }

    companion object {
        fun decode(data: ByteArray): BitswapMessage {
            val cursor = 0
            val type = data[cursor] and 0xFF
            return when (type) {
                0x00 -> decodeWantBlock(data)
                0x01 -> decodeWantHave(data)
                0x02 -> decodeBlock(data)
                0x03 -> decodeHave(data)
                0x04 -> decodeDontHave(data)
                0x05 -> decodeCancel(data)
                else -> throw IllegalArgumentException("Unknown Bitswap message type: $type")
            }
        }

        private fun decodeWantBlock(data: ByteArray): WantBlock {
            var pos = 1
            val count = data[pos++] and 0xFF
            val cids = mutableListOf<CID>()
            repeat(count) {
                val cidLen = data[pos++] and 0xFF
                val cidBytes = data.copyOfRange(pos, pos + cidLen)
                pos += cidLen
                cids.add(CID(cidBytes))
            }
            return WantBlock(cids)
        }

        private fun decodeWantHave(data: ByteArray): WantHave {
            var pos = 1
            val count = data[pos++] and 0xFF
            val cids = mutableListOf<CID>()
            repeat(count) {
                val cidLen = data[pos++] and 0xFF
                val cidBytes = data.copyOfRange(pos, pos + cidLen)
                pos += cidLen
                cids.add(CID(cidBytes))
            }
            return WantHave(cids)
        }

        private fun decodeBlock(data: ByteArray): Block {
            var pos = 1
            val cidLen = data[pos++] and 0xFF
            val cidBytes = data.copyOfRange(pos, pos + cidLen)
            pos += cidLen
            val dataLen = data[pos..pos+3].let { it[0].toInt() shl 24 | it[1].toInt() shl 16 | it[2].toInt() shl 8 | it[3].toInt() }
            pos += 4
            val blockData = data.copyOfRange(pos, pos + dataLen)
            return Block(CID(cidBytes), blockData)
        }

        private fun decodeHave(data: ByteArray): Have {
            var pos = 1
            val cidLen = data[pos++] and 0xFF
            val cidBytes = data.copyOfRange(pos, pos + cidLen)
            return Have(CID(cidBytes))
        }

        private fun decodeDontHave(data: ByteArray): DontHave {
            var pos = 1
            val cidLen = data[pos++] and 0xFF
            val cidBytes = data.copyOfRange(pos, pos + cidLen)
            return DontHave(CID(cidBytes))
        }

        private fun decodeCancel(data: ByteArray): Cancel {
            var pos = 1
            val cidLen = data[pos++] and 0xFF
            val cidBytes = data.copyOfRange(pos, pos + cidLen)
            return Cancel(CID(cidBytes))
        }
    }
}

// Helper for ByteArray building
import kotlin.io.buildByteArray
import kotlin.io.writeByte
import kotlin.io.writeInt
import kotlin.io.write
import java.util.concurrent.CancellationException