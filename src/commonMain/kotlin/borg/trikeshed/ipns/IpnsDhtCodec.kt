package borg.trikeshed.ipns

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view

enum class IpnsDhtType(val wire: ULong) {
    PUT_VALUE(0u), GET_VALUE(1u), ADD_PROVIDER(2u), GET_PROVIDERS(3u), FIND_NODE(4u), PING(5u)
}

data class IpnsDhtRecord(val key: ByteArray, val value: ByteArray)
data class IpnsDhtMessage(
    val type: IpnsDhtType,
    val key: ByteArray = byteArrayOf(),
    val record: IpnsDhtRecord? = null,
    val closerPeers: Series<Libp2pPeer> = 0 j { error("Empty peers") },
    /** Invalid/oversized peer advertisements are isolated from the authenticated record envelope. */
    val rejectedPeers: Int = 0
)

/** /ipfs/kad/1.0.0 protobuf schema; this is not an IPNS signature validator. */
object IpnsDhtCodec {
    const val PROTOCOL = "/ipfs/kad/1.0.0"
    const val MAX_MESSAGE = 1_048_576
    const val MAX_RECORD = 10_240
    const val MAX_PEERS = 64
    const val MAX_ADDRESSES = 64
    const val MAX_ADDRESS = 2048

    fun encode(message: IpnsDhtMessage): ByteArray {
        require(message.key.size <= 512)
        val chunks = mutableListOf<ByteArray>()
        chunks += IpnsProtobuf.uint(1, message.type.wire)
        if (message.key.isNotEmpty()) chunks += IpnsProtobuf.bytes(2, message.key)
        message.record?.let { record ->
            require(record.key.size in 1..512 && record.value.size in 1..MAX_RECORD)
            chunks += IpnsProtobuf.bytes(3, IpnsProtobuf.bytes(1, record.key) + IpnsProtobuf.bytes(2, record.value))
        }
        var count = 0
        for (peer in message.closerPeers.view) {
            require(++count <= MAX_PEERS)
            validatePeerId(peer.id)
            val fields = mutableListOf(IpnsProtobuf.bytes(1, peer.id))
            var addresses = 0
            for (address in peer.addresses.view) {
                require(++addresses <= MAX_ADDRESSES && address.size in 1..MAX_ADDRESS)
                fields += IpnsProtobuf.bytes(2, address)
            }
            chunks += IpnsProtobuf.bytes(8, concatenate(fields))
        }
        return concatenate(chunks).also { require(it.size <= MAX_MESSAGE) }
    }

    fun decode(bytes: ByteArray): IpnsDhtMessage {
        val fields = IpnsProtobuf.decode(bytes, MAX_MESSAGE, 512)
        val type = single(fields, 1, 0)?.integer ?: 0u // PUT_VALUE is omitted by proto3 encoders.
        val kind = IpnsDhtType.entries.singleOrNull { it.wire == type }
            ?: throw IllegalArgumentException("Unknown Kademlia message type $type")
        val key = single(fields, 2, 2)?.bytes ?: byteArrayOf()
        require(key.size <= 512)
        val record = single(fields, 3, 2)?.bytes?.let(::decodeRecord)
        val peers = mutableListOf<Libp2pPeer>()
        var rejected = 0
        for (field in fields) if (field.number == 8) {
            if (field.wireType != 2 || peers.size >= MAX_PEERS) {
                rejected++
                continue
            }
            try {
                peers += decodePeer(field.bytes)
            } catch (_: IllegalArgumentException) {
                // A bad address list must not erase an otherwise valid record or other closer peers.
                rejected++
            }
        }
        return IpnsDhtMessage(kind, key, record, peers.toSeries(), rejected)
    }

    fun decodeRecord(bytes: ByteArray): IpnsDhtRecord {
        val fields = IpnsProtobuf.decode(bytes, MAX_RECORD + 1024, 16)
        val key = single(fields, 1, 2)?.bytes ?: throw IllegalArgumentException("Missing DHT record key")
        val value = single(fields, 2, 2)?.bytes ?: throw IllegalArgumentException("Missing DHT record value")
        require(key.size in 1..512 && value.size in 1..MAX_RECORD)
        return IpnsDhtRecord(key, value)
    }

    fun decodePeer(bytes: ByteArray): Libp2pPeer {
        val fields = IpnsProtobuf.decode(bytes, MAX_ADDRESS * MAX_ADDRESSES + 512, 512)
        val id = single(fields, 1, 2)?.bytes ?: throw IllegalArgumentException("Missing peer ID")
        validatePeerId(id)
        single(fields, 3, 0)?.let { require(it.integer <= 3u) }
        val addresses = mutableListOf<ByteArray>()
        for (field in fields) if (field.number == 2) {
            require(field.wireType == 2 && addresses.size < MAX_ADDRESSES && field.bytes.size in 1..MAX_ADDRESS)
            addresses += field.bytes
        }
        return Libp2pPeer(id, addresses.toSeries())
    }

    fun frame(message: IpnsDhtMessage): ByteArray = encode(message).let { IpnsEncoding.varint(it.size.toULong()) + it }

    suspend fun receive(stream: Libp2pStream): IpnsDhtMessage {
        var length = 0
        for (index in 0..3) {
            val byte = stream.exact(1)[0].toInt() and 255
            length = length or ((byte and 127) shl (index * 7))
            if (byte and 128 == 0) {
                require(index == 0 || byte != 0) { "Non-minimal DHT frame length" }
                require(length in 1..MAX_MESSAGE) { "DHT frame size limit" }
                return decode(stream.exact(length))
            }
        }
        throw IllegalArgumentException("DHT frame length overflow")
    }

    fun validatePeerId(id: ByteArray) {
        require(id.size in 2..128) { "Peer ID size limit" }
        val (code, digest) = IpnsEncoding.multihashParts(id)
        require(code == 0uL && digest.size in 1..42 || code == 0x12uL && digest.size == 32) { "Unsupported peer ID multihash" }
    }

    internal fun single(fields: List<IpnsProtobuf.Field>, number: Int, wire: Int): IpnsProtobuf.Field? {
        val values = fields.filter { it.number == number }
        require(values.size <= 1 && values.all { it.wireType == wire }) { "Invalid/duplicate protobuf field $number" }
        return values.singleOrNull()
    }

    internal fun concatenate(chunks: List<ByteArray>): ByteArray {
        val size = chunks.sumOf { it.size }
        require(size <= MAX_MESSAGE)
        val result = ByteArray(size)
        var offset = 0
        for (chunk in chunks) { chunk.copyInto(result, offset); offset += chunk.size }
        return result
    }
}
