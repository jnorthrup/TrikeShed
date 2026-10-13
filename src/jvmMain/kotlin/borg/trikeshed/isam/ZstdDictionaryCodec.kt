package borg.trikeshed.isam

import borg.trikeshed.job.ContentId
import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdDecompressCtx

/** Opt-in RFC 8878 trained or raw-content dictionary. Does not replace the portable default.
 * Native contexts are scoped to a single frame and closed on success and failure; this codec
 * owns no file, executor, thread-local or daemon lifecycle. Keep the dictionary in the owner's CAS.
 */
class ZstdDictionaryCodec(bytes: ByteArray, val level: Int = 3) : IsamLeafCodec {
    private val bytes = bytes.copyOf()
    override val dictionary = ContentId.of(this.bytes)

    init {
        require(bytes.isNotEmpty()) { "Zstd compression dictionary must not be empty" }
        require(level in Zstd.minCompressionLevel()..Zstd.maxCompressionLevel()) { "Zstd compression level" }
    }

    override fun encoder(): LeafEncoder = { source, length ->
        require(length in 0..source.size)
        val bound = Zstd.compressBound(length.toLong())
        require(bound in 0..Int.MAX_VALUE.toLong()) { "Zstd frame exceeds array capacity" }
        val destination = ByteArray(bound.toInt())
        ZstdCompressCtx().use { context ->
            context.setLevel(level).setChecksum(true).loadDict(bytes)
            val size = context.compressByteArray(destination, 0, destination.size, source, 0, length)
            destination.copyOf(size)
        }
    }

    override fun decoder(): LeafDecoder = { source, destination ->
        ZstdDecompressCtx().use { context ->
            context.loadDict(bytes)
            require(context.decompressByteArray(destination, 0, destination.size, source, 0, source.size) == destination.size) {
                "ISAM dictionary leaf content differs from its seek table"
            }
        }
    }
}
