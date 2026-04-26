@file:OptIn(ExperimentalUnsignedTypes::class, ExperimentalForeignApi::class)

package borg.trikeshed.tilting.zran

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toCPointer
import platform.posix.FILE
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.stderr
import platform.posix.stdin
import platform.posix.stdout
import kotlin.math.absoluteValue

// --- Missing types referenced by GzBlockIndex.kt and kzran.kt ---

/** Point — matches the shape used by kzran.kt:
 *  3rd param = window data (UByteArray), 4th = winsize (Int), 5th = supplier. */
@ExperimentalUnsignedTypes
data class Point(
    val input: ULong,
    val output: ULong,
    val window: UByteArray,
    val winsize: Int = window.size,
    val windowSupplier: (() -> UByteArray)? = null,
) {
    /** Lazily-computed window: use stored window if non-empty, else call supplier. */
    val lazyWindow: UByteArray
        get() = if (window.isNotEmpty()) window else windowSupplier?.invoke() ?: UByteArray(0)
}

/** Stub PointRowVec for root zran — uses plain list cells, no couch dependency. */
interface PointRowVec {
    val keys: List<String>
    val cells: List<Any?>
    val size: Int
    /** Window size — derived from the underlying Point's winsize field. */
    val winsize: Int
    operator fun get(index: Int): Any?
    operator fun get(key: String): Any?
}

internal class PointRowVecImpl(point: Point) : PointRowVec {
    override val keys = listOf("compressedOffset", "decompressedOffset", "windowSize", "windowData")
    override val winsize: Int = point.winsize
    override val cells = listOf<Any?>(
        point.input.toLong(),
        point.output.toLong(),
        point.winsize.toLong(),
        point.window,
    )
    override val size: Int get() = 4
    override fun get(index: Int): Any? = cells.getOrNull(index)
    override fun get(key: String): Any? {
        val idx = keys.indexOf(key)
        return if (idx >= 0) cells.getOrNull(idx) else null
    }
}

fun pointRowVecOf(point: Point): PointRowVec = PointRowVecImpl(point)

/** Stub BlockIndex for root zran — minimal interface matching what GzBlockIndex implements. */
interface BlockIndex {
    val provider: CompressionProvider
    val pointSeries: Series<PointRowVec>
    val lineTable: Series<Long>
    fun seekLine(lineIndex: Int): PointRowVec?
    fun seekByte(decompressedOffset: ULong): PointRowVec?
}

/** Stub enum matching the one in couch. */
enum class CompressionProvider {
    GZIP,
    ZSTD,
}

// --- Missing extensions / utilities referenced by kzran.kt ---

/** Stub logDebug matching the inline-lambda style used in kzran.kt. */
inline fun <T> logDebug(block: () -> T): T = block()

/** UByteArray α UByte::toByte — missing infix overload. */
infix fun <C, B : (Byte) -> C> UByteArray.α(m: B): Series<C> =
    this.size j { i: Int -> m(this[i].toByte()) }

/** winsize is now directly on PointRowVec interface. */

/** Point extension matching what prepareIndexEntry() accesses. */
val Point.window: UByteArray
    get() = if (winsize > 0) this.window else windowSupplier?.invoke() ?: UByteArray(0)

/** Re-export stdin/stdout/stderr as CPointer<FILE> for compatibility with fread/fwrite/fprintf. */
@OptIn(ExperimentalForeignApi::class)
val stdin: CPointer<FILE>? get() = platform.posix.stdin

@OptIn(ExperimentalForeignApi::class)
val stdout: CPointer<FILE>? get() = platform.posix.stdout

@OptIn(ExperimentalForeignApi::class)
val stderr: CPointer<FILE>? get() = platform.posix.stderr

/** Extension on UByteArray to provide toSeries() for the α transform. */
fun UByteArray.toSeries(): Series<UByte> = size j { get(it) }

/** leftIdentity stub — used for stdin window reading. */
val <T> T.leftIdentity: T get() = this

/** binarySearch on List<ULong> stub. */
fun List<ULong>.binarySearch(key: ULong): Int {
    var lo = 0
    var hi = size - 1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        val v = this[mid]
        when {
            v < key -> lo = mid + 1
            v > key -> hi = mid - 1
            else -> return mid
        }
    }
    return -(lo + 1)
}
