package borg.trikeshed.tilting.leaf

import borg.trikeshed.tilting.lz4.Lz4BlockOps
import borg.trikeshed.tilting.lz4.Lz4Exception
import borg.trikeshed.tilting.lz4.Lz4Frame
import borg.trikeshed.tilting.lz4.Lz4Kotlin
import borg.trikeshed.tilting.lzo.BtrfsLzo
import borg.trikeshed.tilting.lzo.LzoBlockOps
import borg.trikeshed.tilting.lzo.LzoException
import borg.trikeshed.tilting.lzo.LzoKotlin

/**
 * A leaf codec in the shape of TrikeShed's IsamLeafCodec: [encoder] takes leaf bytes and a length, [decoder] fills a
 * leaf-sized destination. Instances own scratch, so each thread takes its own.
 */
interface LeafCodec {
    val name: String
    fun encode(src: ByteArray, off: Int, len: Int): ByteArray

    /** Decode all of [src] into [dst]; the decoded size must equal dst.size. */
    fun decodeInto(src: ByteArray, dst: ByteArray)

    fun encoder(): (ByteArray, Int) -> ByteArray = { source, length -> encode(source, 0, length) }
    fun decoder(): (ByteArray, ByteArray) -> Unit = { source, destination -> decodeInto(source, destination) }
}

/** Stored leaves: the bytes themselves. */
object RawLeafCodec : LeafCodec {
    override val name: String get() = "raw"
    override fun encode(src: ByteArray, off: Int, len: Int): ByteArray = src.copyOfRange(off, off + len)
    override fun decodeInto(src: ByteArray, dst: ByteArray) {
        if (src.size != dst.size) throw IllegalArgumentException("raw leaf of ${src.size} bytes into ${dst.size}")
        src.copyInto(dst)
    }
}

/** One LZ4 frame per leaf, as ZstdLeafCodec writes one zstd frame per leaf. */
class Lz4LeafCodec(val block: Lz4BlockOps = Lz4Kotlin()) : LeafCodec {
    private val frame = Lz4Frame(block)
    override val name: String get() = "lz4/${block.name}"
    override fun encode(src: ByteArray, off: Int, len: Int): ByteArray = frame.encode(src, off, len)
    override fun decodeInto(src: ByteArray, dst: ByteArray) {
        val n = frame.decodeInto(src, 0, src.size, dst)
        if (n != dst.size) throw Lz4Exception("leaf decoded to $n bytes, expected ${dst.size}")
    }
}

/** One btrfs LZO extent per leaf: LZO1X per 4 KiB sector, the way btrfs compresses file extents. */
class LzoLeafCodec(val block: LzoBlockOps = LzoKotlin(), sectorSize: Int = 4096) : LeafCodec {
    private val extent = BtrfsLzo(block, sectorSize)
    override val name: String get() = "lzo/${block.name}"
    override fun encode(src: ByteArray, off: Int, len: Int): ByteArray = extent.encode(src, off, len)
    override fun decodeInto(src: ByteArray, dst: ByteArray) {
        val n = extent.decodeInto(src, 0, src.size, dst)
        if (n != dst.size) throw LzoException("leaf decoded to $n bytes, expected ${dst.size}")
    }
}

/**
 * The platform's specialization of the leaf codecs: its own LZ4 and LZO block implementations (C libraries through
 * the platform's FFI), or null where the platform has none. Each call returns a fresh instance.
 */
expect object LeafSpecialization {
    val platform: String
    fun lz4(): Lz4BlockOps?
    fun lzo(): LzoBlockOps?
}
