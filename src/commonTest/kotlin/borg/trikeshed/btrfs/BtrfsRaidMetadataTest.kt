package borg.trikeshed.btrfs

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.InMemoryVolume
import borg.trikeshed.userspace.nio.Volume
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BtrfsRaidMetadataTest {
    private fun config(layout: BtrfsVolumeLayout = BtrfsVolumeLayout.RAID1, count: Int = 2) =
        BtrfsRaidImageConfig(layout, 16, 2,
            Array(count) { BtrfsRaidImageMember((it + 1).toULong(), "/member-$it.img", 5) }.toSeries())

    private fun members(config: BtrfsRaidImageConfig): Array<BtrfsRaidFaultVolume> =
        Array(config.members.size) {
            BtrfsRaidFaultVolume(InMemoryVolume(config.blockSize, BtrfsRaidMetadata.reservedBlocks(config) + 5))
        }

    @Test
    fun short_metadata_writes_exclude_the_member_before_prepare_returns() = runTest {
        val config = config()
        val members = members(config)
        members[0].shortWrite = true
        val raw = Array<Volume?>(members.size) { members[it] }
        val metadata = BtrfsRaidMetadata(raw, BtrfsRaidMetadata.initial(config, emptySet()))
        val bytes = ByteArray(16) { (it + 1).toByte() }
        val writes = Array(2) { BtrfsRaidWrite(it, 0, bytes) }
        assertEquals(setOf(0), metadata.prepare(writes.toSeries(), emptySet()))
        val durable = requireNotNull(BtrfsRaidMetadata.read(members[1], config, 1))
        assertEquals(setOf(0), durable.unavailable)
        assertContentEquals(bytes, durable.writes[0].bytes)
    }

    @Test
    fun short_recovery_writes_cannot_publish_a_recovered_member() = runTest {
        val config = config()
        val members = members(config)
        val raw = Array<Volume?>(members.size) { members[it] }
        val metadata = BtrfsRaidMetadata(raw, BtrfsRaidMetadata.initial(config, emptySet()))
        val bytes = ByteArray(16) { (it + 7).toByte() }
        metadata.prepare(Array(2) { BtrfsRaidWrite(it, 0, bytes) }.toSeries(), emptySet())
        members[0].shortWrite = true
        assertEquals(setOf(0), metadata.recover(emptySet()))
        assertContentEquals(bytes, members[1].raidBytes(BtrfsRaidMetadata.reservedBlocks(config), 1))
        assertEquals(setOf(0), requireNotNull(BtrfsRaidMetadata.read(members[1], config, 1)).unavailable)
    }

    @Test
    fun checksummed_metadata_rejects_incomplete_cross_row_or_inconsistent_redo() {
        val config = config(BtrfsVolumeLayout.RAID6, 5)
        val data = Array(3) { lane -> ByteArray(16) { (lane * 41 + it).toByte() } }
        val parity = BtrfsRaidParity.encode(data)
        val blocks = arrayOf(data[0], data[1], data[2], parity.a, parity.b)
        val writes = Array(5) { BtrfsRaidWrite(it, 0, blocks[it]) }
        val valid = BtrfsRaidMetadata.initial(config, emptySet()).copy(
            generation = 3, epoch = 1, baseEpoch = 0, writes = writes.toSeries())
        assertEquals(1L, BtrfsRaidMetadata.decode(BtrfsRaidMetadata.encode(valid, 0)).epoch)
        val invalid = arrayOf(
            valid.copy(writes = writes.copyOfRange(0, 4).toSeries()),
            valid.copy(writes = Array(5) { if (it == 1) writes[it].copy(member = 0) else writes[it] }.toSeries()),
            valid.copy(writes = Array(5) { if (it == 1) writes[it].copy(lba = 1) else writes[it] }.toSeries()),
            valid.copy(writes = Array(5) { writes[it].copy(lba = 4) }.toSeries()),
            valid.copy(writes = Array(5) { if (it == 4) writes[it].copy(bytes = ByteArray(16)) else writes[it] }.toSeries()),
            valid.copy(writes = 0 j { error("No redo") }),
            valid.copy(baseEpoch = 1),
            valid.copy(epoch = 0),
            valid.copy(generation = 0),
        )
        for (record in invalid) {
            // Encoding recomputes the checksum: structural corruption must also be rejected.
            assertFailsWith<IllegalArgumentException> { BtrfsRaidMetadata.decode(BtrfsRaidMetadata.encode(record, 0)) }
        }
    }

    @Test
    fun incomplete_replica_sets_are_rejected_before_persisting_intent() = runTest {
        val config = config()
        val members = members(config)
        val metadata = BtrfsRaidMetadata(Array<Volume?>(2) { members[it] }, BtrfsRaidMetadata.initial(config, emptySet()))
        assertFailsWith<IllegalArgumentException> {
            metadata.prepare(arrayOf(BtrfsRaidWrite(0, 0, ByteArray(16))).toSeries(), emptySet())
        }
        assertEquals(0L, metadata.record.epoch)
        assertEquals(null, BtrfsRaidMetadata.read(members[0], config, 0))
    }

    @Test
    fun conflicting_slots_at_the_same_generation_are_not_selected_arbitrarily() = runTest {
        val config = config()
        val raw = members(config)[0]
        val first = BtrfsRaidMetadata.initial(config, emptySet()).copy(generation = 3)
        val second = first.copy(unavailable = setOf(1))
        val blocks = BtrfsRaidMetadata.slotBytes(config) / config.blockSize
        raw.write(0, ByteBuffer.wrap(BtrfsRaidMetadata.encode(first, 0)))
        raw.write(blocks.toLong(), ByteBuffer.wrap(BtrfsRaidMetadata.encode(second, 0)))
        assertFailsWith<IllegalArgumentException> { BtrfsRaidMetadata.read(raw, config, 0) }
    }

    @Test
    fun metadata_epoch_cannot_go_backwards_in_a_newer_slot() = runTest {
        val config = config()
        val raw = members(config)[0]
        val initial = BtrfsRaidMetadata.initial(config, emptySet())
        val writes = Array(2) { BtrfsRaidWrite(it, 0, ByteArray(16)) }.toSeries()
        val older = initial.copy(generation = 3, epoch = 1, writes = writes)
        val newer = initial.copy(generation = 4)
        val blocks = BtrfsRaidMetadata.slotBytes(config) / config.blockSize
        raw.write(0, ByteBuffer.wrap(BtrfsRaidMetadata.encode(newer, 0)))
        raw.write(blocks.toLong(), ByteBuffer.wrap(BtrfsRaidMetadata.encode(older, 0)))
        assertFailsWith<IllegalArgumentException> { BtrfsRaidMetadata.read(raw, config, 0) }
    }
}
