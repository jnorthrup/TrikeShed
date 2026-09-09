package borg.trikeshed.btrfs

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.UringCompletion
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.UserspaceChannelBackend
import borg.trikeshed.userspace.openUserspaceChannelBackend
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.Volume
import borg.trikeshed.userspace.nio.channels.UringChannel
import borg.trikeshed.userspace.nio.channels.UringChannels
import borg.trikeshed.userspace.nio.spi.currentNioCapabilityReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BtrfsRaidImageTest {
    private class Backend(private val delegate: UserspaceChannelBackend, var rejectWrites: Boolean) : UserspaceChannelBackend by delegate {
        var closes = 0
        var descriptorCloses = 0
        val opened = mutableListOf<Int>()
        var dataOffset: Long? = null
        var entered: CompletableDeferred<Unit>? = null
        var release: CompletableDeferred<Unit>? = null

        override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
            for (index in 0 until submissions.size) if (submissions[index].opcode == UringOp.CLOSE) descriptorCloses++
            if (rejectWrites && submissions.size == 1 && submissions[0].opcode == UringOp.WRITE) {
                return arrayOf(UringCompletion(submissions[0].userData, -5, 0)).toSeries()
            }
            if (dataOffset != null && submissions.size == 1 && submissions[0].opcode == UringOp.WRITE &&
                submissions[0].offset >= dataOffset!!) {
                entered?.complete(Unit)
                release?.await()
            }
            val result = delegate.batchEnqueue(submissions)
            for (index in 0 until submissions.size) if (submissions[index].opcode == UringOp.OPENAT) {
                for (completion in 0 until result.size) if (result[completion].userData == submissions[index].userData && result[completion].res >= 0) {
                    opened += result[completion].res
                }
            }
            return result
        }

        override fun close() { closes++; delegate.close() }
    }

    private class Images(val directory: Path) {
        val files = mutableListOf<BtrfsUringFileVolume>()
        val channels = mutableListOf<UringChannel>()

        suspend fun member(scope: CoroutineScope, name: String, blocks: Long = 8, create: Boolean = true): BtrfsUringFileVolume {
            val channel = UringChannels.open(scope, entries = 8)
            channels += channel
            val report = BtrfsUringChannelReport(channel.availability, channel.capabilities, channel.nativeCapabilities)
            return BtrfsUringFileVolume.open(channel, directory.resolve(name).toString(),
                blockSize = 512, capacity = blocks, create = create, resize = create,
                backendReport = currentNioCapabilityReport(), channelReport = report).also { files += it }
        }

        suspend fun drain() {
            files.forEach { it.drain() }
            channels.forEach { it.drain() }
            files.forEach { file ->
                assertEquals(1, file.ioReceipts().count { it.opcode == UringOp.CLOSE })
            }
        }

        fun delete() {
            Files.list(directory).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    private fun payload(blocks: Int) = ByteArray(blocks * 512) { ((it * 37 + it / 512 * 11) and 255).toByte() }

    @Test
    fun facade_image_span_persists_across_unequal_file_boundaries_and_reopen() = runTest {
        val images = Images(Files.createTempDirectory("trikeshed-span-"))
        try {
            val members = arrayOf(images.member(this, "a.img", 2), images.member(this, "b.img", 3), images.member(this, "c.img", 1))
            val volume = BtrfsRaidVolume.create(this, BtrfsVolumeLayout.SPAN, members.toSeries())
            val expected = payload(6)
            try {
                volume.write(0, ByteBuffer.wrap(expected))
                volume.sync()
                assertContentEquals(expected, volume.raidBytes())
            } finally { volume.drain() }
            images.drain()
            assertContentEquals(expected.copyOfRange(0, 1024), Files.readAllBytes(images.directory.resolve("a.img")))
            assertContentEquals(expected.copyOfRange(1024, 2560), Files.readAllBytes(images.directory.resolve("b.img")))
            assertContentEquals(expected.copyOfRange(2560, 3072), Files.readAllBytes(images.directory.resolve("c.img")))
            val reopenedMembers = arrayOf(images.member(this, "a.img", 2, false), images.member(this, "b.img", 3, false), images.member(this, "c.img", 1, false))
            val reopened = BtrfsRaidVolume.create(this, BtrfsVolumeLayout.SPAN, reopenedMembers.toSeries())
            try { assertContentEquals(expected, reopened.raidBytes()) } finally { reopened.drain() }
        } finally {
            images.drain()
            images.delete()
        }
    }

    @Test
    fun facade_images_raid0_raid1_raid10_and_dup_have_independent_physical_layouts() = runTest {
        for (layout in arrayOf(BtrfsVolumeLayout.RAID0, BtrfsVolumeLayout.RAID1, BtrfsVolumeLayout.RAID10, BtrfsVolumeLayout.DUP)) {
            val images = Images(Files.createTempDirectory("trikeshed-${layout.name.lowercase()}-"))
            try {
                val members = when (layout) {
                    BtrfsVolumeLayout.DUP -> arrayOf(images.member(this, "a.img", 8))
                    BtrfsVolumeLayout.RAID10 -> Array(4) { images.member(this, "$it.img", 4) }
                    else -> Array(2) { images.member(this, "$it.img", 4) }
                }
                val volume = BtrfsRaidVolume.create(this, layout, members.toSeries())
                val expected = payload(volume.capacity.toInt())
                try { volume.write(0, ByteBuffer.wrap(expected)); volume.sync() } finally { volume.drain() }
                images.drain()
                val physical = members.map { Files.readAllBytes(Path.of(it.imagePath)) }
                when (layout) {
                    BtrfsVolumeLayout.DUP -> assertContentEquals(expected + expected, physical[0])
                    BtrfsVolumeLayout.RAID1 -> physical.forEach { assertContentEquals(expected, it) }
                    else -> {
                        val even = expected.copyOfRange(0, 512) + expected.copyOfRange(1024, 1536) +
                            expected.copyOfRange(2048, 2560) + expected.copyOfRange(3072, 3584)
                        val odd = expected.copyOfRange(512, 1024) + expected.copyOfRange(1536, 2048) +
                            expected.copyOfRange(2560, 3072) + expected.copyOfRange(3584, 4096)
                        assertContentEquals(even, physical[0])
                        if (layout == BtrfsVolumeLayout.RAID10) {
                            assertContentEquals(even, physical[1])
                            assertContentEquals(odd, physical[2])
                            assertContentEquals(odd, physical[3])
                        } else assertContentEquals(odd, physical[1])
                    }
                }
            } finally { images.drain(); images.delete() }
        }
    }

    @Test
    fun parity_images_recover_partial_writes_replace_and_reopen() = runTest {
        for (layout in arrayOf(BtrfsVolumeLayout.RAID5, BtrfsVolumeLayout.RAID6)) {
            val images = Images(Files.createTempDirectory("trikeshed-${layout.name.lowercase()}-"))
            try {
                val count = if (layout == BtrfsVolumeLayout.RAID5) 4 else 5
                val members = Array(count) { BtrfsRaidFaultVolume(images.member(this, "$it.img", 6)) }
                val volume = BtrfsRaidVolume.create(this, layout, members.toSeries())
                val expected = payload(18)
                try {
                    volume.write(0, ByteBuffer.wrap(expected))
                    members[0].partialWriteFailure = true
                    if (layout == BtrfsVolumeLayout.RAID6) members[1].writeFailure = true
                    val patch = payload(5).reversedArray()
                    volume.write(2, ByteBuffer.wrap(patch))
                    patch.copyInto(expected, 1024)
                    assertContentEquals(expected, volume.raidBytes())
                    assertTrue(0 in volume.unavailable())
                    volume.replace(0, images.member(this, "replacement0.img", 6))
                    if (layout == BtrfsVolumeLayout.RAID6) volume.replace(1, images.member(this, "replacement1.img", 6))
                    assertEquals(emptySet(), volume.unavailable())
                    volume.sync()
                } finally { volume.drain() }
                images.drain()
                val reopenedMembers: Array<Volume> = Array(count) { index ->
                    val replaced = index == 0 || (layout == BtrfsVolumeLayout.RAID6 && index == 1)
                    images.member(this, if (replaced) "replacement$index.img" else "$index.img", 6, false)
                }
                val reopened = BtrfsRaidVolume.create(this, layout, reopenedMembers.toSeries())
                try { assertContentEquals(expected, reopened.raidBytes()) } finally { reopened.drain() }
            } finally { images.drain(); images.delete() }
        }
    }

    @Test
    fun owned_image_state_survives_degraded_write_replacement_and_reopen() = runTest {
        val directory = Files.createTempDirectory("trikeshed-raid-state-")
        try {
            val config = BtrfsRaidImageConfig(BtrfsVolumeLayout.RAID6, 512, 1,
                Array(5) { BtrfsRaidImageMember((it + 1).toULong(), directory.resolve("$it.img").toString(), 6) }.toSeries())
            val expected = payload(18)
            val created = BtrfsRaidImage.open(this, config, create = true, resize = true)
            try { created.write(0, ByteBuffer.wrap(expected)); created.sync() } finally { created.drain() }
            Files.delete(directory.resolve("0.img"))
            Files.delete(directory.resolve("1.img"))
            val degraded = BtrfsRaidImage.open(this, config, unavailable = setOf(0, 1))
            var replacedConfig = config
            try {
                assertContentEquals(expected, degraded.raidBytes())
                val patch = payload(4).reversedArray()
                degraded.write(1, ByteBuffer.wrap(patch))
                patch.copyInto(expected, 512)
                degraded.sync()
            } finally { degraded.drain() }
            // The caller no longer supplies the missing set: durable metadata must retain it.
            val reopenedDegraded = BtrfsRaidImage.open(this, config)
            try {
                assertEquals(setOf(0, 1), reopenedDegraded.volume.unavailable())
                assertContentEquals(expected, reopenedDegraded.raidBytes())
                reopenedDegraded.replace(0, BtrfsRaidImageMember(101u, directory.resolve("replacement0.img").toString(), 6))
                reopenedDegraded.replace(1, BtrfsRaidImageMember(102u, directory.resolve("replacement1.img").toString(), 6))
                replacedConfig = reopenedDegraded.config
                assertEquals(emptySet(), reopenedDegraded.volume.unavailable())
            } finally { reopenedDegraded.drain() }
            val reopened = BtrfsRaidImage.open(this, replacedConfig)
            try { assertContentEquals(expected, reopened.raidBytes()) } finally { reopened.drain() }
            val discovered = BtrfsRaidImage.reopen(this, directory.resolve("2.img").toString())
            try {
                assertEquals(BtrfsVolumeLayout.RAID6, discovered.config.layout)
                assertContentEquals(expected, discovered.raidBytes())
            } finally { discovered.drain() }
        } finally {
            Files.list(directory).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun owned_image_reopen_rejects_swapped_member_identity_without_changing_data() = runTest {
        val directory = Files.createTempDirectory("trikeshed-raid-identity-")
        try {
            val a = BtrfsRaidImageMember(1u, directory.resolve("a.img").toString(), 4)
            val b = BtrfsRaidImageMember(2u, directory.resolve("b.img").toString(), 4)
            val config = BtrfsRaidImageConfig(BtrfsVolumeLayout.RAID1, 512, 1, arrayOf(a, b).toSeries())
            val volume = BtrfsRaidImage.open(this, config, create = true, resize = true)
            try { volume.write(0, ByteBuffer.wrap(payload(4))) } finally { volume.drain() }
            val beforeA = Files.readAllBytes(directory.resolve("a.img"))
            val beforeB = Files.readAllBytes(directory.resolve("b.img"))
            val swapped = config.copy(members = arrayOf(a.copy(imagePath = b.imagePath), b.copy(imagePath = a.imagePath)).toSeries())
            assertFails { BtrfsRaidImage.open(this, swapped).drain() }
            assertContentEquals(beforeA, Files.readAllBytes(directory.resolve("a.img")))
            assertContentEquals(beforeB, Files.readAllBytes(directory.resolve("b.img")))
        } finally {
            Files.list(directory).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun prepared_redo_replays_a_torn_parity_row_after_reopen() = runTest {
        val images = Images(Files.createTempDirectory("trikeshed-raid-redo-"))
        try {
            val config = BtrfsRaidImageConfig(BtrfsVolumeLayout.RAID5, 512, 1,
                Array(3) { BtrfsRaidImageMember((it + 1).toULong(), images.directory.resolve("$it.img").toString(), 3) }.toSeries())
            val expected = payload(6)
            val volume = BtrfsRaidImage.open(this, config, create = true, resize = true)
            try { volume.write(0, ByteBuffer.wrap(expected)) } finally { volume.drain() }

            val reserve = BtrfsRaidMetadata.reservedBlocks(config)
            val raw: Array<Volume?> = Array(3) { images.member(this, "$it.img", reserve + 3, false) }
            val persisted = requireNotNull(BtrfsRaidMetadata.read(requireNotNull(raw[0]), config, 0))
            val journal = BtrfsRaidMetadata(raw, persisted)
            val a = ByteArray(512) { (it * 19 + 5).toByte() }
            val b = ByteArray(512) { (it * 43 + 8).toByte() }
            val parity = ByteArray(512) { (a[it].toInt() xor b[it].toInt()).toByte() }
            // Fixed first-row mapping: member A=data0, B=data1, C=P.
            val writes = arrayOf(BtrfsRaidWrite(0, 0, a), BtrfsRaidWrite(1, 0, b), BtrfsRaidWrite(2, 0, parity))
            assertEquals(emptySet(), journal.prepare(writes.toSeries(), emptySet()))
            // Stop after a prefix of the first data write, with neither remaining data nor P written.
            requireNotNull(raw[0]).write(reserve, ByteBuffer.wrap(a.copyOfRange(0, 97)))
            images.drain()
            a.copyInto(expected, 0)
            b.copyInto(expected, 512)
            val reopened = BtrfsRaidImage.open(this, config)
            try {
                assertContentEquals(expected, reopened.raidBytes())
                reopened.sync()
            } finally { reopened.drain() }
            for (index in 0..2) {
                val bytes = Files.readAllBytes(images.directory.resolve("$index.img"))
                val offset = (reserve * 512).toInt()
                assertContentEquals(writes[index].bytes, bytes.copyOfRange(offset, offset + 512))
            }
        } finally { images.drain(); images.delete() }
    }

    @Test
    fun failed_owned_replacement_closes_new_old_and_surviving_member_resources() = runTest {
        val directory = Files.createTempDirectory("trikeshed-raid-replace-failure-")
        val backends = mutableListOf<Backend>()
        try {
            val config = BtrfsRaidImageConfig(BtrfsVolumeLayout.RAID1, 512, 1,
                Array(2) { BtrfsRaidImageMember((it + 1).toULong(), directory.resolve("$it.img").toString(), 4) }.toSeries())
            val volume = BtrfsRaidImage.open(this, config, create = true, resize = true,
                channelFactory = { scope, entries ->
                    val backend = Backend(openUserspaceChannelBackend(entries), rejectWrites = backends.size == 2)
                    backends += backend
                    UringChannel(FunctionalUringFacade.create(scope, entries, backend))
                })
            try {
                volume.write(0, ByteBuffer.wrap(payload(4)))
                assertFails {
                    volume.replace(0, BtrfsRaidImageMember(101u, directory.resolve("replacement.img").toString(), 4))
                }
                assertFails { volume.read(0, 1) }
            } finally { volume.drain() }
            assertEquals(3, backends.size)
            backends.forEach {
                assertEquals(1, it.closes, "Each owned channel closes exactly once")
                assertEquals(1, it.descriptorCloses, "Each opened descriptor receives one CLOSE")
            }
        } finally {
            Files.list(directory).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun discovery_uses_the_second_metadata_slot_when_the_first_prefix_is_torn() = runTest {
        val directory = Files.createTempDirectory("trikeshed-raid-discovery-")
        try {
            val config = BtrfsRaidImageConfig(BtrfsVolumeLayout.RAID1, 512, 1,
                Array(2) { BtrfsRaidImageMember((it + 1).toULong(), directory.resolve("$it.img").toString(), 4) }.toSeries())
            val expected = payload(4)
            val volume = BtrfsRaidImage.open(this, config, create = true, resize = true)
            try { volume.write(0, ByteBuffer.wrap(expected)) } finally { volume.drain() }
            RandomAccessFile(directory.resolve("0.img").toFile(), "rw").use { it.write(ByteArray(32)) }
            val reopened = BtrfsRaidImage.reopen(this, directory.resolve("0.img").toString())
            try { assertContentEquals(expected, reopened.raidBytes()) } finally { reopened.drain() }
        } finally {
            Files.list(directory).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun owned_parent_cancellation_drains_backends_and_replays_the_prepared_block() = runTest {
        val directory = Files.createTempDirectory("trikeshed-raid-owner-cancel-")
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner)
        val backends = mutableListOf<Backend>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val config = BtrfsRaidImageConfig(BtrfsVolumeLayout.RAID5, 512, 1,
                Array(3) { BtrfsRaidImageMember((it + 1).toULong(), directory.resolve("$it.img").toString(), 3) }.toSeries())
            val expected = payload(6)
            val volume = BtrfsRaidImage.open(scope, config, create = true, resize = true,
                channelFactory = { childScope, entries ->
                    val backend = Backend(openUserspaceChannelBackend(entries), rejectWrites = false)
                    backends += backend
                    UringChannel(FunctionalUringFacade.create(childScope, entries, backend))
                })
            try {
                volume.write(0, ByteBuffer.wrap(expected))
                assertEquals(3, backends.sumOf { it.opened.size })
                backends[0].dataOffset = BtrfsRaidMetadata.reservedBlocks(config) * 512
                backends[0].entered = entered
                backends[0].release = release
                val patch = ByteArray(512) { (it * 17 + 91).toByte() }
                val source = ByteBuffer.wrap(patch)
                val writing = async { runCatching { volume.write(0, source) } }
                entered.await() // The replicated prepare has reached FSYNC before this data SQE.
                owner.cancel()
                runCurrent()
                assertFalse(writing.isCompleted, "The borrowed source remains owned until the data submission settles")
                release.complete(Unit)
                writing.await() // Cancellation may fail the logical call after its durable prepare.
                runCatching { volume.drain() }
                owner.join()
                backends.forEach { assertEquals(1, it.closes) }
                patch.copyInto(expected, 0)
                val reopened = BtrfsRaidImage.open(this, config)
                try {
                    assertContentEquals(expected, reopened.raidBytes())
                    assertEquals(emptySet(), reopened.volume.unavailable())
                } finally { reopened.drain() }
            } finally {
                release.complete(Unit)
                runCatching { volume.drain() }
            }
        } finally {
            release.complete(Unit)
            owner.cancel()
            owner.join()
            Files.list(directory).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun single_replacement_preserves_all_bytes_and_retires_the_old_discovery_path() = runTest {
        val directory = Files.createTempDirectory("trikeshed-single-replace-")
        try {
            val old = BtrfsRaidImageMember(1u, directory.resolve("old.img").toString(), 4)
            val replacement = BtrfsRaidImageMember(2u, directory.resolve("replacement.img").toString(), 4)
            val config = BtrfsRaidImageConfig(BtrfsVolumeLayout.SINGLE, 512, 1, arrayOf(old).toSeries())
            val expected = payload(4)
            val volume = BtrfsRaidImage.open(this, config, create = true, resize = true)
            try {
                volume.write(0, ByteBuffer.wrap(expected))
                volume.replace(0, replacement)
                assertContentEquals(expected, volume.raidBytes())
                assertEquals(replacement, volume.config.members[0])
                assertEquals(emptySet(), volume.unavailable())
            } finally { volume.drain() }
            assertFails { BtrfsRaidImage.reopen(this, old.imagePath).drain() }
            assertFails { BtrfsRaidImage.open(this, config).drain() }
            val reopened = BtrfsRaidImage.reopen(this, replacement.imagePath)
            try { assertContentEquals(expected, reopened.raidBytes()) } finally { reopened.drain() }
        } finally {
            Files.list(directory).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun failed_single_retirement_leaves_only_the_old_array_reopenable() = runTest {
        val directory = Files.createTempDirectory("trikeshed-single-retire-failure-")
        val backends = mutableListOf<Backend>()
        try {
            val old = BtrfsRaidImageMember(1u, directory.resolve("old.img").toString(), 4)
            val replacement = BtrfsRaidImageMember(2u, directory.resolve("replacement.img").toString(), 4)
            val config = BtrfsRaidImageConfig(BtrfsVolumeLayout.SINGLE, 512, 1, arrayOf(old).toSeries())
            val expected = payload(4)
            val volume = BtrfsRaidImage.open(this, config, create = true, resize = true,
                channelFactory = { scope, entries ->
                    val backend = Backend(openUserspaceChannelBackend(entries), rejectWrites = false)
                    backends += backend
                    UringChannel(FunctionalUringFacade.create(scope, entries, backend))
                })
            try {
                volume.write(0, ByteBuffer.wrap(expected))
                backends[0].rejectWrites = true
                assertFails { volume.replace(0, replacement) }
                assertFails { volume.read(0, 1) }
            } finally { volume.drain() }
            assertEquals(2, backends.size)
            backends.forEach {
                assertEquals(1, it.closes)
                assertEquals(1, it.descriptorCloses)
            }
            assertFails { BtrfsRaidImage.reopen(this, replacement.imagePath).drain() }
            val reopened = BtrfsRaidImage.reopen(this, old.imagePath)
            try { assertContentEquals(expected, reopened.raidBytes()) } finally { reopened.drain() }
        } finally {
            Files.list(directory).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }
}
