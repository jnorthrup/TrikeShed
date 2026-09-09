package borg.trikeshed.btrfs

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.Volume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Complete physical block after-image, including members currently unavailable. */
data class BtrfsRaidWrite(val member: Int, val lba: Long, val bytes: ByteArray)

/** The image owner persists intent before effects and member state before acknowledgment. */
interface BtrfsRaidPersistence {
    suspend fun prepare(writes: Series<BtrfsRaidWrite>, unavailable: Set<Int>): Set<Int>
    suspend fun commit(unavailable: Set<Int>): Set<Int>
}

class BtrfsRaidException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Serialized logical requests fan out to bounded member IO channels and fan in
 * before completion. Every worker belongs to the caller's supervisor tree.
 * Members are borrowed; their owner drains this composition before closing them.
 * Persistent image owners supply a replicated redo journal; bare Volume callers
 * retain responsibility for persisting configuration and unavailable members.
 */
class BtrfsRaidVolume private constructor(
    parent: CoroutineScope,
    val geometry: BtrfsRaidGeometry,
    suppliedMembers: Series<Volume>,
    unavailable: Set<Int>,
    private val persistence: BtrfsRaidPersistence?,
    queueCapacity: Int,
) : Volume, CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<BtrfsRaidVolume> {
        fun create(
            scope: CoroutineScope,
            layout: BtrfsVolumeLayout,
            members: Series<Volume>,
            stripeBlocks: Int = 1,
            unavailable: Set<Int> = emptySet(),
            persistence: BtrfsRaidPersistence? = null,
            queueCapacity: Int = 16,
        ): BtrfsRaidVolume {
            requireNotNull(scope.coroutineContext[Job]) { "RAID requires an owning Job" }.ensureActive()
            require(members.size > 0 && queueCapacity > 0)
            val values = Array(members.size) { members[it] }
            val members = values.size j { index: Int -> values[index] }
            val blockSize = members[0].blockSize
            require(blockSize > 0 && (0 until members.size).all { members[it].blockSize == blockSize })
            require((0 until members.size).all { i -> (0 until i).none { members[it] === members[i] } }) {
                "Each member must be a distinct volume; use DUP for one member"
            }
            val geometry = BtrfsRaidGeometry(layout, members.size j { members[it].capacity }, stripeBlocks)
            require(geometry.tolerates(unavailable)) { "Unavailable members exceed $layout redundancy" }
            return BtrfsRaidVolume(scope, geometry, members, unavailable, persistence, queueCapacity)
        }
    }

    override val key: CoroutineContext.Key<*> get() = Key
    override val blockSize = suppliedMembers[0].blockSize
    override val capacity: Long get() = geometry.capacity
    private val members = Array(suppliedMembers.size) { suppliedMembers[it] }
    private val failed = unavailable.toMutableSet()
    private var poison: Throwable? = null
    private val supervisor = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + this + supervisor)

    private abstract class Request(val caller: Job?) {
        val settled = CompletableDeferred<Unit>()
        abstract suspend fun execute()
        abstract fun fail(failure: Throwable)
    }

    private class Operation<T>(caller: Job?, val body: suspend () -> T) : Request(caller) {
        val result = CompletableDeferred<T>()
        override suspend fun execute() {
            try { result.complete(body()) } catch (failure: Throwable) { fail(failure) }
            finally { settled.complete(Unit) }
        }
        override fun fail(failure: Throwable) { result.completeExceptionally(failure); settled.complete(Unit) }
    }

    private class MemberIo(
        val operation: UringOp,
        val lba: Long = 0,
        val bytes: ByteArray? = null,
        val target: Volume? = null,
    ) {
        val reply = Channel<Result<ByteArray>>(1)
        fun fail(failure: Throwable) { reply.trySend(Result.failure(failure)); reply.close() }
    }

    private data class Dispatch(val member: Int, val io: MemberIo)
    private val input = Channel<Request>(queueCapacity, onUndeliveredElement = {
        it.fail(CancellationException("RAID request was not admitted"))
    })
    private val memberInputs = Array(members.size) {
        Channel<MemberIo>(1, onUndeliveredElement = { it.fail(CancellationException("Member IO stopped")) })
    }
    private val workers = Array(members.size) { index ->
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // The coordinator closes these queues after the admitted logical
            // request settles; parent cancellation cannot stop a member mid-request.
            withContext(NonCancellable) {
                try {
                    for (request in memberInputs[index]) {
                        val result = runCatching {
                            val volume = request.target ?: members[index]
                            when (request.operation) {
                                UringOp.READ -> {
                                    val buffer = volume.read(request.lba, 1)
                                    check(buffer.remaining() == blockSize) { "Member $index returned a short or oversized block" }
                                    ByteArray(blockSize).also { buffer.get(it) }
                                }
                                UringOp.WRITE -> {
                                    val buffer = ByteBuffer.wrap(requireNotNull(request.bytes))
                                    volume.write(request.lba, buffer)
                                    check(!buffer.hasRemaining()) { "Member $index did not consume the complete write" }
                                    ByteArray(0)
                                }
                                UringOp.FSYNC -> { volume.sync(); ByteArray(0) }
                                else -> error("Invalid member operation")
                            }
                        }
                        request.reply.send(result)
                        request.reply.close()
                    }
                } finally {
                    memberInputs[index].close()
                    while (true) {
                        val request = memberInputs[index].tryReceive().getOrNull() ?: break
                        request.fail(CancellationException("Member worker closed"))
                    }
                }
            }
        }
    }
    private val terminated = CompletableDeferred<Unit>()
    private val consumer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            for (request in input) {
                if (request.caller?.isActive == false) request.fail(CancellationException("RAID caller cancelled before execution"))
                else withContext(NonCancellable) { request.execute() }
            }
        } finally {
            input.close()
            while (true) {
                val request = input.tryReceive().getOrNull() ?: break
                request.fail(CancellationException("RAID admission closed"))
            }
            withContext(NonCancellable) {
                memberInputs.forEach { it.close() }
                workers.forEach { it.join() }
                terminated.complete(Unit)
                supervisor.complete()
            }
        }
    }

    private suspend fun <T> admit(body: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val request = Operation(currentCoroutineContext()[Job], body)
        try {
            input.send(request)
            return request.result.await()
        } finally {
            withContext(NonCancellable) { request.settled.await() }
        }
    }

    private fun operational() {
        requireRecovered()
        recoverable()
    }

    private fun requireRecovered() {
        poison?.let { throw BtrfsRaidException("RAID requires recovery after an incomplete mutation", it) }
    }

    private fun recoverable() {
        if (!geometry.tolerates(failed)) throw BtrfsRaidException("Unavailable members exceed ${geometry.layout} redundancy: $failed")
    }

    private suspend fun dispatch(operations: List<Dispatch>, trackFailures: Boolean = true, allowUnavailable: Boolean = false): List<Result<ByteArray>> {
        for ((member, io) in operations) {
            if (!allowUnavailable && member in failed) io.fail(BtrfsRaidException("Member $member is unavailable"))
            else try { memberInputs[member].send(io) } catch (failure: Throwable) { io.fail(failure) }
        }
        val results = ArrayList<Result<ByteArray>>(operations.size)
        for ((member, io) in operations) {
            val result = io.reply.receive()
            if (trackFailures && result.isFailure) failed.add(member)
            results.add(result)
        }
        return results
    }

    private suspend fun persistState() {
        persistence?.let { failed.addAll(it.commit(failed.toSet())) }
        recoverable()
    }

    private fun checkRange(lba: Long, blocks: Long) {
        require(lba >= 0 && blocks >= 0 && lba <= capacity && blocks <= capacity - lba) { "RAID access outside volume" }
    }

    override suspend fun read(lba: Long, count: Int): ByteBuffer {
        require(count >= 0 && count.toLong() * blockSize <= Int.MAX_VALUE)
        checkRange(lba, count.toLong())
        return admit {
            operational()
            val result = ByteArray(count * blockSize)
            for (offset in 0 until count) readBlock(lba + offset).copyInto(result, offset * blockSize)
            persistState()
            ByteBuffer.wrap(result)
        }
    }

    private suspend fun readBlock(lba: Long): ByteArray {
        if (geometry.parityCount != 0) {
            val data = readStripe(geometry.row(lba), geometry.blockInStripe(lba))
            return data[((lba / geometry.stripeBlocks) % geometry.dataCount).toInt()]
        }
        val addresses = geometry.locations(lba)
        val results = dispatch((0 until addresses.size).map { i ->
            Dispatch(addresses[i].a, MemberIo(UringOp.READ, addresses[i].b))
        })
        recoverable()
        val copies = results.mapNotNull { it.getOrNull() }
        if (copies.isEmpty()) throw BtrfsRaidException("No readable copy of logical block $lba")
        if (copies.any { !it.contentEquals(copies[0]) }) throw BtrfsRaidException("Replica disagreement at logical block $lba")
        return copies[0]
    }

    private suspend fun readStripe(row: Long, block: Int): Array<ByteArray> {
        val physical = geometry.memberLba(row, block)
        val results = dispatch((members.indices).map { Dispatch(it, MemberIo(UringOp.READ, physical)) })
        recoverable()
        val lanes = Array<ByteArray?>(geometry.dataCount) { results[geometry.dataMember(row, it)].getOrNull() }
        val parity = geometry.parityMembers(row)
        val p = results[parity[0]].getOrNull()
        val q = if (geometry.parityCount == 2) results[parity[1]].getOrNull() else null
        val data = BtrfsRaidParity.recover(lanes, p, q, blockSize)
        val encoded = BtrfsRaidParity.encode(data)
        if ((p != null && !p.contentEquals(encoded.a)) || (q != null && !q.contentEquals(encoded.b))) {
            throw BtrfsRaidException("Parity disagreement in stripe $row block $block")
        }
        return data
    }

    override suspend fun write(lba: Long, data: ByteBuffer) {
        val length = data.remaining()
        val blocks = (length.toLong() + blockSize - 1) / blockSize
        checkRange(lba, blocks)
        val start = data.position()
        admit {
            operational()
            var consumed = 0
            while (consumed < length) {
                val logical = lba + consumed / blockSize
                val count = minOf(blockSize, length - consumed)
                val writes: Array<BtrfsRaidWrite>
                if (geometry.parityCount != 0) {
                    val row = geometry.row(logical)
                    val block = geometry.blockInStripe(logical)
                    val lanes = readStripe(row, block)
                    val lane = ((logical / geometry.stripeBlocks) % geometry.dataCount).toInt()
                    data.array().copyInto(lanes[lane], 0, data.arrayOffset() + start + consumed,
                        data.arrayOffset() + start + consumed + count)
                    writes = stripeWrites(row, block, lanes)
                } else {
                    val bytes = if (count == blockSize) ByteArray(blockSize) else readBlock(logical)
                    data.array().copyInto(bytes, 0, data.arrayOffset() + start + consumed,
                        data.arrayOffset() + start + consumed + count)
                    val locations = geometry.locations(logical)
                    writes = Array(locations.size) { BtrfsRaidWrite(locations[it].a, locations[it].b, bytes) }
                }
                apply(writes.toSeries())
                consumed += count
                data.position(start + consumed)
            }
        }
    }

    private fun stripeWrites(row: Long, block: Int, data: Array<ByteArray>): Array<BtrfsRaidWrite> {
        val encoded = BtrfsRaidParity.encode(data)
        val bytes = arrayOfNulls<ByteArray>(members.size)
        for (i in data.indices) bytes[geometry.dataMember(row, i)] = data[i]
        val parity = geometry.parityMembers(row)
        bytes[parity[0]] = encoded.a
        if (geometry.parityCount == 2) bytes[parity[1]] = encoded.b
        val physical = geometry.memberLba(row, block)
        return Array(members.size) { BtrfsRaidWrite(it, physical, requireNotNull(bytes[it])) }
    }

    private suspend fun apply(writes: Series<BtrfsRaidWrite>) {
        try {
            persistence?.let { failed.addAll(it.prepare(writes, failed.toSet())) }
            recoverable()
            dispatch((0 until writes.size).map { i ->
                val write = writes[i]
                Dispatch(write.member, MemberIo(UringOp.WRITE, write.lba, write.bytes))
            })
            recoverable()
            syncMembers()
            persistState()
        } catch (failure: Throwable) {
            poison = failure
            throw failure
        }
    }

    private suspend fun syncMembers() {
        dispatch(members.indices.filter { it !in failed }.map { Dispatch(it, MemberIo(UringOp.FSYNC)) })
        recoverable()
    }

    override suspend fun sync(): Unit = admit { operational(); syncMembers(); persistState() }

    suspend fun unavailable(): Set<Int> = admit { failed.toSet() }

    /** Declare a lost member; it cannot rejoin until replacement/rebuild completes. */
    suspend fun offline(member: Int): Unit = admit {
        require(member in members.indices)
        failed.add(member)
        persistState()
    }

    /** Reconstruct into a borrowed replacement and only then publish it as available. */
    suspend fun replace(index: Int, replacement: Volume): Unit = admit {
        requireRecovered()
        require(index in members.indices)
        require(replacement.blockSize == blockSize && replacement.capacity >= members[index].capacity)
        require(members.none { it === replacement }) { "Replacement must be a distinct volume" }
        val old = members[index]
        val erased = index in failed
        failed.add(index)
        if (erased) recoverable()
        // Publishing the unavailable marker makes an interrupted rebuild resumable.
        persistence?.let { failed.addAll(it.commit(failed.toSet())) }
        try {
            var block = 0L
            while (block < old.capacity) {
                val bytes = if (!erased) {
                    val result = dispatch(listOf(Dispatch(index, MemberIo(UringOp.READ, block, target = old))),
                        trackFailures = false, allowUnavailable = true)[0]
                    result.getOrElse { reconstructMember(index, block) }
                } else reconstructMember(index, block)
                dispatch(listOf(Dispatch(index, MemberIo(UringOp.WRITE, block, bytes, replacement))),
                    trackFailures = false, allowUnavailable = true)[0].getOrThrow()
                block++
            }
            dispatch(listOf(Dispatch(index, MemberIo(UringOp.FSYNC, target = replacement))),
                trackFailures = false, allowUnavailable = true)[0].getOrThrow()
            members[index] = replacement
            failed.remove(index)
            persistState()
        } catch (failure: Throwable) {
            failed.add(index)
            runCatching { persistence?.commit(failed.toSet()) }.onFailure { failure.addSuppressed(it) }
            throw failure
        }
    }

    private suspend fun reconstructMember(member: Int, physical: Long): ByteArray {
        recoverable()
        return when (geometry.layout) {
            BtrfsVolumeLayout.RAID1 -> if (physical < capacity) readBlock(physical) else ByteArray(blockSize)
            BtrfsVolumeLayout.RAID10 -> {
                if (physical >= capacity / geometry.dataCount) ByteArray(blockSize) else {
                    val row = physical / geometry.stripeBlocks
                    val offset = (physical % geometry.stripeBlocks).toInt()
                    val logical = (row * geometry.dataCount + member / 2) * geometry.stripeBlocks + offset
                    readBlock(logical)
                }
            }
            BtrfsVolumeLayout.RAID5, BtrfsVolumeLayout.RAID6 -> {
                val used = capacity / geometry.dataCount
                if (physical >= used) ByteArray(blockSize) else {
                    val row = physical / geometry.stripeBlocks
                    val offset = (physical % geometry.stripeBlocks).toInt()
                    stripeWrites(row, offset, readStripe(row, offset))[member].bytes
                }
            }
            else -> throw BtrfsRaidException("${geometry.layout} cannot reconstruct an erased member")
        }
    }

    suspend fun drain() = withContext(NonCancellable) {
        input.close()
        consumer.join()
        supervisor.join()
        terminated.await()
    }

    suspend fun close() = drain()
}
