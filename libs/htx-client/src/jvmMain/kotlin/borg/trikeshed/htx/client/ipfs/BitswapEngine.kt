package borg.trikeshed.htx.client.ipfs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.ConcurrentHashMap
import java.io.ByteArrayOutputStream

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
            is WantBlock -> encodeWantBlock(cids)
            is WantHave -> encodeWantHave(cids)
            is Block -> encodeBlock(cid, data)
            is Have -> encodeHave(cid)
            is DontHave -> encodeDontHave(cid)
            is Cancel -> encodeCancel(cid)
        }

        private fun encodeWantBlock(cids: List<CID>): ByteArray {
            val output = ByteArrayOutputStream()
            output.write(0x00)
            output.write(cids.size)
            cids.forEach { cid ->
                output.write(cid.bytes.size)
                cid.bytes.forEach { output.write(it.toInt()) }
            }
            return output.toByteArray()
        }
        private fun encodeWantHave(cids: List<CID>): ByteArray {
            val output = ByteArrayOutputStream()
            output.write(0x01)
            output.write(cids.size)
            cids.forEach { cid ->
                output.write(cid.bytes.size)
                cid.bytes.forEach { output.write(it.toInt()) }
            }
            return output.toByteArray()
        }
        private fun encodeBlock(cid: CID, data: ByteArray): ByteArray {
            val output = ByteArrayOutputStream()
            output.write(0x02)
            output.write(cid.bytes.size)
            cid.bytes.forEach { output.write(it.toInt()) }
            output.write(data.size shr 24)
            output.write(data.size shr 16 and 0xFF)
            output.write(data.size shr 8 and 0xFF)
            output.write(data.size and 0xFF)
            data.forEach { output.write(it.toInt()) }
            return output.toByteArray()
        }
        private fun encodeHave(cid: CID): ByteArray {
            val output = ByteArrayOutputStream()
            output.write(0x03)
            output.write(cid.bytes.size)
            cid.bytes.forEach { output.write(it.toInt()) }
            return output.toByteArray()
        }
        private fun encodeDontHave(cid: CID): ByteArray {
            val output = ByteArrayOutputStream()
            output.write(0x04)
            output.write(cid.bytes.size)
            cid.bytes.forEach { output.write(it.toInt()) }
            return output.toByteArray()
        }
        private fun encodeCancel(cid: CID): ByteArray {
            val output = ByteArrayOutputStream()
            output.write(0x05)
            output.write(cid.bytes.size)
            cid.bytes.forEach { output.write(it.toInt()) }
            return output.toByteArray()
        }

        companion object {
            fun decode(data: ByteArray): BitswapMessage {
                var pos = 0
                val type = data[pos++].toInt() and 0xFF
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
                val count = data[pos++].toInt() and 0xFF
                val cids = mutableListOf<CID>()
                repeat(count) {
                    val cidLen = data[pos++].toInt() and 0xFF
                    cids.add(CID(data.copyOfRange(pos, pos + cidLen)))
                    pos += cidLen
                }
                return WantBlock(cids)
            }
            private fun decodeWantHave(data: ByteArray): WantHave {
                var pos = 1
                val count = data[pos++].toInt() and 0xFF
                val cids = mutableListOf<CID>()
                repeat(count) {
                    val cidLen = data[pos++].toInt() and 0xFF
                    cids.add(CID(data.copyOfRange(pos, pos + cidLen)))
                    pos += cidLen
                }
                return WantHave(cids)
            }
            private fun decodeBlock(data: ByteArray): Block {
                var pos = 1
                val cidLen = data[pos++].toInt() and 0xFF
                val cidBytes = data.copyOfRange(pos, pos + cidLen)
                pos += cidLen
                val dataLen = ((data[pos].toInt() and 0xFF) shl 24) or ((data[pos+1].toInt() and 0xFF) shl 16) or ((data[pos+2].toInt() and 0xFF) shl 8) or (data[pos+3].toInt() and 0xFF)
                pos += 4
                return Block(CID(cidBytes), data.copyOfRange(pos, pos + dataLen))
            }
            private fun decodeHave(data: ByteArray): Have {
                var pos = 1
                val cidLen = data[pos++].toInt() and 0xFF
                return Have(CID(data.copyOfRange(pos, pos + cidLen)))
            }
            private fun decodeDontHave(data: ByteArray): DontHave {
                var pos = 1
                val cidLen = data[pos++].toInt() and 0xFF
                return DontHave(CID(data.copyOfRange(pos, pos + cidLen)))
            }
            private fun decodeCancel(data: ByteArray): Cancel {
                var pos = 1
                val cidLen = data[pos++].toInt() and 0xFF
                return Cancel(CID(data.copyOfRange(pos, pos + cidLen)))
            }
        }
    }
}