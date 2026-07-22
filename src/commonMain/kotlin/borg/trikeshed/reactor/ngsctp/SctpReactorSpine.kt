/*
 * Copyright (c) 2024-2026. The TrikeShed Authors.
 * Licensed under the AGPLv3.
 */
package borg.trikeshed.reactor.ngsctp

import borg.trikeshed.reactor.SctpReactorEndpoint
import borg.trikeshed.reactor.PeerAddress
import borg.trikeshed.reactor.MeshActionResult
import borg.trikeshed.lcnc.reactor.ReactorAction
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
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

// 6. Reactor Spine
class SctpReactorSpine : SctpReactorEndpoint {
    private val scope = SctpAssociationScope()
    private val stream = BoundedChannelStream(capacity = 100)
    private val inboundChannel = Channel<Pair<PeerAddress, ReactorAction>>(Channel.BUFFERED)

    override suspend fun bind(port: Int): Int {
        return port
    }

    override suspend fun send(peer: PeerAddress, action: ReactorAction): MeshActionResult {
        // Dummy implementation for the spine interface
        return MeshActionResult.Ok(ByteArray(0))
    }

    override suspend fun receive(): Pair<PeerAddress, ReactorAction> {
        return inboundChannel.receive()
    }

    suspend fun receiveOrNull(): Pair<PeerAddress, ReactorAction>? {
        return inboundChannel.receiveCatching().getOrNull()
    }

    suspend fun triggerReceive(peer: PeerAddress, action: ReactorAction) {
        inboundChannel.send(Pair(peer, action))
    }

    override suspend fun close() {
        scope.close()
        inboundChannel.close()
    }
}
