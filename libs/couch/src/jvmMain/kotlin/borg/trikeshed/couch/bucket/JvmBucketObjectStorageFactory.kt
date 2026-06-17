@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST", "NAME_SHADOWING")

package borg.trikeshed.couch.bucket

import borg.trikeshed.memory.ConfixBlockStore
import borg.trikeshed.memory.FileConfixBlockStore
import java.nio.file.Path

/**
 * JVM-specific factory for persistent BucketObjectStorage.
 * Uses miniduck-memory's FileConfixBlockStore for NDJSON-based persistence.
 */
object JvmBucketObjectStorageFactory {

    /**
     * Create a persistent BucketObjectStorage using FileConfixBlockStore.
     * Each flush writes blocks as .ndjson files in the bucket directory.
     */
    fun <K, V> createPersistent(
        keyOrder: (K, K) -> Int,
        valueToBlock: (K, V) -> ConfixBlock,
        blockToValue: (ConfixBlock) -> V,
        bucketName: String,
        baseDir: Path,
        ringCapacity: Int = 1024,
        flushThreshold: Int = 256,
    ): BucketObjectStorage<K, V> {
        val blockStore = FileConfixBlockStore(baseDir.resolve(bucketName))
        return BucketObjectStorage(
            keyOrder = keyOrder,
            valueToBlock = valueToBlock,
            blockToValue = blockToValue,
            blockStore = blockStore,
            bucketName = bucketName,
            ringCapacity = ringCapacity,
            flushThreshold = flushThreshold,
        )
    }

    /**
     * Create a persistent BucketObjectStorage with a custom ConfixBlockStore.
     */
    fun <K, V> createWithBlockStore(
        keyOrder: (K, K) -> Int,
        valueToBlock: (K, V) -> ConfixBlock,
        blockToValue: (ConfixBlock) -> V,
        bucketName: String,
        blockStore: ConfixBlockStore,
        ringCapacity: Int = 1024,
        flushThreshold: Int = 256,
    ): BucketObjectStorage<K, V> = BucketObjectStorage(
        keyOrder = keyOrder,
        valueToBlock = valueToBlock,
        blockToValue = blockToValue,
        blockStore = blockStore,
        bucketName = bucketName,
        ringCapacity = ringCapacity,
        flushThreshold = flushThreshold,
    )
}