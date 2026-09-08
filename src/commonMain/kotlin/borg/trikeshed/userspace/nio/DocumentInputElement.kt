package borg.trikeshed.userspace.nio

import borg.trikeshed.job.ContentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Exact byte extent over a userspace NIO volume; padding is not document content. */
data class DocumentExtent(
    val lba: Long,
    val byteLength: Int,
    val name: String,
    val mediaType: String? = null,
    val expectedCid: ContentId? = null,
)

data class DocumentBytes(val extent: DocumentExtent, val bytes: ByteArray, val cid: ContentId)

data class DocumentContent(val bytes: ByteArray, val name: String, val mediaType: String? = null)

/** The owner supplies the volume. This element never substitutes a host filesystem backend. */
class DocumentInputElement private constructor(
    parent: CoroutineScope,
    private val volume: Volume,
    capacity: Int = 8,
    private val blocksPerRead: Int = 16,
    private val maxDocumentBytes: Int = 64 * 1024 * 1024,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<DocumentInputElement> {
        fun create(
            scope: CoroutineScope,
            volume: Volume,
            capacity: Int = 8,
            blocksPerRead: Int = 16,
            maxDocumentBytes: Int = 64 * 1024 * 1024,
        ): DocumentInputElement {
            require(volume.blockSize > 0 && volume.capacity >= 0)
            require(capacity > 0 && blocksPerRead > 0 && maxDocumentBytes > 0)
            return DocumentInputElement(scope, volume, capacity, blocksPerRead, maxDocumentBytes)
        }
    }
    override val key: CoroutineContext.Key<*> get() = Key

    private data class Input(
        val extent: DocumentExtent,
        val bytes: ByteArray?,
        val result: CompletableDeferred<DocumentBytes>,
    )
    private val input = Channel<Input>(capacity.also { require(it > 0) })
    val job = SupervisorJob(parent.coroutineContext[Job])
    private val consumer: Job

    init {
        job.invokeOnCompletion { cause ->
            input.close(cause)
            while (true) {
                val request = input.tryReceive().getOrNull() ?: break
                request.result.completeExceptionally(cause ?: IllegalStateException("Document input stopped"))
            }
        }
        consumer = CoroutineScope(parent.coroutineContext + this + job).launch {
            val owner = currentCoroutineContext()[Key] ?: error("Missing document input context")
            try {
                for (request in input) {
                    try {
                        request.bytes?.let { bytes ->
                            owner.validate(request.extent)
                            owner.volume.write(request.extent.lba, ByteBuffer.wrap(bytes))
                            owner.volume.sync()
                        }
                        request.result.complete(owner.readExtent(request.extent))
                    } catch (cancelled: CancellationException) {
                        request.result.completeExceptionally(cancelled)
                        throw cancelled
                    } catch (failure: Throwable) {
                        request.result.completeExceptionally(failure)
                        if (failure !is Exception) throw failure
                    }
                }
            } finally {
                input.close()
                while (true) {
                    val request = input.tryReceive().getOrNull() ?: break
                    request.result.completeExceptionally(IllegalStateException("Document input stopped"))
                }
            }
        }
    }

    suspend fun read(extent: DocumentExtent): DocumentBytes {
        val result = CompletableDeferred<DocumentBytes>()
        input.send(Input(extent, null, result))
        return result.await()
    }

    /** The caller reserves this region; writes and read-back share the existing input worker. */
    suspend fun stage(lba: Long, bytes: ByteArray, name: String, mediaType: String? = null): DocumentBytes {
        validate(DocumentExtent(lba, bytes.size, name, mediaType))
        val retained = bytes.copyOf()
        val extent = DocumentExtent(lba, retained.size, name, mediaType, ContentId.of(retained))
        val result = CompletableDeferred<DocumentBytes>()
        input.send(Input(extent, retained, result))
        return result.await()
    }

    private fun validate(extent: DocumentExtent) {
        require(extent.byteLength in 0..maxDocumentBytes) { "Document length exceeds the configured bound" }
        require(extent.lba >= 0 && extent.lba <= volume.capacity)
        val blocks = (extent.byteLength.toLong() + volume.blockSize - 1) / volume.blockSize
        require(blocks <= volume.capacity - extent.lba) { "Document extent exceeds the volume" }
    }

    private suspend fun readExtent(extent: DocumentExtent): DocumentBytes {
        validate(extent)
        val blocks = (extent.byteLength.toLong() + volume.blockSize - 1) / volume.blockSize
        val bytes = ByteArray(extent.byteLength)
        var block = 0L
        var offset = 0
        while (block < blocks) {
            val count = minOf(blocksPerRead.toLong(), blocks - block).toInt()
            val region = volume.read(extent.lba + block, count)
            val required = minOf((bytes.size - offset).toLong(), count.toLong() * volume.blockSize).toInt()
            check(region.remaining() >= required) { "Short volume read at LBA ${extent.lba + block}" }
            region.get(bytes, offset, required)
            offset += required
            block += count
        }
        val cid = ContentId.of(bytes)
        require(extent.expectedCid == null || extent.expectedCid == cid) { "Document CID mismatch" }
        return DocumentBytes(extent, bytes, cid)
    }

    suspend fun drain() = withContext(NonCancellable) {
        input.close()
        consumer.join()
        job.complete()
        job.join()
    }

    suspend fun close() = drain()
}
