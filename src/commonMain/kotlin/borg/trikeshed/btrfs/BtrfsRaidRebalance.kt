package borg.trikeshed.btrfs

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.Volume
import borg.trikeshed.userspace.nio.file.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * A mounted logical volume whose file-backed RAID generations can be relocated.
 * Requests use one bounded channel under the owning supervisor. Relocation pauses
 * admitted I/O, copies into exclusively created members, syncs them, and publishes
 * a checksummed manifest by durable atomic replacement before switching the handle.
 * Retired and interrupted generations remain on disk; reclaim them only after the
 * owner has established that no recovery root references them.
 *
 * Member headers are TrikeShed RAID metadata. The logical bytes can contain a
 * canonical Btrfs image, but this operation is not Btrfs chunk-tree balancing.
 */
class BtrfsRaidRebalance private constructor(
    private val files: FileOperations,
    val manifestPath: String,
    private val lease: FileLease,
    private val scope: CoroutineScope,
    private val owner: CompletableJob,
    private val entries: Int,
    initial: State,
) : Volume, CoroutineContext.Element {
    private class State(val image: BtrfsRaidImage, val generation: Long, val compression: BtrfsMetadataCompression,
                        val logical: Volume = image, val metadata: BtrfsMetadataDescriptor? = null)
    private class Manifest(val config: BtrfsRaidImageConfig, val generation: Long,
                           val compression: BtrfsMetadataCompression, val metadata: BtrfsMetadataDescriptor?)
    @Volatile private var state = initial
    private var poison: Throwable? = null
    override val key: CoroutineContext.Key<*> get() = Key
    override val blockSize: Int = initial.image.blockSize
    override val capacity: Long get() = state.image.capacity
    val metadataCompression: BtrfsMetadataCompression get() = state.compression

    private abstract class Request(val caller: Job?) {
        val settled = CompletableDeferred<Unit>()
        abstract suspend fun execute()
        abstract fun fail(failure: Throwable)
    }

    private class Operation<T>(caller: Job?, val body: suspend () -> T) : Request(caller) {
        val result = CompletableDeferred<T>()
        override suspend fun execute() {
            try { result.complete(body()) } catch (failure: Throwable) { result.completeExceptionally(failure) }
            finally { settled.complete(Unit) }
        }
        override fun fail(failure: Throwable) { result.completeExceptionally(failure); settled.complete(Unit) }
    }

    private val input = Channel<Request>(entries, onUndeliveredElement = {
        it.fail(CancellationException("Volume generation request was not admitted"))
    })
    private val terminated = CompletableDeferred<Unit>()
    private val consumer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            for (request in input) {
                if (request.caller?.isActive == false) request.fail(CancellationException("Volume caller cancelled before execution"))
                else withContext(NonCancellable) { request.execute() }
            }
        } finally {
            input.close()
            while (true) {
                val request = input.tryReceive().getOrNull() ?: break
                request.fail(CancellationException("Volume generation admission closed"))
            }
            withContext(NonCancellable) {
                var failure: Throwable? = null
                try { state.image.drain() } catch (caught: Throwable) { failure = caught }
                try { withContext(fileIoContext) { lease.close() } } catch (caught: Throwable) {
                    if (failure == null) failure = caught else failure.addSuppressed(caught)
                }
                if (failure == null) terminated.complete(Unit) else terminated.completeExceptionally(failure)
                owner.complete()
            }
        }
    }

    private suspend fun <T> admit(body: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val operation = Operation(currentCoroutineContext()[Job]) {
            poison?.let { throw BtrfsRaidException("Generation publication was interrupted; reopen the manifest", it) }
            body()
        }
        try {
            input.send(operation)
            return operation.result.await()
        } catch (closed: ClosedSendChannelException) {
            // Closed sends never transfer settlement to the consumer. Cancelled
            // sends remain owned by the consumer or the undelivered callback.
            operation.fail(closed)
            throw closed
        } finally {
            withContext(NonCancellable) { operation.settled.await() }
        }
    }

    override suspend fun read(lba: Long, count: Int): ByteBuffer = admit { state.logical.read(lba, count) }
    override suspend fun write(lba: Long, data: ByteBuffer): Unit = admit { state.logical.write(lba, data) }
    override suspend fun sync(): Unit = admit { state.logical.sync() }
    suspend fun config(): BtrfsRaidImageConfig = admit { state.image.config.snapshot() }
    suspend fun generation(): Long = admit { state.generation }

    /** The destination must use fresh files and cannot shrink the logical address space. */
    suspend fun migrate(
        destinationConfig: BtrfsRaidImageConfig,
        copy: Boolean = true,
        beforePublish: suspend (Volume) -> Unit = {},
    ): Long {
        val destination = destinationConfig.snapshot()
        return admit { relocate(destination, copy, beforePublish) }
    }

    /**
     * Publish a filesystem transaction into fresh files with the current geometry.
     * copy=false is for writers that initialize an entire new logical image.
     */
    suspend fun transact(copy: Boolean = true, body: suspend (Volume) -> Unit): Long = admit {
        relocate(freshConfig(), copy, body)
    }

    private fun freshConfig(): BtrfsRaidImageConfig {
        val next = nextGeneration(state.generation)
        val nonce = ContentId.of(Random.Default.nextBytes(32)).value.substringAfter(':').take(24)
        val config = state.image.config
        val members = Array(config.members.size) { index ->
            config.members[index].copy(imagePath = "$manifestPath.g$next.$nonce.d$index.img")
        }
        return config.copy(members = members.toSeries())
    }

    suspend fun setMetadataCompression(mode: BtrfsMetadataCompression): Unit = admit {
        if (state.compression != mode) {
            if (state.metadata == null) {
                val next = State(state.image, nextGeneration(state.generation), mode)
                publish(next)
                state = next
            } else relocate(freshConfig(), copy = true, beforePublish = {}, compression = mode)
        }
    }

    private suspend fun relocate(
        proposed: BtrfsRaidImageConfig,
        copy: Boolean,
        beforePublish: suspend (Volume) -> Unit,
        compression: BtrfsMetadataCompression = state.compression,
    ): Long {
        val previous = state
        val next = nextGeneration(previous.generation)
        val destination = withContext(fileIoContext) { canonicalConfig(files, manifestPath, proposed) }
        val geometry = destination.validate()
        require(destination.blockSize == blockSize) { "Relocation cannot change the logical block size" }
        require(geometry.capacity >= previous.image.capacity) { "Relocation cannot shrink the logical volume" }
        val oldPaths = (0 until previous.image.config.members.size).map { previous.image.config.members[it].imagePath }.toSet()
        withContext(fileIoContext) {
            for (index in 0 until destination.members.size) {
                val path = destination.members[index].imagePath
                require(path !in oldPaths && !files.exists(path)) { "Relocation members must be fresh files: $path" }
            }
        }
        previous.image.sync()
        val staged = BtrfsRaidImage.open(scope, destination, create = true, resize = true, entries = entries)
        val logical = BtrfsMetadataVolume.stage(staged)
        var adopted = false
        try {
            if (copy) {
                val ranges: Series<Join<Long, Int>> = (previous.logical as? BtrfsMetadataVolume)?.ranges
                    ?: (0 j { _: Int -> error("No metadata ranges") })
                for (index in 0 until ranges.size) {
                    val range = ranges[index]
                    logical.writeMetadata(range.a, previous.logical.read(range.a, range.b))
                }
                val batch = maxOf(1, COPY_BYTES / blockSize)
                var lba = 0L
                while (lba < previous.image.capacity) {
                    val count = minOf(batch.toLong(), previous.image.capacity - lba).toInt()
                    val bytes = previous.logical.read(lba, count)
                    check(bytes.remaining() == count * blockSize) { "Short relocation source read" }
                    val payload = ByteArray(bytes.remaining()).also { bytes.get(it) }
                    // Fresh exclusive members are zero-filled. Do not expand a
                    // sparse filesystem into thousands of journalled zero writes.
                    for (block in 0 until count) {
                        val start = block * blockSize
                        val metadataBlock = (0 until ranges.size).any { index ->
                            val range = ranges[index]
                            lba + block >= range.a && lba + block - range.a < range.b
                        }
                        if (!metadataBlock && (start until start + blockSize).any { payload[it] != 0.toByte() }) {
                            val data = ByteBuffer.wrap(payload.copyOfRange(start, start + blockSize))
                            logical.write(lba + block, data)
                            check(!data.hasRemaining()) { "Short relocation destination write" }
                        }
                    }
                    lba += count
                }
            }
            beforePublish(logical)
            val replacement = seal(files, staged, logical, next, compression)
            publish(replacement)
            state = replacement
            adopted = true
            previous.image.drain()
            return next
        } catch (failure: Throwable) {
            if (!adopted) {
                try { staged.drain() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            } else {
                // Do not reuse a pre-transaction filesystem view after a committed cleanup failure.
                poison = failure
            }
            throw failure
        }
    }

    private suspend fun publish(next: State) {
        val bytes = manifest(next.image.config, next.generation, next.compression, next.metadata)
        try {
            withContext(fileIoContext) { files.writeAtomically(manifestPath, bytes) }
        } catch (failure: Throwable) {
            // An adapter can fail after rename (e.g. directory fsync). We cannot
            // continue using the old image while disk may name the new image.
            poison = failure
            throw failure
        }
    }

    suspend fun drain(): Unit = withContext(NonCancellable) {
        input.close()
        try { terminated.await() } finally { consumer.join(); owner.complete(); owner.join() }
    }

    companion object Key : CoroutineContext.Key<BtrfsRaidRebalance> {
        private const val COPY_BYTES = 1024 * 1024
        private const val MANIFEST_BYTES = 32 * 1024 * 1024
        private const val FORMAT = "trikeshed-raid-generation"

        suspend fun create(
            scope: CoroutineScope,
            files: FileOperations,
            manifestPath: String,
            config: BtrfsRaidImageConfig,
            metadataCompression: BtrfsMetadataCompression = BtrfsMetadataCompression.RAW,
            entries: Int = 8,
            initialize: suspend (Volume) -> Unit = {},
        ): BtrfsRaidRebalance = acquire(scope, files, manifestPath, entries, create = true) { owned, path ->
            val canonical = withContext(fileIoContext) { canonicalConfig(files, path, config.snapshot()) }
            val image = BtrfsRaidImage.open(owned, canonical, create = true, resize = true, entries = entries)
            try {
                val logical = BtrfsMetadataVolume.stage(image)
                initialize(logical)
                val initial = seal(files, image, logical, 1L, metadataCompression)
                currentCoroutineContext().ensureActive()
                withContext(fileIoContext + NonCancellable) {
                    files.writeAtomically(path, manifest(canonical, 1L, metadataCompression, initial.metadata))
                }
                initial
            } catch (failure: Throwable) {
                withContext(NonCancellable) { try { image.drain() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) } }
                throw failure
            }
        }

        suspend fun open(
            scope: CoroutineScope,
            files: FileOperations,
            manifestPath: String,
            entries: Int = 8,
        ): BtrfsRaidRebalance = acquire(scope, files, manifestPath, entries, create = false) { owned, path ->
            val record = withContext(fileIoContext) {
                val descriptor = files.open(path, readOnly = true)
                try {
                    require(files.size(descriptor) in 1L..MANIFEST_BYTES.toLong() + 128) {
                        "Generation manifest exceeds its bound"
                    }
                } finally { check(files.close(descriptor) == 0) { "Failed to close generation manifest" } }
                decodeManifest(files.readAllBytes(path))
            }
            val canonical = withContext(fileIoContext) { canonicalConfig(files, path, record.config) }
            require(canonical.sameMembers(record.config)) { "Stored member paths are no longer canonical" }
            val image = BtrfsRaidImage.open(owned, canonical, entries = entries)
            try {
                val logical = record.metadata?.let { descriptor ->
                    withContext(fileIoContext) {
                        require(files.canonicalPath(descriptor.path) == descriptor.path) { "Metadata sidecar path is no longer canonical" }
                        BtrfsMetadataVolume.open(image, files, descriptor)
                    }
                } ?: image
                State(image, record.generation, record.compression, logical, record.metadata)
            } catch (failure: Throwable) {
                withContext(NonCancellable) { try { image.drain() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) } }
                throw failure
            }
        }

        private suspend fun acquire(
            parent: CoroutineScope,
            files: FileOperations,
            manifestPath: String,
            entries: Int,
            create: Boolean,
            prepare: suspend (CoroutineScope, String) -> State,
        ): BtrfsRaidRebalance {
            requireNotNull(parent.coroutineContext[Job]) { "A mounted volume requires an owning Job" }.ensureActive()
            currentCoroutineContext().ensureActive()
            require(entries > 0)
            require(files.durability == StorageDurability.DURABLE) { "Generation manifests require durable atomic publication" }
            val path = withContext(fileIoContext) { files.canonicalPath(manifestPath) }
            val lease = withContext(NonCancellable) { withContext(fileIoContext) { files.acquireExclusiveLease("$path.lock") } }
            val owner = SupervisorJob(parent.coroutineContext[Job])
            val owned = CoroutineScope(parent.coroutineContext + files + owner)
            var prepared: State? = null
            try {
                currentCoroutineContext().ensureActive()
                owner.ensureActive()
                withContext(fileIoContext) {
                    require(files.exists(path) != create) { if (create) "Generation manifest already exists" else "Generation manifest is absent" }
                }
                val initial = prepare(owned, path)
                prepared = initial
                currentCoroutineContext().ensureActive()
                return BtrfsRaidRebalance(files, path, lease, owned, owner, entries, initial)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    try { prepared?.image?.drain() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    try { withContext(fileIoContext) { lease.close() } } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    owner.complete(); owner.join()
                }
                throw failure
            }
        }

        private fun nextGeneration(generation: Long): Long {
            check(generation < Long.MAX_VALUE) { "Volume generation sequence exhausted" }
            return generation + 1
        }

        private fun canonicalConfig(files: FileOperations, manifest: String, config: BtrfsRaidImageConfig): BtrfsRaidImageConfig {
            config.validate()
            val members = Array(config.members.size) { index ->
                config.members[index].let { member ->
                    val path = files.canonicalPath(member.imagePath)
                    require(path != manifest && path != "$manifest.lock") { "A member cannot overwrite its generation manifest or lease" }
                    member.copy(imagePath = path)
                }
            }
            return config.copy(members = members.toSeries()).also { it.validate() }
        }

        private suspend fun seal(files: FileOperations, image: BtrfsRaidImage, logical: BtrfsMetadataVolume,
                                 generation: Long, compression: BtrfsMetadataCompression): State {
            val metadata = if (logical.ranges.size == 0) null else {
                val path = withContext(fileIoContext) {
                    files.canonicalPath(image.config.members[0].imagePath + ".metadata").also {
                        require(!files.exists(it)) { "Generation metadata sidecar must be a fresh file" }
                    }
                }
                logical.finish(files, path, compression)
            }
            image.sync()
            return State(image, generation, compression, if (metadata == null) image else logical, metadata)
        }

        private fun manifest(config: BtrfsRaidImageConfig, generation: Long, mode: BtrfsMetadataCompression,
                             metadata: BtrfsMetadataDescriptor?): ByteArray {
            val bytes = CanonicalCbor.encodeMap(mapOf(
                "format" to FORMAT, "version" to 1L, "generation" to generation, "compression" to mode.name,
                "layout" to config.layout.name, "blockSize" to config.blockSize.toLong(), "stripeBlocks" to config.stripeBlocks.toLong(),
                "members" to List(config.members.size) { index ->
                    config.members[index].let { mapOf("deviceId" to it.deviceId.toString(), "path" to it.imagePath, "capacity" to it.capacityBlocks) }
                },
                "metadata" to metadata?.let { mapOf("path" to it.path, "contentId" to it.contentId.value,
                    "storedBytes" to it.storedBytes.toLong(), "decodedBytes" to it.decodedBytes.toLong(), "extentCount" to it.extentCount.toLong()) },
            ))
            return mode.encode(bytes, MANIFEST_BYTES)
        }

        private fun decodeManifest(bytes: ByteArray): Manifest {
            require(bytes.size <= MANIFEST_BYTES + 128) { "Generation manifest exceeds its bound" }
            require(BtrfsMetadataCompression.isEncoded(bytes)) { "Generation manifest requires a checksummed envelope" }
            val plain = BtrfsMetadataCompression.decode(bytes, MANIFEST_BYTES)
            val record = CanonicalCbor.decodeMap(plain)
            require(CanonicalCbor.encodeMap(record).contentEquals(plain)) { "Generation manifest is not canonical" }
            val keys = setOf("format", "version", "generation", "compression", "layout", "blockSize", "stripeBlocks", "members")
            require(record.keys == keys || record.keys == keys + "metadata")
            require(record["format"] == FORMAT && integer(record["version"]) == 1L) { "Unsupported volume generation manifest" }
            val generation = integer(record["generation"]).also { require(it > 0) }
            val blockSize = integer(record["blockSize"]).also { require(it in 1..1024 * 1024) }.toInt()
            val stripeBlocks = integer(record["stripeBlocks"]).also { require(it in 1..Int.MAX_VALUE) }.toInt()
            val members = record["members"] as? List<*> ?: error("Generation members must be an array")
            require(members.size in 1..4096)
            val values = Array(members.size) { index ->
                val member = members[index] as? Map<*, *> ?: error("Invalid generation member")
                require(member.keys == setOf("deviceId", "path", "capacity"))
                val id = (member["deviceId"] as? String)?.toULongOrNull() ?: error("Invalid member identity")
                BtrfsRaidImageMember(id, member["path"] as? String ?: error("Invalid member path"), integer(member["capacity"]))
            }
            val config = BtrfsRaidImageConfig(BtrfsVolumeLayout.valueOf(record["layout"] as? String ?: error("Missing layout")),
                blockSize, stripeBlocks, values.toSeries()).also { it.validate() }
            val metadata = record["metadata"]?.let { value ->
                val fields = value as? Map<*, *> ?: error("Invalid metadata descriptor")
                require(fields.keys == setOf("path", "contentId", "storedBytes", "decodedBytes", "extentCount"))
                fun count(name: String): Int = integer(fields[name]).also {
                    require(it in 0L..BtrfsMetadataCompression.MAX_BYTES.toLong() + 128)
                }.toInt()
                BtrfsMetadataDescriptor(fields["path"] as? String ?: error("Missing metadata path"),
                    ContentId(fields["contentId"] as? String ?: error("Missing metadata digest")),
                    count("storedBytes"), count("decodedBytes"), count("extentCount"))
            }
            return Manifest(config, generation,
                BtrfsMetadataCompression.valueOf(record["compression"] as? String ?: error("Missing compression")), metadata)
        }

        private fun integer(value: Any?): Long = when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Short -> value.toLong()
            is Byte -> value.toLong()
            else -> error("Generation integer field is invalid")
        }
    }
}
