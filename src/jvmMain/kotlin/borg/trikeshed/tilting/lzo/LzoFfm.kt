package borg.trikeshed.tilting.lzo

import borg.trikeshed.tilting.lz4.loadLibrary
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * liblzo2's lzo1x_1_compress and lzo1x_decompress_safe through FFM critical downcalls; arrays are passed in place.
 * Library: -Dtrikeshed.liblzo2, else liblzo2.so.2, else Homebrew's. [initResult] is lzo_init()'s return (0 = LZO_E_OK).
 * The function addresses are looked up at run time and passed to the unbound handles in [LzoDowncall].
 */
class LzoFfm private constructor(
    private val compress1: MemorySegment,
    private val decompressSafe: MemorySegment,
    val initResult: Int,
) : LzoBlockOps {
    override val name: String get() = "liblzo2"
    private val work: MemorySegment = Arena.ofAuto().allocate(WORK_BYTES)
    private val length = LongArray(1)
    private val lengthAt = MemorySegment.ofArray(length)

    override fun compress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        length[0] = (dst.size - dstOff).toLong()
        val rc = LzoDowncall.BLOCK.invokeExact(compress1, MemorySegment.ofArray(src).asSlice(off.toLong(), len.toLong()),
            len.toLong(), MemorySegment.ofArray(dst).asSlice(dstOff.toLong()), lengthAt, work) as Int
        if (rc != 0) throw LzoException("lzo1x_1_compress returned $rc")
        return length[0].toInt()
    }

    override fun decompress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int, dstCap: Int): Int {
        length[0] = dstCap.toLong()
        val rc = LzoDowncall.BLOCK.invokeExact(decompressSafe, MemorySegment.ofArray(src).asSlice(off.toLong(), len.toLong()),
            len.toLong(), MemorySegment.ofArray(dst).asSlice(dstOff.toLong(), dstCap.toLong()), lengthAt,
            MemorySegment.NULL) as Int
        if (rc != 0) throw LzoException("lzo1x_decompress_safe returned $rc")
        return length[0].toInt()
    }

    companion object {
        /** LZO1X_1_MEM_COMPRESS on LP64: 16384 dictionary entries of sizeof(lzo_bytep). */
        const val WORK_BYTES: Long = 16384L * 8

        /** Null when no liblzo2 loads. */
        fun loadOrNull(): LzoFfm? {
            val lookup = loadLibrary("trikeshed.liblzo2", "liblzo2.so.2", "/opt/homebrew/opt/lzo/lib/liblzo2.2.dylib",
                "/usr/local/opt/lzo/lib/liblzo2.2.dylib") ?: return null
            fun find(name: String) = lookup.find(name).orElseThrow { UnsatisfiedLinkError(name) }
            // lzo_init(): LZO_VERSION 2.10, then sizeof short, int, long, lzo_uint32_t, lzo_uint, dict_t, char*,
            // lzo_voidp, lzo_callback_t on LP64. It runs once, so its handle is made here at run time: native-image
            // for linux-aarch64 (GraalVM 25.0.4) fails on a build-time handle with ten int arguments
            // ("unexpected input could not be handled: linkToNative").
            val init = Linker.nativeLinker().downcallHandle(find("__lzo_init_v2"), FunctionDescriptor.of(JAVA_INT,
                JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT))
            val rc = init.invokeExact(0x20a0, 2, 4, 8, 4, 8, 8, 8, 8, 48) as Int
            return LzoFfm(find("lzo1x_1_compress"), find("lzo1x_decompress_safe"), rc)
        }
    }
}

/**
 * liblzo2's block downcall shape, unbound: the function address is the first argument. No library is touched here, so
 * native-image initializes this at build time (META-INF/native-image).
 */
object LzoDowncall {
    /** int f(const lzo_bytep src, lzo_uint srcLen, lzo_bytep dst, lzo_uintp dstLen, lzo_voidp wrkmem), critical. */
    @JvmField
    val BLOCK: MethodHandle = Linker.nativeLinker().downcallHandle(
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS), Linker.Option.critical(true))
}
