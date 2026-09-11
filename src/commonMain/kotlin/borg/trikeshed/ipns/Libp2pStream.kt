package borg.trikeshed.ipns

import borg.trikeshed.lib.Series
import kotlin.coroutines.CoroutineContext

/** Peer ID multihash and binary multiaddrs, as carried by libp2p routing messages. */
data class Libp2pPeer(val id: ByteArray, val addresses: Series<ByteArray>)

interface Libp2pStream {
    /** At most maxBytes; an empty result means EOF, never temporary unavailability. */
    suspend fun read(maxBytes: Int): ByteArray
    suspend fun write(bytes: ByteArray)
    suspend fun close()
}

interface Libp2pDialer : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<Libp2pDialer>
    override val key: CoroutineContext.Key<*> get() = Key
    suspend fun open(peer: Libp2pPeer, protocol: String): Libp2pStream
}

internal suspend fun Libp2pStream.exact(length: Int): ByteArray {
    require(length in 0..1048576)
    val output = ByteArray(length)
    var at = 0
    while (at < length) {
        val bytes = read(length - at)
        check(bytes.isNotEmpty()) { "Truncated libp2p frame: $at/$length" }
        check(bytes.size <= length - at) { "Stream exceeded requested read width" }
        bytes.copyInto(output, at)
        at += bytes.size
    }
    return output
}

/** Multistream-select v1 messages include a trailing newline in their unsigned-varint length. */
object Libp2pMultistream {
    const val HEADER = "/multistream/1.0.0"
    fun frame(protocol: String): ByteArray {
        require(protocol.length in 1..1023 && '\n' !in protocol)
        val body = (protocol + "\n").encodeToByteArray()
        require(body.size <= 1024)
        val prefix = if (body.size < 128) byteArrayOf(body.size.toByte())
        else byteArrayOf(((body.size and 127) or 128).toByte(), (body.size ushr 7).toByte())
        return prefix + body
    }

    suspend fun receive(stream: Libp2pStream): String {
        var length = 0
        var shift = 0
        while (true) {
            val value = stream.exact(1)[0].toInt() and 255
            require(shift <= 7) { "Multistream length exceeds bound" }
            length = length or ((value and 127) shl shift)
            if (value and 128 == 0) break
            shift += 7
        }
        require(length in 1..1024)
        val bytes = stream.exact(length)
        require(bytes.last() == 10.toByte()) { "Multistream message lacks newline" }
        return bytes.decodeToString(0, bytes.lastIndex, throwOnInvalidSequence = true)
    }

    suspend fun select(stream: Libp2pStream, protocol: String) {
        stream.write(frame(HEADER))
        check(receive(stream) == HEADER) { "Peer rejected multistream-select v1" }
        stream.write(frame(protocol))
        check(receive(stream) == protocol) { "Peer rejected protocol $protocol" }
    }
}
