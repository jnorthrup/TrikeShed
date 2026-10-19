package borg.trikeshed.isam

import borg.trikeshed.job.ContentId
import borg.trikeshed.tilting.zran.*

/** Portable RFC 8878 dictionary codec. CAS supplies the bytes; this codec performs no I/O. */
class DictionaryLeafCodec(bytes: ByteArray) : IsamLeafCodec {
    private val state = ZstdDictionary(bytes)
    override val dictionary = ContentId.of(state.bytes)

    override fun encoder(): LeafEncoder {
        val encoder = ZstdEncoder(dictionary = state)
        return { source, length -> encoder.compress(source, 0, length) }
    }

    override fun decoder(): LeafDecoder {
        val decoder = ZstdDecoder(state)
        return { source, destination ->
            require(decoder.decodeInto(source, 0, source.size, destination, 0, destination.size) == source.size) {
                "ISAM leaf has trailing bytes"
            }
        }
    }
}
