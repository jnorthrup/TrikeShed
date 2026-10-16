package borg.trikeshed.tilting.leaf

import borg.trikeshed.tilting.leaf.c.LZ4_compress_default
import borg.trikeshed.tilting.leaf.c.LZ4_decompress_safe
import borg.trikeshed.tilting.leaf.c.__lzo_init_v2
import borg.trikeshed.tilting.leaf.c.lzo1x_1_compress
import borg.trikeshed.tilting.leaf.c.lzo1x_decompress_safe
import borg.trikeshed.tilting.lz4.Lz4BlockOps
import borg.trikeshed.tilting.lz4.Lz4Exception
import borg.trikeshed.tilting.lzo.LzoBlockOps
import borg.trikeshed.tilting.lzo.LzoException
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.value
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * Kotlin/Native specialization (linuxX64, linuxArm64, macosArm64): the platform's liblz4 and liblzo2 through cinterop
 * (src/nativeInterop/cinterop/lz.def). [platform] is the target's OS and CPU, e.g. linux-x64, macosx-arm64.
 */
actual object LeafSpecialization {
    @OptIn(ExperimentalNativeApi::class)
    actual val platform: String =
        "${Platform.osFamily.name.lowercase()}-${Platform.cpuArchitecture.name.lowercase()}"
    actual fun lz4(): Lz4BlockOps? = Lz4Native()
    actual fun lzo(): LzoBlockOps? = LzoNative()
}

/** A zero-length range gets a pointer into this, never dereferenced (its length or capacity is 0). */
private val spare = ByteArray(1)

private fun ByteArray.at(index: Int): CValuesRef<ByteVar> = if (index < size) refTo(index) else spare.refTo(0)
private fun ByteArray.uat(index: Int): CValuesRef<UByteVar> =
    if (index < size) asUByteArray().refTo(index) else spare.asUByteArray().refTo(0)

class Lz4Native : Lz4BlockOps {
    override val name: String get() = "liblz4"

    override fun compress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        val n = LZ4_compress_default(src.at(off), dst.at(dstOff), len, dst.size - dstOff)
        if (n <= 0) throw Lz4Exception("LZ4_compress_default returned $n")
        return n
    }

    override fun decompress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int, dstCap: Int): Int {
        val n = LZ4_decompress_safe(src.at(off), dst.at(dstOff), len, dstCap)
        if (n < 0) throw Lz4Exception("LZ4_decompress_safe returned $n")
        return n
    }
}

class LzoNative : LzoBlockOps {
    override val name: String get() = "liblzo2"

    /** lzo_init(): LZO_VERSION 2.10 and the LP64 type sizes. */
    val initResult: Int = __lzo_init_v2(0x20a0u, 2, 4, 8, 4, 8, 8, 8, 8, 48)
    private val work = nativeHeap.allocArray<ByteVar>(16384 * 8) // LZO1X_1_MEM_COMPRESS
    private val length = nativeHeap.alloc<ULongVar>()

    override fun compress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        length.value = (dst.size - dstOff).toULong()
        val rc = lzo1x_1_compress(src.uat(off), len.toULong(), dst.uat(dstOff), length.ptr, work)
        if (rc != 0) throw LzoException("lzo1x_1_compress returned $rc")
        return length.value.toInt()
    }

    override fun decompress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int, dstCap: Int): Int {
        length.value = dstCap.toULong()
        val rc = lzo1x_decompress_safe(src.uat(off), len.toULong(), dst.uat(dstOff), length.ptr, null)
        if (rc != 0) throw LzoException("lzo1x_decompress_safe returned $rc")
        return length.value.toInt()
    }
}
