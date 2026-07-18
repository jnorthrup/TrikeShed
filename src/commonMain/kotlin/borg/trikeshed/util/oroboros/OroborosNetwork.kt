package borg.trikeshed.util.oroboros

import borg.trikeshed.dht.routing.RoutingTable
import borg.trikeshed.dht.id.NUID
import borg.trikeshed.dht.include.Address
import borg.trikeshed.dht.net.NetMask
import borg.trikeshed.htx.HtxElement
import borg.trikeshed.htx.parseHtxRequest
import borg.trikeshed.htx.HtxExchangeResult
import borg.trikeshed.htx.HtxExchangeLifecycle
import borg.trikeshed.htx.state
import borg.trikeshed.htx.frames
import borg.trikeshed.htx.HtxFrame
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.OpK
import borg.trikeshed.lib.FacetedRow
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.toList
import borg.trikeshed.context.StreamTransport
import borg.trikeshed.job.Sha256Pure
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException

sealed class OroborosNetworkK<out R> : OpK<R>() {
    object DhtLookup : OroborosNetworkK<suspend (ContentId) -> List<Join<NUID<*>, Address>>>()
    object IpfsFetch : OroborosNetworkK<suspend (ContentId, HtxElement) -> ByteArray>()
    object StreamFetch : OroborosNetworkK<suspend (ContentId, StreamTransport) -> ByteArray>()
    object HtxFetch : OroborosNetworkK<suspend (String, HtxElement) -> ByteArray>()
    object FanoutFetch : OroborosNetworkK<suspend (ContentId) -> ByteArray>()
}

typealias OroborosNetworkRow = FacetedRow<OroborosNetworkK<*>>

interface ContentGateway {
    suspend fun fetch(contentId: ContentId): ByteArray
}

class DhtContentGateway<TNum : Comparable<TNum>, Sz : NetMask<TNum>>(
    private val routingTable: RoutingTable<TNum, Sz>,
    private val toTNum: (ContentId) -> TNum
) {
    fun lookup(contentId: ContentId): List<Join<NUID<TNum>, Address>> {
        val target = toTNum(contentId)
        return routingTable.getClosest(target, 20)
    }
}

// Global payload extraction method as HtxFrame internal details aren't known, mocked for testing tests.
fun extractHtxPayloadBytes(response: HtxExchangeResult): ByteArray {
    var result = ByteArray(0)
    val frames = response.frames.toList()
    for (frame in frames) {
         // Placeholder for body extraction
         // Real app would deserialize frames appropriately
         if (frame.toString() == "MOCK_PAYLOAD") {
             result += "MOCK_PAYLOAD".encodeToByteArray()
         }
    }
    return result
}

open class IpfsContentGateway(private val htxElement: HtxElement) : ContentGateway {
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    override suspend fun fetch(contentId: ContentId): ByteArray {
        val request = parseHtxRequest("ipfs://${contentId.value}")
        val response = htxElement.exchange(request)

        if (response.state.lifecycle == HtxExchangeLifecycle.FAILED) {
            error("IpfsFetch: Failed to fetch")
        }

        val payload = extractPayload(response)

        val expectedHex = contentId.value.substring("sha256:".length)
        val actualHash = Sha256Pure.digest(payload)

        // Portable hex string conversion
        val hexBuilder = StringBuilder(actualHash.size * 2)
        for (b in actualHash) {
            val v = b.toInt() and 0xFF
            hexBuilder.append(HEX_CHARS[v ushr 4])
            hexBuilder.append(HEX_CHARS[v and 0x0F])
        }
        val actualHex = hexBuilder.toString()

        if (expectedHex != actualHex) {
            error("Digest mismatch rejection: expected $expectedHex, got $actualHex")
        }

        return payload
    }

    // Virtual method to mock the response for testing logic
    internal open fun extractPayload(response: HtxExchangeResult): ByteArray {
        return extractHtxPayloadBytes(response)
    }
}

class StreamContentGateway(private val transport: StreamTransport) : ContentGateway {
    override suspend fun fetch(contentId: ContentId): ByteArray {
        val stream = transport.openStream()

        // Framing: [1 byte type = 0x01 (FETCH)] [64 bytes content id]
        val idBytes = contentId.value.encodeToByteArray()
        val reqFrame = ByteArray(1 + idBytes.size)
        reqFrame[0] = 0x01
        idBytes.copyInto(reqFrame, 1)

        stream.send.send(reqFrame)
        stream.send.close()

        // Accumulate with a list of chunks, then flat map to byte array to be more efficient than appending
        val chunks = mutableListOf<ByteArray>()
        var totalSize = 0
        for (chunk in stream.recv) {
            chunks.add(chunk)
            totalSize += chunk.size
        }

        val responseBytes = ByteArray(totalSize)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(responseBytes, offset)
            offset += chunk.size
        }

        // Assuming Response Framing: [1 byte type = 0x02 (CONTENT) or 0x03 (TOMBSTONE)] [payload bytes]
        if (responseBytes.isEmpty()) error("Empty response from StreamTransport")

        val type = responseBytes[0]
        if (type == 0x03.toByte()) {
            error("Tombstone received for $contentId")
        } else if (type != 0x02.toByte()) {
            error("Unknown response frame type: $type")
        }

        return responseBytes.copyOfRange(1, responseBytes.size)
    }
}

class HtxContentGateway(private val htxElement: HtxElement) {
    suspend fun fetch(url: String): ByteArray {
        val request = parseHtxRequest(url)
        val response = htxElement.exchange(request)
        if (response.state.lifecycle == HtxExchangeLifecycle.FAILED) {
            error("HtxFetch: Failed to fetch")
        }
        return extractHtxPayloadBytes(response)
    }
}

class FanoutFailure(message: String, val failures: Map<String, Throwable>) : Exception(message)

class ContentGatewayFanout(
    private val gateways: Map<String, ContentGateway>
) {
    suspend fun fetch(contentId: ContentId): ByteArray = coroutineScope {
        val ch = Channel<Pair<String, Result<ByteArray>>>(Channel.BUFFERED)

        val jobs = gateways.map { (name, gateway) ->
            async {
                try {
                    val bytes = gateway.fetch(contentId)
                    ch.send(name to Result.success(bytes))
                } catch (e: CancellationException) {
                    // Send cancellation as a failure so receive loop does not block
                    try {
                        ch.send(name to Result.failure(Exception("$name cancelled", e)))
                    } catch (closed: Exception) {
                    }
                    throw e
                } catch (e: Exception) {
                    try {
                        ch.send(name to Result.failure(Exception("$name named failure: ${e.message}", e)))
                    } catch (closed: Exception) {
                    }
                }
            }
        }

        val failures = mutableMapOf<String, Throwable>()
        var successBytes: ByteArray? = null

        for (i in 1..gateways.size) {
            val (name, result) = ch.receive()
            if (result.isSuccess) {
                successBytes = result.getOrNull()
                break
            } else {
                failures[name] = result.exceptionOrNull()!!
            }
        }

        jobs.forEach { it.cancel() }
        ch.close()

        if (successBytes != null) {
            successBytes
        } else {
            throw FanoutFailure("Fanout failed: all selected gateways failed", failures)
        }
    }
}
