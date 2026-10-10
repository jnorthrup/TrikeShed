package borg.trikeshed.loom

import borg.trikeshed.collections.associative.*
import borg.trikeshed.ipns.IpnsCrypto
import borg.trikeshed.job.sha256
import borg.trikeshed.lib.*

/** cocaine-rats crates/loom-mesh/src/segment.rs: a SHA-256 content id, 32 bytes. */
typealias Id = ByteArray

const val MAX_CIPHERTEXT: Int = 1_048_576

/** Producer-signed ciphertext of one (producer, epoch, sequence) slot. */
class Segment(
    val producer: String,
    val epoch: ULong,
    val sequence: ULong,
    val ciphertext: ByteArray,
    var signature: ByteArray = ByteArray(64),
) {
    fun validate() {
        label(producer)
        if (epoch > Long.MAX_VALUE.toULong() || sequence > Long.MAX_VALUE.toULong() || ciphertext.size !in 16..MAX_CIPHERTEXT)
            error("segment bounds")
    }

    fun unsigned(): Item = itemArrayOf(
        Item.Str("loom-segment/v1"),
        Item.Str(producer),
        Item.Num(epoch.toLong()),
        Item.Num(sequence.toLong()),
        Item.Bin(ciphertext),
    )

    fun to_bytes(): ByteArray = Cbor.encode(itemArrayOf(unsigned(), Item.Bin(signature)))

    /** [producers] maps producer ids to their verified 32-byte keys. */
    fun verify(producers: Map<String, ByteArray>, crypto: IpnsCrypto) {
        validate()
        val key = producers[producer] ?: error("unknown producer")
        if (!crypto.verify(key, Cbor.encode(unsigned()), signature)) error("segment signature")
    }

    fun id(): Id = sha256(to_bytes())

    fun slot(): Join<String, Twin<ULong>> = producer j (epoch j sequence)

    companion object {
        fun sign(producer: String, epoch: ULong, sequence: ULong, ciphertext: ByteArray, key: ByteArray, crypto: IpnsCrypto): Segment =
            Segment(producer, epoch, sequence, ciphertext).also {
                it.validate()
                it.signature = crypto.sign(key, Cbor.encode(it.unsigned()))
            }

        fun from_bytes(data: ByteArray): Segment {
            val outer = array(decode(data), 2)
            val a = array(outer[0], 5)
            tag(a[0], "loom-segment/v1")
            return Segment(text(a[1]), number(a[2]), number(a[3]), bytes(a[4]), fixed(outer[1], 64)).also { it.validate() }
        }
    }
}
