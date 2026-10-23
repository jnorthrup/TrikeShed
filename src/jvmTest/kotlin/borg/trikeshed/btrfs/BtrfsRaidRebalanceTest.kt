package borg.trikeshed.btrfs

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.InMemoryVolume
import borg.trikeshed.userspace.nio.Volume
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BtrfsRaidRebalanceTest {
    private fun config(directory: Path, prefix: String, layout: BtrfsVolumeLayout = BtrfsVolumeLayout.SPAN,
                       blocks: Long = 4, members: Int = 2) =
        BtrfsRaidImageConfig(layout, 512, 1,
            Array(members) { BtrfsRaidImageMember((it + 1).toULong(), directory.resolve("$prefix-$it.img").toString(), blocks) }.toSeries())

    private fun payload(blocks: Int) = ByteArray(blocks * 512) { ((it * 37 + it / 512 * 13) and 255).toByte() }
    private suspend fun Volume.bytes(lba: Long, count: Int): ByteArray = read(lba, count).let { buffer ->
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
    private fun remove(directory: Path) { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }

    private class Publication(private val delegate: FileOperations) : FileOperations by delegate {
        var failure: Int = 0
        override fun writeAtomically(filename: String, bytes: ByteArray) {
            if (failure == 1) error("Injected failure before manifest replacement")
            delegate.writeAtomically(filename, bytes)
            if (failure == 2) error("Injected failure after manifest replacement")
        }
    }

    @Test
    fun addition_and_profile_rebalance_preserve_the_logical_image_across_reopen() = runTest {
        val directory = Files.createTempDirectory("btrfs-generations-")
        val manifest = directory.resolve("volume.cbor").toString()
        val files = JvmFileOperations()
        val expected = payload(8)
        try {
            val volume = BtrfsRaidRebalance.create(this, files, manifest, config(directory, "initial")) {
                it.write(0, ByteBuffer.wrap(expected))
            }
            try {
                assertEquals(1L, volume.generation())
                assertEquals(2L, volume.migrate(config(directory, "added", members = 3)))
                assertEquals(12L, volume.capacity)
                assertContentEquals(expected, volume.bytes(0, 8))
                assertContentEquals(ByteArray(4 * 512), volume.bytes(8, 4))
                assertEquals(3L, volume.migrate(config(directory, "mirror", BtrfsVolumeLayout.RAID1, blocks = 12)))
                volume.setMetadataCompression(BtrfsMetadataCompression.ZSTD)
                assertEquals(4L, volume.generation())
                assertContentEquals(expected, volume.bytes(0, 8))
            } finally { volume.drain() }
            val reopened = BtrfsRaidRebalance.open(this, JvmFileOperations(), manifest)
            try {
                assertEquals(4L, reopened.generation())
                assertEquals(BtrfsMetadataCompression.ZSTD, reopened.metadataCompression)
                assertEquals(BtrfsVolumeLayout.RAID1, reopened.config().layout)
                assertContentEquals(expected, reopened.bytes(0, 8))
                assertContentEquals(ByteArray(4 * 512), reopened.bytes(8, 4))
            } finally { reopened.drain() }
            assertTrue(Files.exists(directory.resolve("initial-0.img")))
        } finally { remove(directory) }
    }

    @Test
    fun interrupted_staging_preserves_prior_generation_and_does_not_lose_queued_writes() = runTest {
        val directory = Files.createTempDirectory("btrfs-relocation-")
        val manifest = directory.resolve("volume.cbor").toString()
        try {
            val volume = BtrfsRaidRebalance.create(this, JvmFileOperations(), manifest, config(directory, "initial"))
            try {
                val original = payload(8)
                volume.write(0, ByteBuffer.wrap(original))
                assertFailsWith<IllegalStateException> {
                    volume.migrate(config(directory, "failed", members = 3)) {
                        it.write(0, ByteBuffer.wrap(ByteArray(512) { 99 }))
                        error("Injected interrupted filesystem rewrite")
                    }
                }
                assertEquals(1L, volume.generation())
                assertContentEquals(original, volume.bytes(0, 8))

                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val relocation = async {
                    volume.migrate(config(directory, "accepted", members = 3)) { entered.complete(Unit); release.await() }
                }
                entered.await()
                val after = ByteArray(512) { 42 }
                val writer = async { volume.write(5, ByteBuffer.wrap(after)) }
                runCurrent()
                assertFalse(writer.isCompleted)
                release.complete(Unit)
                assertEquals(2L, relocation.await())
                writer.await()
                assertContentEquals(after, volume.bytes(5, 1))
                assertContentEquals(original.copyOfRange(0, 5 * 512), volume.bytes(0, 5))
            } finally { volume.drain() }
            val reopened = BtrfsRaidRebalance.open(this, JvmFileOperations(), manifest)
            try { assertContentEquals(ByteArray(512) { 42 }, reopened.bytes(5, 1)) } finally { reopened.drain() }
        } finally { remove(directory) }
    }

    @Test
    fun publication_failure_requires_reopen_and_selects_the_actual_published_generation() = runTest {
        for (phase in 1..2) {
            val directory = Files.createTempDirectory("btrfs-publication-")
            val manifest = directory.resolve("volume.cbor").toString()
            val files = Publication(JvmFileOperations())
            try {
                val volume = BtrfsRaidRebalance.create(this, files, manifest, config(directory, "initial")) {
                    it.write(0, ByteBuffer.wrap(ByteArray(512) { 7 }))
                }
                try {
                    files.failure = phase
                    assertFailsWith<IllegalStateException> {
                        volume.transact { it.write(0, ByteBuffer.wrap(ByteArray(512) { 9 })) }
                    }
                    assertFailsWith<BtrfsRaidException> { volume.read(0, 1) }
                } finally { volume.drain() }
                val reopened = BtrfsRaidRebalance.open(this, JvmFileOperations(), manifest)
                try {
                    assertEquals(if (phase == 1) 1L else 2L, reopened.generation())
                    assertContentEquals(ByteArray(512) { if (phase == 1) 7 else 9 }, reopened.bytes(0, 1))
                } finally { reopened.drain() }
            } finally { remove(directory) }
        }
    }

    @Test
    fun transaction_generates_fresh_files_and_publishes_only_after_initialization() = runTest {
        val directory = Files.createTempDirectory("btrfs-transaction-")
        val manifest = directory.resolve("volume.cbor").toString()
        try {
            val volume = BtrfsRaidRebalance.create(this, JvmFileOperations(), manifest, config(directory, "initial")) {
                it.write(0, ByteBuffer.wrap(payload(8)))
            }
            try {
                val previous = volume.config()
                assertEquals(2L, volume.transact(copy = false) { it.write(2, ByteBuffer.wrap(ByteArray(512) { 11 })) })
                val current = volume.config()
                assertTrue((0 until current.members.size).all { current.members[it].imagePath != previous.members[it].imagePath })
                assertContentEquals(ByteArray(512) { 11 }, volume.bytes(2, 1))
                assertContentEquals(ByteArray(512), volume.bytes(0, 1))
            } finally { volume.drain() }
        } finally { remove(directory) }
    }

    @Test
    fun alias_overlap_shrink_and_concurrent_mount_refuse_without_altering_source() = runTest {
        val directory = Files.createTempDirectory("btrfs-topology-validation-")
        val manifest = directory.resolve("volume.cbor").toString()
        try {
            val volume = BtrfsRaidRebalance.create(this, JvmFileOperations(), manifest, config(directory, "initial")) {
                it.write(0, ByteBuffer.wrap(payload(8)))
            }
            try {
                assertFails { BtrfsRaidRebalance.open(this, JvmFileOperations(), directory.resolve("./volume.cbor").toString()) }
                assertFailsWith<IllegalArgumentException> { volume.migrate(volume.config()) }
                assertFailsWith<IllegalArgumentException> { volume.migrate(config(directory, "small", blocks = 2)) }
                assertFalse(Files.exists(directory.resolve("small-0.img")))
                assertEquals(1L, volume.generation())
                assertContentEquals(payload(8), volume.bytes(0, 8))
            } finally { volume.drain() }
        } finally { remove(directory) }
    }

    @Test
    fun cancelling_an_admitted_relocation_waits_for_publication_before_drain() = runTest {
        val directory = Files.createTempDirectory("btrfs-relocation-cancel-")
        val manifest = directory.resolve("volume.cbor").toString()
        try {
            val volume = BtrfsRaidRebalance.create(this, JvmFileOperations(), manifest, config(directory, "initial"))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val relocation = async {
                volume.transact { entered.complete(Unit); release.await(); it.write(0, ByteBuffer.wrap(ByteArray(512) { 21 })) }
            }
            entered.await()
            relocation.cancel()
            val draining = async { volume.drain() }
            runCurrent()
            assertFalse(draining.isCompleted)
            release.complete(Unit)
            relocation.join()
            draining.await()
            val reopened = BtrfsRaidRebalance.open(this, JvmFileOperations(), manifest)
            try {
                assertEquals(2L, reopened.generation())
                assertContentEquals(ByteArray(512) { 21 }, reopened.bytes(0, 1))
            } finally { reopened.drain() }
        } finally { remove(directory) }
    }

    @Test
    fun operations_after_drain_reject_without_waiting_for_an_absent_consumer() = runTest {
        val directory = Files.createTempDirectory("btrfs-closed-generation-")
        val manifest = directory.resolve("volume.cbor").toString()
        try {
            val volume = BtrfsRaidRebalance.create(this, JvmFileOperations(), manifest, config(directory, "initial"))
            volume.drain()
            withTimeout(1000) {
                assertFailsWith<kotlinx.coroutines.channels.ClosedSendChannelException> { volume.read(0, 1) }
                assertFailsWith<kotlinx.coroutines.channels.ClosedSendChannelException> { volume.generation() }
                assertFailsWith<kotlinx.coroutines.channels.ClosedSendChannelException> { volume.transact {} }
                volume.drain()
            }
        } finally { remove(directory) }
    }

    @Test
    fun closed_underlying_raid_rejects_without_waiting_for_an_absent_consumer() = runTest {
        val raw = BtrfsRaidVolume.create(this, BtrfsVolumeLayout.SINGLE, s_[InMemoryVolume(512, 4)])
        raw.drain()
        withTimeout(1000) {
            assertFailsWith<kotlinx.coroutines.channels.ClosedSendChannelException> { raw.read(0, 1) }
            assertFailsWith<kotlinx.coroutines.channels.ClosedSendChannelException> { raw.write(0, ByteBuffer.wrap(ByteArray(512))) }
            assertFailsWith<kotlinx.coroutines.channels.ClosedSendChannelException> { raw.sync() }
            raw.drain()
        }
    }

    @Test
    fun owner_cancellation_settles_staged_relocation_before_releasing_the_manifest_lease() = runTest {
        val directory = Files.createTempDirectory("btrfs-relocation-owner-")
        val manifest = directory.resolve("volume.cbor").toString()
        val owner = SupervisorJob(coroutineContext[Job])
        val owned = CoroutineScope(coroutineContext + owner)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val original = ByteArray(512) { 17 }
        val volume = BtrfsRaidRebalance.create(owned, JvmFileOperations(), manifest, config(directory, "initial")) {
            it.write(0, ByteBuffer.wrap(original))
        }
        try {
            val relocation = async {
                runCatching {
                    volume.transact { staged ->
                        entered.complete(Unit)
                        release.await()
                        staged.write(0, ByteBuffer.wrap(ByteArray(512) { 29 }))
                    }
                }
            }
            entered.await()
            owner.cancel()
            runCurrent()
            assertFalse(owner.isCompleted)
            release.complete(Unit)
            // Parent cancellation closes admission on the staged RAID pipeline.
            // The interrupted transaction must leave the old manifest readable.
            assertTrue(relocation.await().isFailure)
            withTimeout(5000) { owner.join(); volume.drain() }
            val reopened = BtrfsRaidRebalance.open(this, JvmFileOperations(), manifest)
            try {
                assertEquals(1L, reopened.generation())
                assertContentEquals(original, reopened.bytes(0, 1))
            } finally { reopened.drain() }
        } finally {
            release.complete(Unit)
            owner.cancel()
            volume.drain()
            owner.join()
            remove(directory)
        }
    }

    @Test
    fun oversized_manifest_rejects_before_allocation_and_releases_its_lease() = runTest {
        val directory = Files.createTempDirectory("btrfs-manifest-bound-")
        val manifestPath = directory.resolve("volume.cbor")
        val manifest = manifestPath.toString()
        val delegate = JvmFileOperations()
        var readManifest = false
        val files = object : FileOperations by delegate {
            override fun readAllBytes(filename: String): ByteArray {
                if (filename == manifest) readManifest = true
                return delegate.readAllBytes(filename)
            }
        }
        try {
            val volume = BtrfsRaidRebalance.create(this, files, manifest, config(directory, "initial"))
            volume.drain()
            val original = Files.readAllBytes(manifestPath)
            java.io.RandomAccessFile(manifest, "rw").use { it.setLength(33L * 1024 * 1024) }
            assertFailsWith<IllegalArgumentException> { BtrfsRaidRebalance.open(this, files, manifest) }
            assertFalse(readManifest)
            Files.write(manifestPath, original)
            val reopened = BtrfsRaidRebalance.open(this, files, manifest)
            try { assertEquals(1L, reopened.generation()) } finally { reopened.drain() }
        } finally { remove(directory) }
    }

    @Test
    fun native_metadata_sidecar_survives_relocation_and_codec_change_without_raw_tree_copies() = runTest {
        val directory = Files.createTempDirectory("btrfs-compressed-generations-")
        val manifest = directory.resolve("volume.cbor").toString()
        val tree = ByteArray(4 * 512) { (it % 7).toByte() }
        fun rawTreesAreAbsent(config: BtrfsRaidImageConfig) {
            val raw = Files.readAllBytes(Path.of(config.members[0].imagePath))
            val start = ((BtrfsRaidMetadata.reservedBlocks(config) + 2) * 512).toInt()
            assertContentEquals(ByteArray(tree.size), raw.copyOfRange(start, start + tree.size))
            assertTrue(Files.size(Path.of(config.members[0].imagePath + ".metadata")) < tree.size)
        }
        try {
            val volume = BtrfsRaidRebalance.create(this, JvmFileOperations(), manifest,
                config(directory, "initial", blocks = 8), BtrfsMetadataCompression.LZ4) { staged ->
                staged.write(0, ByteBuffer.wrap(ByteArray(512) { 41 }))
                (staged as BtrfsMetadataSink).writeMetadata(2, ByteBuffer.wrap(tree))
            }
            var sidecar: Path? = null
            try {
                rawTreesAreAbsent(volume.config())
                assertContentEquals(tree, volume.bytes(2, 4))
                assertFailsWith<IllegalStateException> { volume.write(2, ByteBuffer.wrap(ByteArray(512))) }
                volume.migrate(config(directory, "added", blocks = 8, members = 3))
                rawTreesAreAbsent(volume.config())
                assertContentEquals(tree, volume.bytes(2, 4))
                volume.setMetadataCompression(BtrfsMetadataCompression.ZSTD)
                assertEquals(3L, volume.generation())
                rawTreesAreAbsent(volume.config())
                assertContentEquals(tree, volume.bytes(2, 4))
                sidecar = Path.of(volume.config().members[0].imagePath + ".metadata")
            } finally { volume.drain() }
            val reopened = BtrfsRaidRebalance.open(this, JvmFileOperations(), manifest)
            try {
                assertContentEquals(tree, reopened.bytes(2, 4))
                assertContentEquals(ByteArray(512) { 41 }, reopened.bytes(0, 1))
            } finally { reopened.drain() }
            val metadataPath = requireNotNull(sidecar)
            val original = Files.readAllBytes(metadataPath)
            val corrupt = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            Files.write(metadataPath, corrupt)
            assertFails { BtrfsRaidRebalance.open(this, JvmFileOperations(), manifest) }
            Files.write(metadataPath, original)
            val recovered = BtrfsRaidRebalance.open(this, JvmFileOperations(), manifest)
            try { assertContentEquals(tree, recovered.bytes(2, 4)) } finally { recovered.drain() }
        } finally { remove(directory) }
    }
}
