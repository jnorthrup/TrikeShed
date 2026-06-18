@file:Suppress("unused")
package borg.trikeshed.isam

import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable

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
 */
interface FileOperations {
    /**
     * Append bytes to file.
     */
    fun append(filename: String, bytes: ByteArray): Long

    /**
     * Append multiple byte arrays.
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

// Simple platform utilities
inline  class PlatformUtilsImpl(
    val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
    val randomUuid: () -> String = { java.util.UUID.randomUUID().toString() },
    val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    val toPlatformByteArray: (String) -> ByteArray = { it.toByteArray() },
    val toPlatformString: (ByteArray) -> String = { String(it) },
)
val platformUtils = PlatformUtilsImpl()

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

@Serializable
data class DataFileConfig(
    val filename: String,
    val recordlen: Int,
    val fileOps: FileOperations,
)

inline  class DataFilePosition(val offset: Long = 0)

// ---------------------------------------------------------------------------
// Reified inline append
// ---------------------------------------------------------------------------

inline fun appendToIsam(
    crossinline config: DataFileConfig,
    crossinline records: Series<ByteArray>,
): Long = config.fileOps.appendAll(config.filename, records.toByteArray())

inline fun appendToIsam(
    crossinline config: DataFileConfig,
    crossinline record: ByteArray,
): Long = config.fileOps.append(config.filename, record)

// ---------------------------------------------------------------------------
// Reified inline scan with projection
// ---------------------------------------------------------------------------

inline fun <T> scanIsam(
    crossinline config: DataFileConfig,
    crossinline decoder: (ByteArray) -> T,
    crossinline projection: (T) -> T,
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

inline fun <T> scanIsamRange(
    crossinline config: DataFileConfig,
    crossinline decoder: (ByteArray) -> T,
    crossinline projection: (T) -> T,
    start: Int,
    end: Int,
): Series<T> = scanIsam(config, decoder, projection).slice(start until min(end, (config.fileOps.size(config.filename) / config.recordlen).toInt()))

// ---------------------------------------------------------------------------
// Reified inline get by index
// ---------------------------------------------------------------------------

inline fun <T> getIsam(
    crossinline config: DataFileConfig,
    crossinline decoder: (ByteArray) -> T,
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

inline  class PointcutJournalConfig(
    val filename: String,
    val recordlen: Int = 128,
    val fileOps: FileOperations,
)

inline fun appendPointcut(
    crossinline config: PointcutJournalConfig,
    crossinline pointcut: ByteArray,
): Long = config.fileOps.append(config.filename, pointcut)

inline fun scanPointcuts(
    crossinline config: PointcutJournalConfig,
): Series<ByteArray> {
    val fileSize = config.fileOps.size(config.filename)
    val recordCount = (fileSize / config.recordlen).toInt()
    return recordCount j { i ->
        val offset = i.toLong() * config.recordlen
        config.fileOps.read(config.filename, offset, config.recordlen)
    }
}

// ---------------------------------------------------------------------------
// ReduxMutableSeries checkpoint (for ISAM state)
// ---------------------------------------------------------------------------

/**
 * Checkpoint a ReduxMutableSeries to ISAM.
 * Uses the capture -> body flow for Confix-style persistence.
 */
inline fun checkpointToIsam<State>(
    crossinline series: ReduxMutableSeries<State>,
    crossinline config: DataFileConfig,
    crossinline encoder: (State) -> ByteArray,
): Long {
    val body = series.capture()
    val bytes = encoder(body)
    return config.fileOps.append(config.filename, bytes)
}

inline fun restoreFromIsam<State>(
    crossinline config: DataFileConfig,
    crossinline decoder: (ByteArray) -> State,
): State? {
    val fileSize = config.fileOps.size(config.filename)
    if (fileSize == 0) return null
    val lastRecord = fileSize / config.recordlen - 1
    return getIsam(config, decoder, lastRecord)
}

// ---------------------------------------------------------------------------
// File layout spec (reified)
// ---------------------------------------------------------------------------

inline  class FileLayoutSpec(
    val recordlen: Int,
    val headerSize: Int = 0,
    val indexStride: Int = 0, // 0 = no index
) {
    inline fun calcOffset(index: Int): Long = (index.toLong() * recordlen) + headerSize
    inline fun recordCount(fileSize: Long): Int = ((fileSize - headerSize) / recordlen).toInt()
}

// ---------------------------------------------------------------------------
// ISAM operations as flow
// ---------------------------------------------------------------------------

inline fun <T> isamFlow(
    crossinline config: DataFileConfig,
    crossinline decoder: (ByteArray) -> T,
    crossinline predicate: (T) -> Boolean = { true },
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
}.flowOn(Dispatchers.IO)

// ---------------------------------------------------------------------------
// Batch operations
// ---------------------------------------------------------------------------

inline fun batchAppend(
    crossinline config: DataFileConfig,
    crossinline records: List<ByteArray>,
): Long = config.fileOps.appendAll(config.filename, records.toByteArray())

inline fun batchAppend(
    crossinline config: DataFileConfig,
    crossinline records: Series<ByteArray>,
): Long = config.fileOps.appendAll(config.filename, records.toByteArray())