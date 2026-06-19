@file:Suppress("unused")
package borg.trikeshed.isam

import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.min
import kotlin.jvm.JvmInline

/**
 * ISAM Data File DSL — reified inline factory builders.
 *
 * Replaces class-based data file operations with composable builder functions.
 * - append/get/scan as inline functions
 * - file layout as reified spec
 * - record encoders/decoders as typeclass instances
 */

/**
 * File operations interface — platform-neutral, implemented per target.
 * Note: distinct from borg.trikeshed.userspace.nio.file.spi.FileOperations (CCEK-based).
 * This is a simpler synchronous interface for DSL use.
 */
interface IsamFileOps {
    /**
     * Append bytes to file.
     */
    fun append(filename: String, bytes: ByteArray): Long

    /**
     * Append multiple byte arrays concatenated.
     */
    fun appendAll(filename: String, bytes: ByteArray): Long

    /**
     * Read bytes from file at offset.
     */
    fun read(filename: String, offset: Long, length: Int): ByteArray

    /**
     * Get file size in bytes.
     */
    fun size(filename: String): Long
}



/**
 * ISAM Data File DSL — reified inline factory builders.
 *
 * Replaces class-based data file operations with composable builder functions.
 * - append/get/scan as inline functions
 * - file layout as reified spec
 * - record encoders/decoders as typeclass instances
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

data class DataFileConfig(
    val filename: String,
    val recordlen: Int,
    val fileOps: IsamFileOps,
)

@JvmInline
value class DataFilePosition(val offset: Long = 0)

// ---------------------------------------------------------------------------
// Reified inline append
// ---------------------------------------------------------------------------

fun appendToIsam(
    config: DataFileConfig,
    records: Series<ByteArray>,
): Long {
    val combined = ByteArray(records.size * records[0].size)
    for (i in 0 until records.size) {
        records[i].copyInto(combined, i * records[i].size)
    }
    return config.fileOps.appendAll(config.filename, combined)
}

fun appendToIsam(
    config: DataFileConfig,
    record: ByteArray,
): Long = config.fileOps.append(config.filename, record)

// ---------------------------------------------------------------------------
// Reified inline scan with projection
// ---------------------------------------------------------------------------

fun <T> scanIsam(
    config: DataFileConfig,
    decoder: (ByteArray) -> T,
    projection: (T) -> T,
): Series<T> {
    val recordlen = config.recordlen
    val fileSize = config.fileOps.size(config.filename)
    val recordCount = (fileSize / recordlen).toInt()

    return recordCount j { i ->
        val offset = i.toLong() * recordlen
        val record = config.fileOps.read(config.filename, offset, recordlen)
        projection(decoder(record))
    }
}

fun <T> scanIsamRange(
    config: DataFileConfig,
    decoder: (ByteArray) -> T,
    projection: (T) -> T,
    start: Int,
    end: Int,
): Series<T> = scanIsam(config, decoder, projection)[start until min(end, (config.fileOps.size(config.filename) / config.recordlen).toInt())]

// ---------------------------------------------------------------------------
// Reified inline get by index
// ---------------------------------------------------------------------------

fun <T> getIsam(
    config: DataFileConfig,
    decoder: (ByteArray) -> T,
    index: Int,
): T? {
    val recordlen = config.recordlen
    val fileSize = config.fileOps.size(config.filename)
    if (index < 0 || index >= fileSize / recordlen) return null
    val offset = index.toLong() * recordlen
    val record = config.fileOps.read(config.filename, offset, recordlen)
    return decoder(record)
}

// ---------------------------------------------------------------------------
// Reified inline pointcut journal (append-only)
// ---------------------------------------------------------------------------

data class PointcutJournalConfig(
    val filename: String,
    val recordlen: Int = 128,
    val fileOps: IsamFileOps,
)

fun appendPointcut(
    config: PointcutJournalConfig,
    pointcut: ByteArray,
): Long = config.fileOps.append(config.filename, pointcut)

fun scanPointcuts(
    config: PointcutJournalConfig,
): Series<ByteArray> {
    val fileSize = config.fileOps.size(config.filename)
    val recordCount = (fileSize / config.recordlen).toInt()
    return recordCount j { i ->
        val offset = i.toLong() * config.recordlen
        config.fileOps.read(config.filename, offset, config.recordlen)
    }
}

// ---------------------------------------------------------------------------
// File layout spec
// ---------------------------------------------------------------------------

data class FileLayoutSpec(
    val recordlen: Int,
    val headerSize: Int = 0,
    val indexStride: Int = 0, // 0 = no index
) {
    fun calcOffset(index: Int): Long = (index.toLong() * recordlen) + headerSize
    fun recordCount(fileSize: Long): Int = ((fileSize - headerSize) / recordlen).toInt()
}

// ---------------------------------------------------------------------------
// ISAM operations as flow
// ---------------------------------------------------------------------------

fun <T> isamFlow(
    config: DataFileConfig,
    decoder: (ByteArray) -> T,
    predicate: (T) -> Boolean = { true },
): Flow<T> = flow {
    val recordlen = config.recordlen
    val fileSize = config.fileOps.size(config.filename)
    val recordCount = (fileSize / recordlen).toInt()
    for (i in 0 until recordCount) {
        val offset = i.toLong() * recordlen
        val record = config.fileOps.read(config.filename, offset, recordlen)
        val decoded = decoder(record)
        if (predicate(decoded)) emit(decoded)
    }
}.flowOn(Dispatchers.Default)

// ---------------------------------------------------------------------------
// Batch operations
// ---------------------------------------------------------------------------

fun batchAppend(
    config: DataFileConfig,
    records: List<ByteArray>,
): Long {
    val totalSize = records.sumOf { it.size }
    val combined = ByteArray(totalSize)
    var pos = 0
    for (r in records) { r.copyInto(combined, pos); pos += r.size }
    return config.fileOps.appendAll(config.filename, combined)
}

fun batchAppend(
    config: DataFileConfig,
    records: Series<ByteArray>,
): Long {
    val n = records.size
    if (n == 0) return 0L
    val recSize = records[0].size
    val combined = ByteArray(n * recSize)
    for (i in 0 until n) records[i].copyInto(combined, i * recSize)
    return config.fileOps.appendAll(config.filename, combined)
}