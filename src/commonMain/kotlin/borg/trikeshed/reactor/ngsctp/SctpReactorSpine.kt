/*
 * Copyright (c) 2024-2026. The TrikeShed Authors.
 * Licensed under the AGPLv3.
 */
package borg.trikeshed.reactor.ngsctp

import borg.trikeshed.reactor.SctpReactorEndpoint
import borg.trikeshed.reactor.PeerAddress
import borg.trikeshed.reactor.MeshActionFrame
import borg.trikeshed.reactor.MeshActionResult
import borg.trikeshed.lcnc.reactor.ReactorAction
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.view
import borg.trikeshed.lib.toList
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel

// 1. TLV Chunk Parser
class TlvChunkParser {
    class Chunk(val type: Int, val data: ByteArray)

    fun parse(data: ByteArray): Series<Chunk> {
        val resultList = mutableListOf<Chunk>()
        var offset = 0
        while (offset < data.size) {
            if (offset + 4 > data.size) break
            val type = data[offset].toInt() and 0xFF
            val length = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            if (offset + length > data.size) break

            val chunkData = data.copyOfRange(offset + 4, offset + length)

            if (type == 0x00) {
                resultList.add(Chunk(type, chunkData))
            } else {
                val action = type shr 6
                if (action == 0 || action == 1) {
                    break // Stop processing
                }
                // skip others implicitly
            }

            val padding = (4 - (length % 4)) % 4
            offset += length + padding
        }

        return resultList.size j { i -> resultList[i] }
    }
}

// 2. Bounded Channel Stream
class BoundedChannelStream(capacity: Int) {
    private val channel = Channel<ByteArray>(capacity)

    fun enqueue(data: ByteArray): Boolean {
        return channel.trySend(data).isSuccess
    }

    suspend fun dequeue(): ByteArray? {
        val res = channel.receiveCatching()
        return res.getOrNull()
    }
}

// 3. Association Scope
class SctpAssociationScope : CoroutineScope {
    private val job = SupervisorJob()
    override val coroutineContext = Dispatchers.Default + job

    fun close() {
        job.cancel()
    }
}

// 4. Partial Reliability
class PartialReliabilityBuffer(val capacity: Int) {
    private val buffer = mutableListOf<Pair<Int, ByteArray>>()

    fun enqueue(tsn: Int, data: ByteArray) {
        if (buffer.size >= capacity) {
            buffer.removeAt(0) // Drop oldest
        }
        buffer.add(tsn to data)
    }

    fun getAllUnacked(): Series<Pair<Int, ByteArray>> = buffer.size j { i -> buffer[i] }
}

// 5. Liburing Facade
interface LiburingFacade {
    fun submitBatch(batch: Series<ByteArray>)
    fun completeBatch(): Int
}

// 6. Subnet Job Buffer — capacity-triggered assembly
/**
 * Bounded buffer for jobs waiting to be dispatched to a specific subnet.
 * When [capacity] is reached, [onFull] is invoked to trigger dispatch.
 */
class SubnetJobBuffer(
    val subnet: String,
    val capacity: Int,
    val onFull: suspend (List<ByteArray>) -> Unit,
) {
    private val buffer = mutableListOf<ByteArray>()

    /**
     * Enqueue a job. Returns true if buffer is now full and [onFull] was triggered.
     */
    suspend fun enqueue(job: ByteArray): Boolean {
        buffer.add(job)
        if (buffer.size >= capacity) {
            val batch = buffer.toList()
            buffer.clear()
            onFull(batch)
            return true
        }
        return false
    }

    fun size(): Int = buffer.size
    fun isFull(): Boolean = buffer.size >= capacity
}

/**
 * Manages job buffers for multiple subnets in a concentric mesh topology.
 * Each concentric subnet buffers jobs until full, then triggers dispatch.
 */
class SubnetJobAssembly(
) {
    private val buffers: MutableMap<String, SubnetJobBuffer> = mutableMapOf()

    /**
     * Register a subnet with capacity trigger.
     */
    fun registerSubnet(subnet: String, capacity: Int) {
        buffers[subnet] = SubnetJobBuffer(subnet, capacity) { batch ->
            // Default: dispatch via fanout if available
            // Override per-subnet for custom behavior
        }
    }

    /**
     * Enqueue a job to a subnet's buffer. Returns true if dispatch was triggered.
     */
    suspend fun enqueue(subnet: String, job: ByteArray): Boolean {
        val buffer = buffers[subnet] ?: run {
            // Auto-register with default capacity
            val defaultCapacity = 10
            registerSubnet(subnet, defaultCapacity)
            buffers[subnet]!!
        }
        return buffer.enqueue(job)
    }

    /**
     * Get current buffer size for a subnet.
     */
    fun bufferSize(subnet: String): Int = buffers[subnet]?.size() ?: 0

    /**
     * Force trigger dispatch for a subnet even if not full.
     */
    suspend fun triggerDispatch(subnet: String) {
        buffers[subnet]?.let { buffer ->
            // Dispatch remaining jobs
            // Implementation would call fanout.dispatch() per job
        }
    }
}

// 6. Reactor Spine
class SctpReactorSpine(
    private val jobAssembly: SubnetJobAssembly = SubnetJobAssembly(),
) : SctpReactorEndpoint {
    private val scope = SctpAssociationScope()
    private val stream = BoundedChannelStream(capacity = 100)
    private val parser = TlvChunkParser()

    override suspend fun bind(port: Int): Int {
        return port
    }

    /**
     * Send a ReactorAction to a peer. The action is encoded as a mesh frame
     * and enqueued to the subnet's job buffer for capacity-triggered assembly.
     */
    override suspend fun send(peer: PeerAddress, action: ReactorAction): MeshActionResult {
        // Extract subnet from action's NUID for routing
        val subnet = extractSubnet(action)
        val frame = MeshActionFrame.encode(action)
        
        // Enqueue to subnet buffer — triggers dispatch when full
        jobAssembly.enqueue(subnet, frame.payload)
        
        return MeshActionResult.Ok(ByteArray(0))
    }

    /**
     * Receive incoming data from SCTP stream, parse TLV chunks,
     * route to appropriate subnet buffer for job assembly.
     */
    override suspend fun receive(): Pair<PeerAddress, ReactorAction> {
        val data = stream.dequeue() ?: throw IllegalStateException("No data available")
        
        // Parse TLV chunks
        val chunks = parser.parse(data)
        
        // Route each DATA chunk to subnet buffer
        for (chunk in chunks.view) {
            if (chunk.type == 0x00) { // DATA chunk
                val subnet = extractSubnetFromChunk(chunk.data)
                jobAssembly.enqueue(subnet, chunk.data)
            }
        }
        
        // Return placeholder — actual implementation would decode ReactorAction
        return PeerAddress("unknown", 0) to ReactorAction.Opened(
            nuid(
                Capability.Process("unknown"),
                Nonce.RandomBytes(),
                Subnet.core
            )
        )
    }

    override suspend fun close() {
        scope.close()
    }

    // ── helper extraction ───────────────────────────────────────────

    private fun extractSubnet(action: ReactorAction): String {
        return when (action) {
            is ReactorAction.Opened -> action.nuid.b.b.toString()
            is ReactorAction.Activated -> action.nuid.b.b.toString()
            is ReactorAction.PublishEntity -> action.nuid.b.b.toString()
            is ReactorAction.Draining -> action.nuid.b.b.toString()
            is ReactorAction.Closed -> action.nuid.b.b.toString()
        }
    }

    private fun extractSubnetFromChunk(data: ByteArray): String {
        // Extract subnet from chunk data — placeholder implementation
        // In production, decode the NUID from the chunk payload
        return "local"
    }
}
