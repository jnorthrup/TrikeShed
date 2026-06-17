package borg.trikeshed.couch.bucket

import borg.trikeshed.memory.ConfixMemoryStore
import borg.trikeshed.memory.ConfixBlock
import borg.trikeshed.mutable.*
import borg.trikeshed.parse.confix.ConfixDoc
import kotest.framework.test.TestConfig
import kotest.framework.test.TestFactory
import kotest.framework.runner.config.TestRunnerConfig

class BucketObjectStorageTest : TestFactory() {
    override fun config(): TestConfig = TestConfig(isolateTests = true)

    override fun tests() = listOf(
        test("BucketObjectStorage with miniduck-memory integration") {
            val memoryStore = ConfixMemoryStore()
            val blockStore = memoryStore.getBlockStore()

            // Create bucket with string keys and values
            val bucket = BucketObjectStorage<String, String>(
                keyOrder = { a, b -> a.compareTo(b) },
                valueToBlock = { k, v -> ConfixBlock.mutable().apply {
                    append(confixDocCell(
                        keys = listOf("key", "value"),
                        cells = listOf(k, v),
                    ))
                }},
                blockToValue = { block ->
                    val cell = block.cells.firstOrNull()
                    cell?.reify()?.let { (it as Map<*, *>)["value"] as? String } ?: ""
                },
                blockStore = blockStore,
                bucketName = "test-bucket",
                ringCapacity = 16,
                flushThreshold = 4,
            )

            // Add some entries (goes to ring buffer)
            bucket.put("key1", "value1")
            bucket.put("key2", "value2")
            bucket.put("key3", "value3")

            bucket.size shouldBe 3
            bucket.pendingCount shouldBe 3
            bucket.ringBuffer.size shouldBe 3
            bucket.sortedSeries.size shouldBe 0

            // Flush triggers ring -> sorted -> blockStore
            val result = bucket.flush()

            result.flushedFromRing shouldBe 3
            result.totalSorted shouldBe 3
            result.persistedToStore shouldBe 3
            result.blockCount shouldBe 3
            bucket.ringBuffer.size shouldBe 0
            bucket.sortedSeries.size shouldBe 3
            bucket.sortedEntries().map { it.key }.toList() shouldBe listOf("key1", "key2", "key3")

            // Data persisted in blockStore
            blockStore.list().size shouldBe 3

            // Journal committed
            bucket.pendingCount shouldBe 0
        },

        test("MergedSeries auto-flush threshold") {
            val memoryStore = ConfixMemoryStore()
            val blockStore = memoryStore.getBlockStore()

            val bucket = BucketObjectStorage<String, String>(
                keyOrder = { a, b -> a.compareTo(b) },
                valueToBlock = { k, v -> ConfixBlock.mutable().apply {
                    append(confixDocCell(keys = listOf("key", "value"), cells = listOf(k, v)))
                }},
                blockToValue = { block ->
                    val cell = block.cells.firstOrNull()
                    (cell?.reify() as? Map<*, *>)?.get("value") as? String ?: ""
                },
                blockStore = blockStore,
                bucketName = "auto-flush-bucket",
                ringCapacity = 100,
                flushThreshold = 2, // Auto-flush every 2 entries
            )

            // First put - stays in ring
            bucket.put("a", "1")
            bucket.ringBuffer.size shouldBe 1

            // Second put - triggers auto-flush
            bucket.put("b", "2")
            bucket.ringBuffer.size shouldBe 0
            bucket.sortedSeries.size shouldBe 2

            // Third put - goes to ring
            bucket.put("c", "3")
            bucket.ringBuffer.size shouldBe 1
            bucket.sortedSeries.size shouldBe 2

            // Manual flush
            bucket.flush()
            bucket.ringBuffer.size shouldBe 0
            bucket.sortedSeries.size shouldBe 3
        },

        test("Journal rollback") {
            val memoryStore = ConfixMemoryStore()
            val blockStore = memoryStore.getBlockStore()

            val bucket = BucketObjectStorage<String, String>(
                keyOrder = { a, b -> a.compareTo(b) },
                valueToBlock = { k, v -> ConfixBlock.mutable().apply {
                    append(confixDocCell(keys = listOf("key", "value"), cells = listOf(k, v)))
                }},
                blockToValue = { block ->
                    val cell = block.cells.firstOrNull()
                    (cell?.reify() as? Map<*, *>)?.get("value") as? String ?: ""
                },
                blockStore = blockStore,
                bucketName = "journal-bucket",
                ringCapacity = 16,
                flushThreshold = 4,
            )

            bucket.put("x", "1")
            bucket.put("y", "2")
            bucket.pendingCount shouldBe 2

            // Before flush, rollback clears journal but ring buffer is NOT cleared
            // (JournalSeries rollback operates on backing, not the input ring)
            bucket.rollback()
            bucket.pendingCount shouldBe 0
            bucket.ringBuffer.size shouldBe 2 // Ring buffer unchanged by journal rollback
        },

        test("Sorted order maintained after flush") {
            val memoryStore = ConfixMemoryStore()
            val blockStore = memoryStore.getBlockStore()

            val bucket = BucketObjectStorage<String, Int>(
                keyOrder = { a, b -> a.compareTo(b) },
                valueToBlock = { k, v -> ConfixBlock.mutable().apply {
                    append(confixDocCell(keys = listOf("key", "value"), cells = listOf(k, v)))
                }},
                blockToValue = { block ->
                    val cell = block.cells.firstOrNull()
                    (cell?.reify() as? Map<*, *>)?.get("value") as? Int ?: 0
                },
                blockStore = blockStore,
                bucketName = "sorted-bucket",
                ringCapacity = 100,
                flushThreshold = 10,
            )

            // Add out of order
            bucket.put("zebra", 100)
            bucket.put("alpha", 1)
            bucket.put("middle", 50)

            bucket.flush()

            val keys = bucket.sortedEntries().map { it.key }.toList()
            keys shouldBe listOf("alpha", "middle", "zebra")
        },

        test("Remove from sorted series") {
            val memoryStore = ConfixMemoryStore()
            val blockStore = memoryStore.getBlockStore()

            val bucket = BucketObjectStorage<String, String>(
                keyOrder = { a, b -> a.compareTo(b) },
                valueToBlock = { k, v -> ConfixBlock.mutable().apply {
                    append(confixDocCell(keys = listOf("key", "value"), cells = listOf(k, v)))
                }},
                blockToValue = { block ->
                    val cell = block.cells.firstOrNull()
                    (cell?.reify() as? Map<*, *>)?.get("value") as? String ?: ""
                },
                blockStore = blockStore,
                bucketName = "remove-bucket",
                ringCapacity = 16,
                flushThreshold = 4,
            )

            bucket.put("a", "1")
            bucket.put("b", "2")
            bucket.put("c", "3")
            bucket.flush()

            bucket.remove("b") shouldBe true
            bucket.sortedSeries.size shouldBe 2
            bucket.sortedEntries().map { it.key }.toList() shouldBe listOf("a", "c")

            bucket.remove("nonexistent") shouldBe false
        },

        test("Confix facets provide query projections") {
            val memoryStore = ConfixMemoryStore()
            val blockStore = memoryStore.getBlockStore()

            val bucket = BucketObjectStorage<String, String>(
                keyOrder = { a, b -> a.compareTo(b) },
                valueToBlock = { k, v -> ConfixBlock.mutable().apply {
                    append(confixDocCell(keys = listOf("key", "value"), cells = listOf(k, v)))
                }},
                blockToValue = { block ->
                    val cell = block.cells.firstOrNull()
                    (cell?.reify() as? Map<*, *>)?.get("value") as? String ?: ""
                },
                blockStore = blockStore,
                bucketName = "facet-bucket",
                ringCapacity = 16,
                flushThreshold = 4,
            )

            bucket.put("key1", "val1")
            bucket.put("key2", "val2")
            bucket.flush()

            val facets = bucket.confixFacets
            facets.keyIndex.toList() shouldBe listOf("facet-bucket_key1", "facet-bucket_key2")
            facets.blockIds.size shouldBe 2
            facets.ringBufferSeries.size shouldBe 0
            facets.sortedSeries.size shouldBe 2

            val meta = facets.metadata()
            meta.value("bucket") shouldBe "facet-bucket"
            meta.value("size") shouldBe 2
            meta.value("persistedBlocks") shouldBe 2
        },

        test("Factory creates in-memory bucket") {
            val bucket = BucketObjectStorageFactory.createInMemory<String, String>(
                keyOrder = { a, b -> a.compareTo(b) },
                valueToBlock = { k, v -> ConfixBlock.mutable().apply {
                    append(confixDocCell(keys = listOf("key", "value"), cells = listOf(k, v)))
                }},
                blockToValue = { block ->
                    val cell = block.cells.firstOrNull()
                    (cell?.reify() as? Map<*, *>)?.get("value") as? String ?: ""
                },
                bucketName = "factory-bucket",
            )

            bucket.put("test", "data")
            bucket.flush()

            bucket.size shouldBe 1
            bucket.confixBlockStore.list().size shouldBe 1
        },
    )
}