package borg.trikeshed.htx.client

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Multi-segment parallel downloader — aria2c-compatible via HTX-TLS transport.
 *
 * Pattern A (CCEK + SupervisorJob + FSM):
 *   - companion object Key : AsyncContextKey<Aria2HtxDownloader>()
 *   - CREATED → OPEN → ACTIVE → DRAINING → CLOSED
 *   - Resolves HtxElement from coroutine context
 *   - Block chunk scavenging: failed segments retried under supervisor
 *
 * Usage:
 * ```
 * val element = HtxElement()
 * element.registerTransport(HtxTransport.HTTPS, createHttpsHandler())
 * val ctx = coroutineContext + element + Aria2HtxDownloader()
 * val downloader = ctx[Aria2HtxDownloader.Key]!!
 * downloader.open()
 * val bytes = downloader.download("https://.../file.zip", split = 4)
 * downloader.close()
 * ```
 */
class Aria2HtxDownloader(
    val element: HtxElement,
    private val switches: Aria2Switches = Aria2Switches(),
) : AsyncContextElement() {
    companion object Key : AsyncContextKey<Aria2HtxDownloader>()
    override val key: AsyncContextKey<Aria2HtxDownloader> get() = Key

    override suspend fun open() {
        if (state.isAtLeast(ElementState.OPEN)) return
        super.open()
        state = ElementState.ACTIVE
    }

    /**
     * Download [url] in [split] parallel segments via HTTP Range requests.
     * Failed segments are scavenged — retried up to [maxRetries] times.
     */
    suspend fun download(
        url: String,
        split: Int = switches.split,
        maxConcurrent: Int = switches.maxConcurrent,
        maxRetries: Int = 3,
    ): ByteArray = withContext(supervisor) {
        requireState(ElementState.ACTIVE)

        val el = element

        if (split <= 1) {
            val response = el.request("GET", url, transport = HtxTransport.HTTPS)
            check(response.status in 200..299) { "Download failed: HTTP ${response.status}" }
            return@withContext response.body.encodeToByteArray()
        }

        // HEAD → Content-Length
        val head = el.request("HEAD", url, transport = HtxTransport.HTTPS)
        val contentLength = head.headers["content-length"]?.toLongOrNull()
            ?: error("Server did not provide Content-Length")

        val chunkSize = contentLength / split
        val semaphore = Semaphore(maxConcurrent.coerceAtMost(split))

        // Segment state: null = not started, ByteArray = completed, Int = retries remaining
        val segments = Array<Any?>(split) { null }

        // Scavenge loop: launch segments, retry failures
        while (true) {
            val pending = (0 until split).filter { segments[it] !is ByteArray }
            if (pending.isEmpty()) break

            val jobs = pending.map { index ->
                launch {
                    semaphore.withPermit {
                        var attempt = 0
                        while (attempt <= maxRetries) {
                            try {
                                val start = index * chunkSize
                                val end = if (index == split - 1) contentLength - 1
                                    else start + chunkSize - 1
                                val response = el.request(
                                    method = "GET",
                                    path = url,
                                    headers = mapOf("Range" to "bytes=$start-$end"),
                                    transport = HtxTransport.HTTPS,
                                )
                                check(response.status in 200..299 || response.status == 206) {
                                    "Segment $index failed: HTTP ${response.status}"
                                }
                                segments[index] = response.body.encodeToByteArray()
                                return@withPermit
                            } catch (e: Exception) {
                                attempt++
                                if (attempt > maxRetries) throw e
                                delay(100L * attempt) // backoff
                            }
                        }
                    }
                }
            }
            jobs.joinAll()
        }

        // Reassemble in order
        val total = segments.sumOf { (it as ByteArray).size }
        val result = ByteArray(total)
        var offset = 0
        for (seg in segments) {
            val bytes = seg as ByteArray
            bytes.copyInto(result, offset)
            offset += bytes.size
        }
        result
    }
}
