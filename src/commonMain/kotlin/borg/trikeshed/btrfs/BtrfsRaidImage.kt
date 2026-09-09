package borg.trikeshed.btrfs

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.Volume
import borg.trikeshed.userspace.nio.channels.UringChannel
import borg.trikeshed.userspace.nio.channels.UringChannels
import borg.trikeshed.userspace.nio.spi.currentNioCapabilityReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class BtrfsRaidImageMember(val deviceId: ULong, val imagePath: String, val capacityBlocks: Long)

data class BtrfsRaidImageConfig(
    val layout: BtrfsVolumeLayout,
    val blockSize: Int,
    val stripeBlocks: Int,
    val members: Series<BtrfsRaidImageMember>,
) {
    internal fun validate(): BtrfsRaidGeometry {
        require(members.size in 1..4096) { "Image member count must be in 1..4096" }
        require(blockSize > 0 && blockSize <= 1024 * 1024 && blockSize.countOneBits() == 1) {
            "Image block size must be a power of two up to 1 MiB"
        }
        val deviceIds = mutableSetOf<ULong>()
        val paths = mutableSetOf<String>()
        for (index in 0 until members.size) {
            val member = members[index]
            require(member.deviceId != 0uL && deviceIds.add(member.deviceId)) { "RAID member device IDs must be nonzero and unique" }
            require(member.imagePath.isNotBlank() && member.imagePath.encodeToByteArray().size <= 4096 &&
                '\u0000' !in member.imagePath && paths.add(member.imagePath)) { "RAID member paths must be distinct and valid" }
        }
        val reserved = BtrfsRaidMetadata.reservedBlocks(this)
        require((0 until members.size).all { members[it].capacityBlocks <= Long.MAX_VALUE / blockSize - reserved }) {
            "Member allocation exceeds the file offset range"
        }
        return BtrfsRaidGeometry(layout, members.size j { members[it].capacityBlocks }, stripeBlocks)
    }

    internal fun sameGeometry(other: BtrfsRaidImageConfig): Boolean =
        layout == other.layout && blockSize == other.blockSize && stripeBlocks == other.stripeBlocks &&
            members.size == other.members.size && (0 until members.size).all {
                members[it].capacityBlocks == other.members[it].capacityBlocks
            }

    internal fun sameMembers(other: BtrfsRaidImageConfig): Boolean =
        sameGeometry(other) && (0 until members.size).all { members[it] == other.members[it] }

    internal fun snapshot(): BtrfsRaidImageConfig {
        val values = Array(members.size) { members[it] }
        return copy(members = values.size j { values[it] })
    }
}

/**
 * Owned image members composed through their dedicated scoped uring channels.
 * Metadata and redo slots precede each member's data region. Capacity in the
 * public config counts data blocks only. This is a userspace RAID block volume;
 * its metadata is not the canonical Btrfs filesystem format.
 */
class BtrfsRaidImage private constructor(
    internal val volume: BtrfsRaidVolume,
    private val resources: Array<BtrfsRaidImageResource?>,
    private val metadata: BtrfsRaidMetadata,
    private val scope: CoroutineScope,
    private val owner: CompletableJob,
    private val entries: Int,
    private val channelFactory: (CoroutineScope, Int) -> UringChannel,
) : Volume {
    val config: BtrfsRaidImageConfig get() = metadata.record.config
    override val blockSize: Int get() = volume.blockSize
    override val capacity: Long get() = volume.capacity
    private val admission = Mutex()
    private var closed = false

    override suspend fun read(lba: Long, count: Int): ByteBuffer = admission.withLock {
        check(!closed) { "RAID image is closed" }
        volume.read(lba, count)
    }

    override suspend fun write(lba: Long, data: ByteBuffer): Unit = admission.withLock {
        check(!closed) { "RAID image is closed" }
        volume.write(lba, data)
    }

    override suspend fun sync(): Unit = admission.withLock {
        check(!closed) { "RAID image is closed" }
        volume.sync()
    }

    suspend fun unavailable(): Set<Int> = admission.withLock {
        check(!closed) { "RAID image is closed" }
        volume.unavailable()
    }

    suspend fun offline(index: Int): Unit = admission.withLock {
        check(!closed) { "RAID image is closed" }
        volume.offline(index)
    }

    /** Rebuild a member before publishing it as available; replace never resizes another member. */
    suspend fun replace(
        index: Int,
        member: BtrfsRaidImageMember,
        create: Boolean = true,
        resize: Boolean = true,
    ): Unit = admission.withLock {
        check(!closed) { "RAID image is closed" }
        require(index in resources.indices)
        require(member.capacityBlocks == config.members[index].capacityBlocks) { "Replacement capacity must preserve array geometry" }
        require(member.imagePath != config.members[index].imagePath) { "Replacement needs a distinct image path" }
        require(member.deviceId != config.members[index].deviceId) { "Replacement needs a distinct device identity" }
        val proposed = Array(config.members.size) { if (it == index) member else config.members[it] }
        config.copy(members = proposed.size j { proposed[it] }).validate()
        val resource = openMember(scope, config, member, entries, create, resize, channelFactory)
        val previous = resources[index]
        try {
            currentCoroutineContext().ensureActive()
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching { resource.drain() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
            }
            throw failure
        }
        // Once identity publication starts, retain the member and complete the
        // admitted rebuild/cleanup even if the requesting coroutine is cancelled.
        withContext(NonCancellable) {
            resources[index] = resource
            var mutationFailure: Throwable? = null
            try {
                metadata.replacement(index, member, resource.raw)
                volume.replace(index, BtrfsRaidImageData(resource.raw, BtrfsRaidMetadata.reservedBlocks(config), member.capacityBlocks))
            } catch (failure: Throwable) {
                mutationFailure = failure
                // The published replacement remains unavailable on disk. Stop
                // admission if publication/rebuild did not complete coherently.
                closed = true
                runCatching { volume.drain() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                for (owned in resources) owned?.let {
                    runCatching { it.drain() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                }
                throw failure
            } finally {
                try {
                    previous?.drain()
                } catch (cleanup: Throwable) {
                    if (mutationFailure != null) mutationFailure.addSuppressed(cleanup) else throw cleanup
                } finally {
                    if (closed) {
                        owner.complete()
                        owner.join()
                    }
                }
            }
        }
    }

    suspend fun drain(): Unit = withContext(NonCancellable) { admission.withLock {
        if (closed) return@withLock
        closed = true
        var failure: Throwable? = null
        suspend fun collect(action: suspend () -> Unit) {
            try { action() } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure!!.addSuppressed(caught)
            }
        }
        collect { volume.drain() }
        for (resource in resources) if (resource != null) collect { resource.drain() }
        owner.complete()
        owner.join()
        failure?.let { throw it }
    } }

    companion object {
        /**
         * Existing arrays are opened without creation or resizing by default.
         * Creation is exclusive and requires resize=true to allocate sparse,
         * zero-filled member data. Unavailable members are never silently rejoined.
         */
        suspend fun open(
            scope: CoroutineScope,
            config: BtrfsRaidImageConfig,
            create: Boolean = false,
            resize: Boolean = false,
            entries: Int = 8,
            unavailable: Set<Int> = emptySet(),
            channelFactory: (CoroutineScope, Int) -> UringChannel = { owner, depth -> UringChannels.open(owner, depth) },
        ): BtrfsRaidImage {
            val config = config.snapshot()
            requireNotNull(scope.coroutineContext[Job]) { "RAID images require an owning Job" }.ensureActive()
            currentCoroutineContext().ensureActive()
            val geometry = config.validate()
            require(entries > 0)
            require(!resize || create) { "Resizing an existing RAID image is not supported" }
            require(!create || resize) { "New RAID images require resize=true to allocate their data" }
            require(geometry.tolerates(unavailable)) { "Unavailable members exceed layout redundancy" }
            val owner = SupervisorJob(scope.coroutineContext[Job])
            val ownedScope = CoroutineScope(scope.coroutineContext + owner)
            val resources = arrayOfNulls<BtrfsRaidImageResource>(config.members.size)
            var volume: BtrfsRaidVolume? = null
            try {
                var failed = unavailable
                for (index in resources.indices) if (index !in failed) {
                    try {
                        resources[index] = openMember(ownedScope, config, config.members[index], entries, create, resize, channelFactory)
                    } catch (failure: Throwable) {
                        if (failure is CancellationException || create) throw failure
                        failed = failed + index
                    }
                }
                val rawMembers = Array<Volume?>(resources.size) { resources[it]?.raw }
                val metadata: BtrfsRaidMetadata
                if (create) {
                    metadata = BtrfsRaidMetadata(rawMembers, BtrfsRaidMetadata.initial(config, failed))
                    // Both slots become valid before the array is published.
                    failed = metadata.commit(failed)
                    failed = metadata.commit(failed)
                } else {
                    val records = Array<BtrfsRaidRecord?>(resources.size) { null }
                    for (index in resources.indices) {
                        records[index] = resources[index]?.let { BtrfsRaidMetadata.read(it.raw, config, index) }
                        if (records[index] == null) failed = failed + index
                    }
                    val latest = records.asSequence().filterNotNull().maxByOrNull { it.generation }
                        ?: error("No valid RAID metadata remains")
                    require(latest.config.sameMembers(config)) { "RAID image configuration or ordered member identity mismatch" }
                    for (record in records) if (record != null) {
                        require(record.arrayId == latest.arrayId && record.config.sameGeometry(latest.config)) {
                            "RAID members belong to incompatible arrays"
                        }
                        require(record.epoch <= latest.epoch) { "RAID metadata epoch regressed" }
                        if (record.generation == latest.generation) require(record.sameContent(latest)) {
                            "Conflicting RAID metadata at the same generation"
                        }
                    }
                    failed = failed + latest.unavailable
                    for (index in records.indices) {
                        val record = records[index] ?: continue
                        if (record.epoch < latest.baseEpoch || record.config.members[index] != latest.config.members[index]) {
                            failed = failed + index
                        }
                    }
                    require(geometry.tolerates(failed)) { "Unavailable or stale members exceed layout redundancy" }
                    metadata = BtrfsRaidMetadata(rawMembers, latest)
                    failed = metadata.recover(failed)
                }
                require(geometry.tolerates(failed)) { "Durable members do not satisfy layout redundancy" }
                val reserved = BtrfsRaidMetadata.reservedBlocks(config)
                val members: Series<Volume> = resources.size j { index ->
                    BtrfsRaidImageData(resources[index]?.raw, reserved, config.members[index].capacityBlocks, config.blockSize)
                }
                volume = BtrfsRaidVolume.create(ownedScope, config.layout, members, config.stripeBlocks, failed, metadata)
                currentCoroutineContext().ensureActive()
                return BtrfsRaidImage(volume, resources, metadata, ownedScope, owner, entries, channelFactory)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    volume?.let { runCatching { it.drain() }.exceptionOrNull()?.let { failure.addSuppressed(it) } }
                    for (resource in resources) resource?.let {
                        runCatching { it.drain() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                    }
                    owner.complete()
                    owner.join()
                }
                throw failure
            }
        }

        /** Discover stored geometry and paths from an existing member, then reopen the array. */
        suspend fun reopen(
            scope: CoroutineScope,
            imagePath: String,
            entries: Int = 8,
            unavailable: Set<Int> = emptySet(),
        ): BtrfsRaidImage {
            requireNotNull(scope.coroutineContext[Job]) { "RAID images require an owning Job" }.ensureActive()
            currentCoroutineContext().ensureActive()
            val channel = UringChannels.open(scope, entries)
            var bootstrap: BtrfsUringFileVolume? = null
            val config: BtrfsRaidImageConfig
            try {
                // This capacity is a read bound, not a resize. Slots are bounded
                // at 64 MiB and aligned to 4 KiB, so a damaged first prefix can
                // be bypassed by locating the second slot's own valid prefix.
                bootstrap = BtrfsUringFileVolume.open(channel, imagePath, 4096, 32768, false, false, currentNioCapabilityReport())
                var slotBytes: Int? = null
                var pageIndex = 0L
                while (pageIndex <= 16384 && slotBytes == null) {
                    val page = try { bootstrap.read(pageIndex, 1) } catch (failure: Throwable) {
                        if (failure is CancellationException) throw failure
                        break
                    }
                    val prefix = ByteArray(page.remaining()).also { page.get(it) }
                    val candidate = runCatching { BtrfsRaidMetadata.prefix(prefix).first }.getOrNull()
                    if (candidate != null && (pageIndex == 0L || candidate.toLong() == pageIndex * 4096)) {
                        val valid = try {
                            val buffer = bootstrap.read(pageIndex, candidate / 4096)
                            BtrfsRaidMetadata.decode(ByteArray(buffer.remaining()).also { buffer.get(it) })
                            true
                        } catch (failure: Throwable) {
                            if (failure is CancellationException) throw failure
                            false
                        }
                        if (valid) slotBytes = candidate
                    }
                    pageIndex++
                }
                val slotLength = requireNotNull(slotBytes) { "No valid RAID metadata prefix in discovery member" }
                var latest: BtrfsRaidRecord? = null
                for (slot in 0..1) {
                    val record = try {
                        val buffer = bootstrap.read(slot.toLong() * slotLength / 4096, slotLength / 4096)
                        BtrfsRaidMetadata.decode(ByteArray(buffer.remaining()).also { buffer.get(it) })
                    } catch (failure: Throwable) {
                        if (failure is CancellationException) throw failure
                        null
                    }
                    if (record != null && (latest == null || record.generation > latest.generation)) latest = record
                }
                config = requireNotNull(latest) { "No valid RAID metadata in discovery member" }.config
                require((0 until config.members.size).any { config.members[it].imagePath == imagePath }) { "Discovery path does not match stored member identity" }
            } finally {
                withContext(NonCancellable) {
                    try { bootstrap?.drain() } finally { channel.drain() }
                }
            }
            return open(scope, config, entries = entries, unavailable = unavailable)
        }

        private suspend fun openMember(
            scope: CoroutineScope,
            config: BtrfsRaidImageConfig,
            member: BtrfsRaidImageMember,
            entries: Int,
            create: Boolean,
            resize: Boolean,
            channelFactory: (CoroutineScope, Int) -> UringChannel,
        ): BtrfsRaidImageResource {
            require(!resize || create)
            require(!create || resize)
            val reserved = BtrfsRaidMetadata.reservedBlocks(config)
            require(member.capacityBlocks <= Long.MAX_VALUE - reserved)
            val channel = channelFactory(scope, entries)
            try {
                val raw = BtrfsUringFileVolume.open(
                    channel = channel,
                    imagePath = member.imagePath,
                    blockSize = config.blockSize,
                    capacity = member.capacityBlocks + reserved,
                    create = create,
                    resize = resize,
                    backendReport = currentNioCapabilityReport(),
                    channelReport = BtrfsUringChannelReport(channel.availability, channel.capabilities, channel.nativeCapabilities),
                    exclusive = create,
                )
                return BtrfsRaidImageResource(raw, channel)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    runCatching { channel.drain() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                }
                throw failure
            }
        }

    }
}

private class BtrfsRaidImageResource(val raw: BtrfsUringFileVolume, private val channel: UringChannel) {
    suspend fun drain() {
        try { raw.drain() } finally { channel.drain() }
    }
}

private class BtrfsRaidImageData(
    private val raw: Volume?,
    private val offset: Long,
    override val capacity: Long,
    override val blockSize: Int = requireNotNull(raw).blockSize,
) : Volume {
    override suspend fun read(lba: Long, count: Int): ByteBuffer {
        require(lba >= 0 && count >= 0 && lba <= capacity && count <= capacity - lba)
        return requireNotNull(raw) { "RAID image member is unavailable" }.read(offset + lba, count)
    }
    override suspend fun write(lba: Long, data: ByteBuffer) {
        require(lba >= 0 && lba <= capacity && data.remaining().toLong() <= (capacity - lba) * blockSize)
        requireNotNull(raw) { "RAID image member is unavailable" }.write(offset + lba, data)
    }
    override suspend fun sync() { requireNotNull(raw) { "RAID image member is unavailable" }.sync() }
}
