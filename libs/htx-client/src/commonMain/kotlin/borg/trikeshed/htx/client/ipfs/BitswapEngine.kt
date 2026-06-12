package borg.trikeshed.htx.client.ipfs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Bitswap Engine — IPFS Block Exchange Protocol implementation.
 */
class BitswapEngine(
    private val blockStore: BlockStore,
    private val sendMessage: (ByteArray) -> Unit,
) {

    private val wantlist = ConcurrentHashMap<String, WantEntry>()
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()

    suspend fun wantBlock(cid: CID): ByteArray {
        val key = cid.hex()
        val local = blockStore.get(cid)
        if (local != null) return local

        val entry = wantlist.computeIfAbsent(key) { WantEntry(cid) }
        entry.incrementPriority()
        broadcastWantBlock(cid)

        val deferred = CompletableDeferred<ByteArray>()
        pendingResponses[key] = deferred

        return try {
            deferred.await()
        } finally {
            pendingResponses.remove(key)
            val e = wantlist[key]
            if (e != null) {
                e.decrementPriority()
                if (e.priority <= 0) wantlist.remove(key)
            }
        }
    }

    fun cancelWant(cid: CID) {
        val key = cid.hex()
        wantlist.remove(key)
        pendingResponses.remove(key)?.completeExceptionally(Exception("Want cancelled for $key"))
        broadcastCancel(cid)
    }

    fun handleMessage(message: BitswapMessage) {
        when (message) {
            is BitswapMessage.WantBlock -> message.cids.forEach { cid ->
                val data = blockStore.get(cid)
                if (data != null) sendBlock(cid, data) else sendDontHave(cid)
            }
            is BitswapMessage.WantHave -> message.cids.forEach { cid ->
                if (blockStore.get(cid) != null) sendHave(cid) else sendDontHave(cid)
            }
            is BitswapMessage.Block -> {
                blockStore.put(message.cid, message.data)
                pendingResponses.remove(message.cid.hex())?.complete(message.data)
            }
            is BitswapMessage.Cancel -> {
                wantlist.remove(message.cid.hex())
                pendingResponses.remove(message.cid.hex())
            }
            else -> {}
        }
    }

    private fun broadcastWantBlock(cid: CID) = sendMessage(BitswapMessage.WantBlock(listOf(cid)).encode())
    private fun broadcastCancel(cid: CID) = sendMessage(BitswapMessage.Cancel(cid).encode())
    private fun sendBlock(cid: CID, data: ByteArray) = sendMessage(BitswapMessage.Block(cid, data).encode())
    private fun sendHave(cid: CID) = sendMessage(BitswapMessage.Have(cid).encode())
    private fun sendDontHave(cid: CID) = sendMessage(BitswapMessage.DontHave(cid).encode())

    class WantEntry(val cid: CID) {
        var priority = 1
        fun incrementPriority() { priority++ }
        fun decrementPriority() { priority-- }
    }

    sealed class BitswapMessage {
        data class WantBlock(val cids: List<CID>) : BitswapMessage()
        data class WantHave(val cids: List<CID>) : BitswapMessage()
        data class Block(val cid: CID, val data: ByteArray) : BitswapMessage()
        data class Have(val cid: CID) : BitswapMessage()
        data class DontHave(val cid: CID) : BitswapMessage()
        data class Cancel(val cid: CID) : BitswapMessage()

        fun encode(): ByteArray = when (this) {
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

        companion object {
            fun decode(data: ByteArray): BitswapMessage {
                var pos = 0
                val type = data[pos++] and 0xFF
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
                    cids.add(CID(data.copyOfRange(pos, pos + cidLen)))
                    pos += cidLen
                }
                return WantBlock(cids)
            }
            private fun decodeWantHave(data: ByteArray): WantHave {
                var pos = 1
                val count = data[pos++] and 0xFF
                val cids = mutableListOf<CID>()
                repeat(count) {
                    val cidLen = data[pos++] and 0xFF
                    cids.add(CID(data.copyOfRange(pos, pos + cidLen)))
                    pos += cidLen
                }
                return WantHave(cids)
            }
            private fun decodeBlock(data: ByteArray): Block {
                var pos = 1
                val cidLen = data[pos++] and 0xFF
                val cidBytes = data.copyOfRange(pos, pos + cidLen)
                pos += cidLen
                val dataLen = (data[pos] shl 24) | ((data[pos+1] and 0xFF) shl 16) | ((data[pos+2] and 0xFF) shl 8) | (data[pos+3] and 0xFF)
                pos += 4
                return Block(CID(cidBytes), data.copyOfRange(pos, pos + dataLen))
            }
            private fun decodeHave(data: ByteArray): Have {
                var pos = 1
                val cidLen = data[pos++] and 0xFF
                return Have(CID(data.copyOfRange(pos, pos + cidLen)))
            }
            private fun decodeDontHave(data: ByteArray): DontHave {
                var pos = 1
                val cidLen = data[pos++] and 0xFF
                return DontHave(CID(data.copyOfRange(pos, pos + cidLen)))
            }
            private fun decodeCancel(data: ByteArray): Cancel {
                var pos = 1
                val cidLen = data[pos++] and 0xFF
                return Cancel(CID(data.copyOfRange(pos, pos + cidLen)))
            }
        }
    }
}