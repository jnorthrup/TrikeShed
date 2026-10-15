package borg.trikeshed.tilting.lz4

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle

/**
 * liblz4's block functions through FFM critical downcalls; the arrays are passed in place. Library:
 * -Dtrikeshed.liblz4, else liblz4.so.1, else Homebrew's. The function addresses are looked up at run time and passed
 * to the unbound handle in [Lz4Downcall].
 */
class Lz4Ffm private constructor(private val compressDefault: MemorySegment, private val decompressSafe: MemorySegment) :
    Lz4BlockOps {
    override val name: String get() = "liblz4"

    override fun compress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        val n = Lz4Downcall.BLOCK.invokeExact(compressDefault, MemorySegment.ofArray(src).asSlice(off.toLong(), len.toLong()),
            MemorySegment.ofArray(dst).asSlice(dstOff.toLong()), len, dst.size - dstOff) as Int
        if (n <= 0) throw Lz4Exception("LZ4_compress_default returned $n")
        return n
    }

    override fun decompress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int, dstCap: Int): Int {
        val n = Lz4Downcall.BLOCK.invokeExact(decompressSafe, MemorySegment.ofArray(src).asSlice(off.toLong(), len.toLong()),
            MemorySegment.ofArray(dst).asSlice(dstOff.toLong(), dstCap.toLong()), len, dstCap) as Int
        if (n < 0) throw Lz4Exception("LZ4_decompress_safe returned $n")
        return n
    }

    companion object {
        /** Null when no liblz4 loads. */
        fun loadOrNull(): Lz4Ffm? {
            val lookup = loadLibrary("trikeshed.liblz4", "liblz4.so.1", "/opt/homebrew/opt/lz4/lib/liblz4.1.dylib",
                "/usr/local/opt/lz4/lib/liblz4.1.dylib") ?: return null
            fun find(name: String) = lookup.find(name).orElseThrow { UnsatisfiedLinkError(name) }
            return Lz4Ffm(find("LZ4_compress_default"), find("LZ4_decompress_safe"))
        }
    }
}

/**
 * The downcall shape both liblz4 block functions share, unbound: the function address is the first argument. No
 * library is touched here, so native-image initializes this at build time (META-INF/native-image) and the call
 * compiles to a direct one; a handle created at image run time goes through a slow path.
 */
object Lz4Downcall {
    /** int f(const char* src, char* dst, int srcSize, int dstCapacity), critical with heap access. */
    @JvmField
    val BLOCK: MethodHandle = Linker.nativeLinker().downcallHandle(
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT), Linker.Option.critical(true))
}

/** The first library that loads: the path in system property [property], then each candidate. */
internal fun loadLibrary(property: String, vararg candidates: String): SymbolLookup? =
    (listOfNotNull(System.getProperty(property)) + candidates).firstNotNullOfOrNull {
        runCatching { SymbolLookup.libraryLookup(it, Arena.global()) }.getOrNull()
    }
