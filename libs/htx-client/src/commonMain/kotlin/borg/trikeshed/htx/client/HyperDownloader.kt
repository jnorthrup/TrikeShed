package borg.trikeshed.htx.client

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
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
class HyperDownloader(
    val element: HtxElement,
    private val switches: HyperDLSwitches = HyperDLSwitches(),
) : AsyncContextElement() {
    companion object Key : AsyncContextKey<HyperDownloader>()
    override val key: AsyncContextKey<HyperDownloader> get() = Key

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

        // HEAD → Content-Length
        val head = el.request("HEAD", url, transport = HtxTransport.HTTPS)
        val contentLength = head.headers["content-length"]?.toLongOrNull()
            ?: error("Server did not provide Content-Length")

        // aria2c rule: only split if file_size ≥ 2 × minSplitSize
        // aria2c rule: effective connections per host capped by maxConnectionPerServer
        val perHostSplit = switches.split.coerceAtMost(switches.maxConnectionPerServer)
        val effectiveSplit = if (perHostSplit <= 1 || contentLength < 2 * switches.minSplitSize) 1 else perHostSplit
        if (effectiveSplit <= 1) {
            val response = el.request("GET", url, transport = HtxTransport.HTTPS)
            check(response.status in 200..299) { "Download failed: HTTP ${response.status}" }
            return@withContext response.body.encodeToByteArray()
        }

        // aria2c rule: split points at multiples of pieceLength
        val pieceMask = switches.pieceLength - 1
        val chunkSize = ((contentLength / effectiveSplit) and pieceMask.inv()).coerceAtLeast(switches.pieceLength)
        val semaphore = Semaphore(maxConcurrent.coerceAtMost(effectiveSplit))

        // Segment state: null = not started, ByteArray = completed
        val segments = Array<Any?>(effectiveSplit) { null }

        // Scavenge loop: launch segments, retry failures
        while (true) {
            val pending = (0 until effectiveSplit).filter { segments[it] !is ByteArray }
            if (pending.isEmpty()) break

            val jobs = pending.map { index ->
                launch {
                    semaphore.withPermit {
                        var attempt = 0
                        while (attempt <= maxRetries) {
                            try {
                                val start = index * chunkSize
                                val end = if (index == effectiveSplit - 1) contentLength - 1
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
