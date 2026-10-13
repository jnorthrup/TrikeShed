package borg.trikeshed.isam

import borg.trikeshed.job.ContentId
import borg.trikeshed.tilting.zran.*

typealias LeafEncoder = (ByteArray, Int) -> ByteArray
typealias LeafDecoder = (ByteArray, ByteArray) -> Unit

/** Zstd frames only. Dictionary identity is independent of dictionary-coded column values.
 * Factories give each sink/reader its own scratch; concurrent reads get a separate decoder.
 * A dictionary must remain available under its content id for the lifetime of its files.
 */
interface IsamLeafCodec {
    val dictionary: ContentId? get() = null
    /** Resolve the identity recorded by a reader, independently of the dictionary selected for new writes. */
    fun resolve(dictionary: ContentId): IsamLeafCodec {
        require(this.dictionary == dictionary) { "ISAM compression dictionary required: ${dictionary.value}" }
        return this
    }
    /** Publish dictionary bytes before any metadata or leaf file can name them. */
    fun prepareWrite() {}
    fun encoder(): LeafEncoder
    fun decoder(): LeafDecoder
}

/** Existing portable encoder and wire format; no native dependency or compression dictionary. */
object ZstdLeafCodec : IsamLeafCodec {
    override fun encoder(): LeafEncoder {
        val encoder = ZstdEncoder()
        return { source, length -> encoder.compress(source, 0, length) }
    }

    override fun decoder(): LeafDecoder {
        val decoder = ZstdDecoder()
        return { source, destination ->
            require(decoder.decodeInto(source, 0, source.size, destination, 0, destination.size) == source.size) {
                "ISAM leaf has trailing bytes"
            }
        }
    }
}

/** Optional skippable frame immediately before the existing fence: version + dictionary CID. */
const val LEAF_DICTIONARY_MAGIC: Int = 0x184D2A5C
const val LEAF_DICTIONARY_BYTES: Int = 80

fun IsamLeafCodec.dictionaryFrame(): ByteArray = dictionary?.let { cid ->
    ByteArray(LEAF_DICTIONARY_BYTES).also {
        it.put32(0, LEAF_DICTIONARY_MAGIC)
        it.put32(4, LEAF_DICTIONARY_BYTES - 8)
        it[8] = 1
        cid.value.encodeToByteArray().copyInto(it, 9)
    }
} ?: ByteArray(0)
