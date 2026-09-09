package borg.trikeshed.btrfs

import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.InMemoryVolume
import borg.trikeshed.userspace.nio.Volume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Failure is injected at the Volume boundary, after admission to the RAID composition. */
internal class BtrfsRaidFaultVolume(val delegate: Volume) : Volume by delegate {
    var readFailure = false
    var shortRead = false
    var writeFailure = false
    var partialWriteFailure = false
    var shortWrite = false
    var syncFailure = false
    var entered: CompletableDeferred<Unit>? = null
    var release: CompletableDeferred<Unit>? = null

    override suspend fun read(lba: Long, count: Int): ByteBuffer {
        check(!readFailure) { "Injected member read failure" }
        val data = delegate.read(lba, count)
        if (shortRead && data.remaining() > 0) data.limit(data.limit() - 1)
        return data
    }

    override suspend fun write(lba: Long, data: ByteBuffer) {
        entered?.complete(Unit)
        release?.await()
        if (partialWriteFailure) {
            val prefix = ByteArray(maxOf(1, data.remaining() / 2))
            data.get(prefix)
            delegate.write(lba, ByteBuffer.wrap(prefix))
            error("Injected member failure after a partial physical write")
        }
        check(!writeFailure) { "Injected member write failure" }
        if (shortWrite) {
            val prefix = ByteArray(data.remaining() / 2)
            data.get(prefix)
            delegate.write(lba, ByteBuffer.wrap(prefix))
            return
        }
        delegate.write(lba, data)
    }

    override suspend fun sync() {
        check(!syncFailure) { "Injected member sync failure" }
        delegate.sync()
    }
}

internal suspend fun Volume.raidBytes(lba: Long = 0, count: Int = capacity.toInt()): ByteArray {
    val data = read(lba, count)
    return ByteArray(data.remaining()).also { data.get(it) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BtrfsRaidVolumeTest {
    private fun member(capacity: Long = 8) = BtrfsRaidFaultVolume(InMemoryVolume(16, capacity))
    private fun payload(blocks: Int) = ByteArray(blocks * 16) { ((it * 29 + it / 16 * 7) and 255).toByte() }

    @Test
    fun ranges_reject_overflow_before_member_io_or_buffer_consumption() = runTest {
        val member = object : Volume {
            override val blockSize = 16
            override val capacity = Long.MAX_VALUE
            override suspend fun read(lba: Long, count: Int): ByteBuffer = error("Unexpected member read")
            override suspend fun write(lba: Long, data: ByteBuffer): Unit = error("Unexpected member write")
            override suspend fun sync() = Unit
        }
        val volume = BtrfsRaidVolume.create(this, BtrfsVolumeLayout.SINGLE, arrayOf(member).toSeries())
        try {
            val source = ByteBuffer.wrap(ByteArray(17))
            assertFails { volume.read(Long.MAX_VALUE, 1) }
            assertFails { volume.read(Long.MAX_VALUE - 1, 2) }
            assertFails { volume.read(-1, 0) }
            assertFails { volume.read(0, -1) }
            assertFails { volume.read(0, Int.MAX_VALUE) }
            assertFails { volume.write(Long.MAX_VALUE - 1, source) }
            assertEquals(0, source.position())
            assertEquals(0, volume.read(Long.MAX_VALUE, 0).remaining())
            volume.write(Long.MAX_VALUE, ByteBuffer.wrap(ByteArray(0)))
        } finally { volume.drain() }
    }

    @Test
    fun degraded_parity_partial_slices_cross_full_stripe_boundaries() = runTest {
        for (layout in arrayOf(BtrfsVolumeLayout.RAID5, BtrfsVolumeLayout.RAID6)) {
            val members = Array(if (layout == BtrfsVolumeLayout.RAID5) 4 else 5) { member(6) }
            val volume = BtrfsRaidVolume.create(this, layout, members.toSeries(), stripeBlocks = 2)
            try {
                val expected = payload(volume.capacity.toInt())
                volume.write(0, ByteBuffer.wrap(expected))
                volume.offline(0)
                if (layout == BtrfsVolumeLayout.RAID6) volume.offline(1)
                val patch = ByteArray(48) { (211 - it).toByte() }
                val source = ByteBuffer.wrap(patch).position(3).limit(38).slice().position(2).limit(31)
                val lba = volume.geometry.dataCount * 2L - 1
                volume.write(lba, source)
                patch.copyInto(expected, (lba * 16).toInt(), 5, 34)
                assertEquals(31, source.position())
                assertContentEquals(expected, volume.raidBytes())
                volume.replace(0, member(6))
                if (layout == BtrfsVolumeLayout.RAID6) volume.replace(1, member(6))
                assertContentEquals(expected, volume.raidBytes())
            } finally { volume.drain() }
        }
    }

    @Test
    fun replacement_cannot_clear_an_incomplete_transaction() = runTest {
        val persistence = object : BtrfsRaidPersistence {
            override suspend fun prepare(writes: borg.trikeshed.lib.Series<BtrfsRaidWrite>, unavailable: Set<Int>) = unavailable
            override suspend fun commit(unavailable: Set<Int>): Set<Int> = error("Injected commit failure")
        }
        val members = Array(2) { member(2) }
        val volume = BtrfsRaidVolume.create(this, BtrfsVolumeLayout.RAID1, members.toSeries(), persistence = persistence)
        try {
            assertFails { volume.write(0, ByteBuffer.wrap(payload(1))) }
            val replacement = member(2)
            assertFails { volume.replace(0, replacement) }
            assertContentEquals(ByteArray(32), replacement.raidBytes())
            assertFails { volume.read(0, 1) }
        } finally { volume.drain() }
    }

    @Test
    fun parity_disagreement_is_rejected_without_guessing_a_corrupt_member() = runTest {
        for (layout in arrayOf(BtrfsVolumeLayout.RAID5, BtrfsVolumeLayout.RAID6)) {
            val members = Array(if (layout == BtrfsVolumeLayout.RAID5) 3 else 4) { member(2) }
            val volume = BtrfsRaidVolume.create(this, layout, members.toSeries())
            try {
                volume.write(0, ByteBuffer.wrap(payload(4)))
                members[0].delegate.write(0, ByteBuffer.wrap(byteArrayOf(99)))
                assertFails { volume.read(0, 1) }
                assertEquals(emptySet(), volume.unavailable())
            } finally { volume.drain() }
        }
    }

    @Test
    fun span_crosses_unequal_member_boundaries_and_preserves_partial_blocks() = runTest {
        val members = arrayOf(member(2), member(3), member(1))
        val volume = BtrfsRaidVolume.create(this, BtrfsVolumeLayout.SPAN, members.toSeries())
        try {
            assertEquals(6L, volume.capacity)
            val expected = payload(6)
            volume.write(0, ByteBuffer.wrap(expected))
            assertContentEquals(expected.copyOfRange(0, 32), members[0].raidBytes())
            assertContentEquals(expected.copyOfRange(32, 80), members[1].raidBytes())
            assertContentEquals(expected.copyOfRange(80, 96), members[2].raidBytes())
            val patch = ByteBuffer.wrap(byteArrayOf(90, 91, 92, 93, 94, 95, 96, 97))
            patch.position(2).limit(7)
            volume.write(2, patch)
            byteArrayOf(92, 93, 94, 95, 96).copyInto(expected, 32)
            assertEquals(7, patch.position())
            assertContentEquals(expected, volume.raidBytes())
            assertFails { volume.read(5, 2) }
            assertFails { volume.write(6, ByteBuffer.wrap(byteArrayOf(1))) }
        } finally { volume.drain() }
        // The engine borrows its members; draining it must retain their usability.
        members[2].write(0, ByteBuffer.wrap(byteArrayOf(44)))
        assertEquals(44, members[2].raidBytes()[0].toInt())
    }

    @Test
    fun raid0_physical_stripes_follow_a_fixed_independent_byte_table() = runTest {
        val members = arrayOf(member(4), member(4), member(4))
        val volume = BtrfsRaidVolume.create(this, BtrfsVolumeLayout.RAID0, members.toSeries(), stripeBlocks = 2)
        try {
            val expected = payload(12)
            volume.write(0, ByteBuffer.wrap(expected))
            // Two-block stripes: A=[0,1,6,7], B=[2,3,8,9], C=[4,5,10,11].
            assertContentEquals(expected.copyOfRange(0, 32) + expected.copyOfRange(96, 128), members[0].raidBytes())
            assertContentEquals(expected.copyOfRange(32, 64) + expected.copyOfRange(128, 160), members[1].raidBytes())
            assertContentEquals(expected.copyOfRange(64, 96) + expected.copyOfRange(160, 192), members[2].raidBytes())
            assertContentEquals(expected.copyOfRange(16, 176), volume.raidBytes(1, 10))
            members[1].readFailure = true
            assertFails { volume.read(2, 1) }
        } finally { volume.drain() }
    }

    @Test
    fun mirror_dup_and_raid10_use_separate_expected_copy_locations() = runTest {
        for (layout in arrayOf(BtrfsVolumeLayout.RAID1, BtrfsVolumeLayout.DUP, BtrfsVolumeLayout.RAID10)) {
            val members = when (layout) {
                BtrfsVolumeLayout.DUP -> arrayOf(member(8))
                BtrfsVolumeLayout.RAID10 -> Array(4) { member(4) }
                else -> Array(3) { member(4) }
            }
            val volume = BtrfsRaidVolume.create(this, layout, members.toSeries())
            try {
                val expected = payload(volume.capacity.toInt())
                volume.write(0, ByteBuffer.wrap(expected))
                when (layout) {
                    BtrfsVolumeLayout.DUP -> assertContentEquals(expected + expected, members[0].raidBytes())
                    BtrfsVolumeLayout.RAID1 -> members.forEach { assertContentEquals(expected, it.raidBytes()) }
                    BtrfsVolumeLayout.RAID10 -> {
                        val a = expected.copyOfRange(0, 16) + expected.copyOfRange(32, 48) +
                            expected.copyOfRange(64, 80) + expected.copyOfRange(96, 112)
                        val b = expected.copyOfRange(16, 32) + expected.copyOfRange(48, 64) +
                            expected.copyOfRange(80, 96) + expected.copyOfRange(112, 128)
                        assertContentEquals(a, members[0].raidBytes())
                        assertContentEquals(a, members[1].raidBytes())
                        assertContentEquals(b, members[2].raidBytes())
                        assertContentEquals(b, members[3].raidBytes())
                    }
                    else -> error("Unexpected layout")
                }
                assertContentEquals(expected, volume.raidBytes())
            } finally { volume.drain() }
        }
    }

    @Test
    fun raid5_recovers_every_failed_member_and_rebuilds_a_replacement() = runTest {
        for (missing in 0 until 4) {
            val members = Array(4) { member(4) }
            val volume = BtrfsRaidVolume.create(this, BtrfsVolumeLayout.RAID5, members.toSeries())
            try {
                val expected = payload(12)
                volume.write(0, ByteBuffer.wrap(expected))
                members[missing].readFailure = true
                members[missing].writeFailure = true
                assertContentEquals(expected, volume.raidBytes())
                assertTrue(missing in volume.unavailable())
                val patch = payload(2).reversedArray()
                volume.write(2, ByteBuffer.wrap(patch))
                patch.copyInto(expected, 32)
                assertContentEquals(expected, volume.raidBytes())
                val replacement = member(4)
                volume.replace(missing, replacement)
                assertEquals(emptySet(), volume.unavailable())
                assertContentEquals(expected, volume.raidBytes())
                assertTrue(replacement.raidBytes().any { it != 0.toByte() })
                members[(missing + 1) % 4].readFailure = true
                assertContentEquals(expected, volume.raidBytes(), "Rebuilt member must support another independent failure")
            } finally { volume.drain() }
        }
    }

    @Test
    fun raid6_recovers_every_pair_including_two_data_and_both_parity_members() = runTest {
        for (a in 0 until 5) for (b in a + 1 until 5) {
            val members = Array(5) { member(3) }
            val volume = BtrfsRaidVolume.create(this, BtrfsVolumeLayout.RAID6, members.toSeries())
            try {
                val expected = payload(9)
                volume.write(0, ByteBuffer.wrap(expected))
                members[a].readFailure = true
                members[b].readFailure = true
                members[a].writeFailure = true
                members[b].writeFailure = true
                assertContentEquals(expected, volume.raidBytes(), "Failed members $a and $b")
                assertEquals(setOf(a, b), volume.unavailable())
                val patch = payload(4).reversedArray()
                volume.write(1, ByteBuffer.wrap(patch))
                patch.copyInto(expected, 16)
                assertContentEquals(expected, volume.raidBytes())
                volume.replace(a, member(3))
                volume.replace(b, member(3))
                assertEquals(emptySet(), volume.unavailable())
                assertContentEquals(expected, volume.raidBytes())
            } finally { volume.drain() }
        }
    }

    @Test
    fun partial_member_write_does_not_return_torn_logical_data() = runTest {
        val members = Array(4) { member(4) }
        val volume = BtrfsRaidVolume.create(this, BtrfsVolumeLayout.RAID5, members.toSeries())
        try {
            val expected = payload(12)
            volume.write(0, ByteBuffer.wrap(expected))
            members[1].partialWriteFailure = true
            val patch = ByteArray(40) { (200 + it).toByte() }
            volume.write(1, ByteBuffer.wrap(patch))
            patch.copyInto(expected, 16)
            assertTrue(1 in volume.unavailable())
            assertContentEquals(expected, volume.raidBytes())
            volume.replace(1, member(4))
            assertContentEquals(expected, volume.raidBytes())
        } finally { volume.drain() }
    }

    @Test
    fun replica_recovery_respects_raid10_pair_boundaries() = runTest {
        val members = Array(4) { member(4) }
        val volume = BtrfsRaidVolume.create(this, BtrfsVolumeLayout.RAID10, members.toSeries())
        try {
            val expected = payload(8)
            volume.write(0, ByteBuffer.wrap(expected))
            members[0].readFailure = true
            members[2].readFailure = true
            assertContentEquals(expected, volume.raidBytes())
            assertEquals(setOf(0, 2), volume.unavailable())
            val patch = payload(3).reversedArray()
            volume.write(1, ByteBuffer.wrap(patch))
            patch.copyInto(expected, 16)
            assertContentEquals(expected, volume.raidBytes())
            members[1].readFailure = true
            assertFails { volume.read(0, 1) }
        } finally { runCatching { volume.drain() } }
    }

    @Test
    fun short_member_reads_and_failed_rebuilds_retain_the_surviving_copy() = runTest {
        val members = Array(2) { member(4) }
        val volume = BtrfsRaidVolume.create(this, BtrfsVolumeLayout.RAID1, members.toSeries())
        try {
            val expected = payload(4)
            volume.write(0, ByteBuffer.wrap(expected))
            members[0].shortRead = true
            assertContentEquals(expected, volume.raidBytes())
            assertEquals(setOf(0), volume.unavailable())
            val replacement = member(4)
            replacement.partialWriteFailure = true
            assertFails { volume.replace(0, replacement) }
            assertEquals(setOf(0), volume.unavailable())
            assertContentEquals(expected, volume.raidBytes())
            volume.replace(0, member(4))
            assertEquals(emptySet(), volume.unavailable())
            assertContentEquals(expected, volume.raidBytes())
        } finally { volume.drain() }
    }

    @Test
    fun excess_failures_reject_writes_and_reads_without_claiming_recovery() = runTest {
        val members = Array(4) { member(4) }
        val volume = BtrfsRaidVolume.create(this, BtrfsVolumeLayout.RAID5, members.toSeries())
        try {
            volume.write(0, ByteBuffer.wrap(payload(12)))
            members[0].readFailure = true
            members[1].readFailure = true
            assertFails { volume.read(0, 3) }
            assertFails { volume.write(0, ByteBuffer.wrap(payload(1))) }
        } finally { runCatching { volume.drain() } }
    }

    @Test
    fun concurrent_disjoint_writes_and_partial_updates_preserve_parity() = runTest {
        val members = Array(4) { member(16) }
        val volume = BtrfsRaidVolume.create(this, BtrfsVolumeLayout.RAID5, members.toSeries())
        try {
            val expected = ByteArray(48 * 16)
            (0 until 48).map { block ->
                async {
                    val data = ByteArray(16) { (block + 1).toByte() }
                    data.copyInto(expected, block * 16)
                    volume.write(block.toLong(), ByteBuffer.wrap(data))
                }
            }.awaitAll()
            members[0].readFailure = true
            assertContentEquals(expected, volume.raidBytes())
        } finally { volume.drain() }
    }

    @Test
    fun cancelled_caller_and_cancelled_drain_join_admitted_writes() = runTest {
        val members = Array(2) { member(4) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        members[0].entered = entered
        members[0].release = release
        val volume = BtrfsRaidVolume.create(this, BtrfsVolumeLayout.RAID1, members.toSeries())
        try {
            val expected = payload(4)
            val writing = async { volume.write(0, ByteBuffer.wrap(expected)) }
            entered.await()
            writing.cancel()
            val draining = async { volume.drain() }
            runCurrent()
            draining.cancel()
            runCurrent()
            assertFalse(draining.isCompleted, "Drain must account for the admitted write")
            release.complete(Unit)
            writing.join()
            draining.join()
            volume.drain()
            assertContentEquals(expected, members[0].raidBytes())
            assertContentEquals(expected, members[1].raidBytes())
            assertFails { volume.read(0, 1) }
        } finally {
            release.complete(Unit)
            volume.drain()
        }
    }

    @Test
    fun owner_cancellation_joins_the_entire_admitted_write_before_members_stop() = runTest {
        val members = Array(2) { member(4) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        members[0].entered = entered
        members[0].release = release
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner)
        val volume = BtrfsRaidVolume.create(scope, BtrfsVolumeLayout.RAID1, members.toSeries())
        try {
            val expected = payload(4)
            val writing = async { runCatching { volume.write(0, ByteBuffer.wrap(expected)) } }
            entered.await()
            owner.cancel()
            runCurrent()
            assertFalse(owner.isCompleted, "Parent cancellation must still account for admitted work")
            release.complete(Unit)
            writing.await().getOrThrow()
            owner.join()
            volume.drain()
            assertContentEquals(expected, members[0].raidBytes())
            assertContentEquals(expected, members[1].raidBytes())
        } finally {
            release.complete(Unit)
            volume.drain()
            owner.cancel()
            owner.join()
        }
    }
}
