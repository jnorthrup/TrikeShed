package borg.trikeshed.btrfs

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.Volume
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

/** TrikeShed array metadata, not a Btrfs device or chunk tree. */
internal data class BtrfsRaidRecord(
    val generation: Long,
    val epoch: Long,
    val baseEpoch: Long,
    val arrayId: String,
    val config: BtrfsRaidImageConfig,
    val unavailable: Set<Int>,
    val writes: Series<BtrfsRaidWrite>,
) {
    fun sameContent(other: BtrfsRaidRecord): Boolean =
        arrayId == other.arrayId && epoch == other.epoch && baseEpoch == other.baseEpoch &&
            config.sameMembers(other.config) && unavailable == other.unavailable && writes.size == other.writes.size &&
            (0 until writes.size).all { index ->
                val a = writes[index]
                val b = other.writes[index]
                a.member == b.member && a.lba == b.lba && a.bytes.contentEquals(b.bytes)
            }
}

/**
 * Two checksummed slots per member retain the previous valid record while a new
 * record is written. A prepared transaction contains complete block after-images
 * and reaches FSYNC before data writes begin. Commit retains those after-images:
 * they also repair an interrupted metadata commit when reopening the array.
 *
 * The owning RAID actor serializes calls. No journal work escapes its request.
 */
internal class BtrfsRaidMetadata(
    private val rawMembers: Array<Volume?>,
    initial: BtrfsRaidRecord,
) : BtrfsRaidPersistence {
    var record: BtrfsRaidRecord = initial
        private set

    override suspend fun prepare(writes: Series<BtrfsRaidWrite>, unavailable: Set<Int>): Set<Int> {
        require(writes.size in 1..maxOf(2, rawMembers.size)) { "Transaction must contain one complete RAID row" }
        val captured = Array(writes.size) { index ->
            writes[index].let { write ->
                require(write.member in rawMembers.indices)
                require(write.lba >= 0 && write.lba < record.config.members[write.member].capacityBlocks)
                require(write.bytes.size == record.config.blockSize)
                write.copy(bytes = write.bytes.copyOf())
            }
        }
        val transaction = captured.size j { captured[it] }
        validateWrites(record.config, transaction)
        check(record.epoch < Long.MAX_VALUE) { "RAID transaction sequence exhausted" }
        record = record.copy(
            baseEpoch = record.epoch,
            epoch = record.epoch + 1,
            writes = transaction,
            unavailable = record.unavailable + unavailable,
        )
        return persist(record.unavailable)
    }

    override suspend fun commit(unavailable: Set<Int>): Set<Int> = persist(unavailable)

    suspend fun replacement(index: Int, member: BtrfsRaidImageMember, raw: Volume) {
        val previous = rawMembers[index]
        val previouslyAvailable = index !in record.unavailable
        val members = Array(record.config.members.size) { i ->
            if (i == index) member else record.config.members[i]
        }
        record = record.copy(config = record.config.copy(members = members.size j { members[it] }))
        persist(record.unavailable + index)
        // An accessible retired member also records the new identity. In
        // particular, SINGLE must not reopen its old image as a stale fork.
        if (previouslyAvailable && previous != null) {
            try { writeRecord(index, record) } catch (failure: Throwable) {
                if (failure is CancellationException || record.config.layout == BtrfsVolumeLayout.SINGLE) throw failure
            }
        }
        rawMembers[index] = raw
        // The replacement starts explicitly unavailable until rebuild completes.
        writeRecord(index, record)
    }

    /** Replay only members whose recorded epoch can be advanced by this intent. */
    suspend fun recover(unavailable: Set<Int>): Set<Int> {
        var failed = unavailable
        val config = record.config
        for (i in 0 until record.writes.size) {
            val write = record.writes[i]
            if (write.member in failed) continue
            try {
                val buffer = ByteBuffer.wrap(write.bytes)
                requireNotNull(rawMembers[write.member]).write(
                    reservedBlocks(config) + write.lba,
                    buffer,
                )
                check(!buffer.hasRemaining()) { "Incomplete RAID redo write" }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                failed = failed + write.member
            }
        }
        for (index in rawMembers.indices) if (index !in failed) {
            try {
                requireNotNull(rawMembers[index]).sync()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                failed = failed + index
            }
        }
        return persist(failed)
    }

    private suspend fun persist(unavailable: Set<Int>): Set<Int> {
        require(unavailable.all { it in rawMembers.indices }) { "Unknown unavailable member" }
        var failed = unavailable
        do {
            val before = failed
            check(record.generation < Long.MAX_VALUE) { "RAID metadata sequence exhausted" }
            record = record.copy(generation = record.generation + 1, unavailable = failed)
            for (index in rawMembers.indices) if (index !in failed) {
                try {
                    writeRecord(index, record)
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    failed = failed + index
                }
            }
        } while (failed != before)
        record = record.copy(unavailable = failed)
        return failed
    }

    private suspend fun writeRecord(index: Int, value: BtrfsRaidRecord) {
        val raw = requireNotNull(rawMembers[index])
        val blocks = slotBytes(value.config) / value.config.blockSize
        val buffer = ByteBuffer.wrap(encode(value, index))
        raw.write((value.generation % 2) * blocks, buffer)
        check(!buffer.hasRemaining()) { "Incomplete RAID metadata write" }
        raw.sync()
    }

    companion object {
        private const val MAGIC = 0x5453524149443031L // TSRAID01
        private const val VERSION = 1
        private const val DIGEST_BYTES = 71
        private const val PREFIX_BYTES = 32
        private const val PATH_BYTES = 4096

        fun slotBytes(config: BtrfsRaidImageConfig): Int {
            val quantum = maxOf(4096, config.blockSize)
            val bytes = 1024L + config.members.size.toLong() * (PATH_BYTES + 32L) +
                maxOf(2, config.members.size).toLong() * (config.blockSize + 16L)
            val aligned = (bytes + quantum - 1) / quantum * quantum
            require(aligned <= 64 * 1024 * 1024) { "RAID metadata exceeds bounded slot capacity" }
            return aligned.toInt()
        }

        fun reservedBlocks(config: BtrfsRaidImageConfig): Long =
            2L * slotBytes(config) / config.blockSize

        fun initial(config: BtrfsRaidImageConfig, unavailable: Set<Int>): BtrfsRaidRecord =
            BtrfsRaidRecord(
                generation = 0,
                epoch = 0,
                baseEpoch = 0,
                arrayId = ContentId.of(Random.Default.nextBytes(32)).value,
                config = config,
                unavailable = unavailable,
                writes = 0 j { error("Initial array has no transaction") },
            )

        suspend fun read(raw: Volume, config: BtrfsRaidImageConfig, index: Int): BtrfsRaidRecord? {
            val blocks = slotBytes(config) / config.blockSize
            var result: BtrfsRaidRecord? = null
            for (slot in 0..1) {
                val value = try {
                    val buffer = raw.read(slot.toLong() * blocks, blocks)
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    decode(bytes, index)
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    null
                }
                if (value != null) {
                    val previous = result
                    if (previous != null) {
                        require(previous.arrayId == value.arrayId && previous.config.sameGeometry(value.config)) {
                            "RAID metadata slots belong to incompatible arrays"
                        }
                        if (previous.generation == value.generation) require(previous.sameContent(value)) {
                            "Conflicting RAID metadata at the same generation"
                        }
                        val newer = if (previous.generation > value.generation) previous else value
                        val older = if (previous.generation > value.generation) value else previous
                        require(newer.epoch >= older.epoch) { "RAID metadata epoch regressed" }
                    }
                    if (previous == null || value.generation > previous.generation) result = value
                }
            }
            return result
        }

        /** The first page is sufficient to discover the allocation geometry. */
        fun prefix(bytes: ByteArray): Pair<Int, Int> {
            val source = BtrfsRaidDecoder(bytes)
            require(source.long() == MAGIC && source.int() == VERSION) { "Not a TrikeShed RAID image" }
            val slotBytes = source.int()
            val blockSize = source.int()
            require(blockSize > 0 && blockSize <= 1024 * 1024 && blockSize.countOneBits() == 1)
            require(slotBytes in 4096..64 * 1024 * 1024 && slotBytes % maxOf(4096, blockSize) == 0)
            return slotBytes to blockSize
        }

        fun decode(bytes: ByteArray, memberIndex: Int? = null): BtrfsRaidRecord {
            val (slotBytes, blockSize) = prefix(bytes)
            require(bytes.size == slotBytes) { "Incomplete RAID metadata slot" }
            val prefix = BtrfsRaidDecoder(bytes)
            prefix.long(); prefix.int(); prefix.int(); prefix.int()
            val self = prefix.int()
            val length = prefix.int()
            require(prefix.int() == 0)
            require(length in PREFIX_BYTES until bytes.size - DIGEST_BYTES)
            val expected = bytes.copyOfRange(length, length + DIGEST_BYTES).decodeToString()
            require(ContentId.of(bytes.copyOfRange(0, length)).value == expected) { "RAID metadata checksum mismatch" }
            val source = BtrfsRaidDecoder(bytes, PREFIX_BYTES, length)
            val generation = source.long()
            val epoch = source.long()
            val baseEpoch = source.long()
            require(generation >= 0 && epoch >= 0 && generation >= epoch)
            require(baseEpoch == if (epoch == 0L) 0L else epoch - 1) { "Invalid RAID transaction epoch" }
            val arrayId = ContentId(source.string(71)).value
            val layout = BtrfsVolumeLayout.valueOf(source.string(32))
            val stripeBlocks = source.int()
            val count = source.int()
            require(count in 1..4096 && self in 0 until count)
            if (memberIndex != null) require(self == memberIndex) { "RAID member order mismatch" }
            val members = Array(count) {
                BtrfsRaidImageMember(source.long().toULong(), source.string(PATH_BYTES), source.long())
            }
            val config = BtrfsRaidImageConfig(layout, blockSize, stripeBlocks, count j { members[it] })
            config.validate()
            require(slotBytes(config) == slotBytes)
            val failedCount = source.int()
            require(failedCount in 0..count)
            val failed = mutableSetOf<Int>()
            repeat(failedCount) {
                val index = source.int()
                require(index in 0 until count && failed.add(index))
            }
            val writeCount = source.int()
            require(writeCount in 0..maxOf(2, count))
            val writes = Array(writeCount) {
                val member = source.int()
                val lba = source.long()
                require(member in members.indices && lba in 0 until members[member].capacityBlocks)
                BtrfsRaidWrite(member, lba, source.bytes(blockSize))
            }
            require(source.position == length) { "Unexpected RAID metadata fields" }
            require((epoch == 0L) == (writeCount == 0)) { "RAID transaction is missing its redo blocks" }
            val transaction = writeCount j { writes[it] }
            if (writeCount > 0) validateWrites(config, transaction)
            return BtrfsRaidRecord(generation, epoch, baseEpoch, arrayId, config, failed, transaction)
        }

        private fun validateWrites(config: BtrfsRaidImageConfig, writes: Series<BtrfsRaidWrite>) {
            val geometry = config.validate()
            require(writes.size in 1..maxOf(2, geometry.memberCount))
            for (index in 0 until writes.size) {
                val write = writes[index]
                require(write.member in 0 until geometry.memberCount && write.bytes.size == config.blockSize)
                require(write.lba in 0 until config.members[write.member].capacityBlocks)
            }
            val first = writes[0]
            if (geometry.parityCount > 0) {
                require(writes.size == geometry.memberCount) { "Incomplete RAID parity row" }
                require(first.lba < geometry.capacity / geometry.dataCount) { "RAID redo addresses an unused tail" }
                require((0 until writes.size).all { writes[it].member == it && writes[it].lba == first.lba }) {
                    "RAID redo must contain each member of one stripe block"
                }
                val row = first.lba / geometry.stripeBlocks
                val data = Array(geometry.dataCount) { writes[geometry.dataMember(row, it)].bytes }
                val encoded = BtrfsRaidParity.encode(data)
                val parity = geometry.parityMembers(row)
                require(writes[parity[0]].bytes.contentEquals(encoded.a)) { "RAID redo P parity mismatch" }
                if (geometry.parityCount == 2) require(writes[parity[1]].bytes.contentEquals(encoded.b)) {
                    "RAID redo Q parity mismatch"
                }
            } else {
                val logical = when (config.layout) {
                    BtrfsVolumeLayout.SPAN -> {
                        var offset = first.lba
                        for (index in 0 until first.member) offset += config.members[index].capacityBlocks
                        offset
                    }
                    BtrfsVolumeLayout.RAID0, BtrfsVolumeLayout.RAID10 -> {
                        require(first.lba < geometry.capacity / geometry.dataCount) { "RAID redo addresses an unused tail" }
                        val lane = if (config.layout == BtrfsVolumeLayout.RAID10) first.member / 2 else first.member
                        (first.lba / geometry.stripeBlocks * geometry.dataCount + lane) * geometry.stripeBlocks +
                            first.lba % geometry.stripeBlocks
                    }
                    else -> first.lba
                }
                val locations = geometry.locations(logical)
                require(writes.size == locations.size) { "Incomplete RAID replica set" }
                require((0 until writes.size).all {
                    writes[it].member == locations[it].a && writes[it].lba == locations[it].b &&
                        writes[it].bytes.contentEquals(first.bytes)
                }) { "RAID redo replica addresses or bytes disagree" }
            }
        }

        internal fun encode(record: BtrfsRaidRecord, memberIndex: Int): ByteArray {
            val bytes = ByteArray(slotBytes(record.config))
            val target = BtrfsRaidEncoder(bytes)
            target.long(MAGIC)
            target.int(VERSION)
            target.int(bytes.size)
            target.int(record.config.blockSize)
            target.int(memberIndex)
            target.int(0) // payload length filled below, included in checksum
            target.int(0)
            target.long(record.generation)
            target.long(record.epoch)
            target.long(record.baseEpoch)
            target.string(record.arrayId)
            target.string(record.config.layout.name)
            target.int(record.config.stripeBlocks)
            target.int(record.config.members.size)
            for (index in 0 until record.config.members.size) {
                val member = record.config.members[index]
                target.long(member.deviceId.toLong())
                target.string(member.imagePath)
                target.long(member.capacityBlocks)
            }
            target.int(record.unavailable.size)
            for (index in 0 until record.config.members.size) if (index in record.unavailable) target.int(index)
            target.int(record.writes.size)
            for (index in 0 until record.writes.size) {
                val write = record.writes[index]
                target.int(write.member)
                target.long(write.lba)
                target.bytes(write.bytes)
            }
            val length = target.position
            require(length + DIGEST_BYTES < bytes.size)
            BtrfsRaidEncoder(bytes, 24).int(length)
            ContentId.of(bytes.copyOfRange(0, length)).value.encodeToByteArray().copyInto(bytes, length)
            return bytes
        }
    }
}

private class BtrfsRaidEncoder(private val target: ByteArray, var position: Int = 0) {
    fun int(value: Int) { repeat(4) { target[position++] = (value ushr (it * 8)).toByte() } }
    fun long(value: Long) { repeat(8) { target[position++] = (value ushr (it * 8)).toByte() } }
    fun string(value: String) { val bytes = value.encodeToByteArray(); int(bytes.size); bytes(bytes) }
    fun bytes(value: ByteArray) { value.copyInto(target, position); position += value.size }
}

private class BtrfsRaidDecoder(
    private val source: ByteArray,
    var position: Int = 0,
    private val limit: Int = source.size,
) {
    fun int(): Int { require(limit - position >= 4); return (0..3).fold(0) { n, i -> n or ((source[position++].toInt() and 255) shl (i * 8)) } }
    fun long(): Long { require(limit - position >= 8); return (0..7).fold(0L) { n, i -> n or ((source[position++].toLong() and 255) shl (i * 8)) } }
    fun bytes(length: Int): ByteArray { require(length >= 0 && length <= limit - position); return source.copyOfRange(position, position + length).also { position += length } }
    fun string(maximum: Int): String { val length = int(); require(length in 0..maximum); return bytes(length).decodeToString(throwOnInvalidSequence = true) }
}
