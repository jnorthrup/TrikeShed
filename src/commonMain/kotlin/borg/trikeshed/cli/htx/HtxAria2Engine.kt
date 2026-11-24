package borg.trikeshed.cli.htx

import borg.trikeshed.htx.*
import borg.trikeshed.lib.toSeries
import borg.trikeshed.transfer.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * One file from its mirrors, as aria2 takes one: `split` byte ranges of at least `min-split-size`, each from a
 * mirror in turn, `max-connection-per-server` times the mirrors in flight at once, and the whole checked against
 * `checksum` (`sha-256=…` or `sha-1=…`) before it is written. Every URI names the same bytes; a mirror that
 * refuses, answers another length or times out is passed over for the next, and the file fails only when every
 * mirror failed a range. Redirects are the client reactor's to follow.
 *
 * [read] is the same file on demand: one byte range and no more, for a reader that enters by a footer and narrows
 * by an index. Nothing checks such a range against the file's checksum, which covers the whole file only.
 *
 * The whole file is gathered in memory and written once, so it is bounded by one array (below 2 GiB) and a stopped
 * download starts over: `continue` skips a file already present with the pinned checksum, it does not resume
 * a part. Pre-allocation, a control file and positional writes are not here.
 *
 * While it runs, [row] reports it as a transfer: a range counts as completed when its mirror answered with exactly
 * its bytes, and the checksum, which covers the whole file only, decides complete or error at the end.
 */
class HtxAria2Engine(
    private val options: Aria2Options,
    private val clientReactor: HtxClientReactorElement,
    private val fileOps: FileOperations
) {
    /** Exchanges made and body bytes received by this engine. */
    var requests = 0
    var received = 0L
    /** What asked for this file, where its content lands in the store, and the SUMO class of that content; set by the caller. */
    var context: TransferContext? = null
    var content: String? = null
    var contentClass: String? = null
    /** The row's counters, under [observation]. */
    val observation = Mutex()
    val mirrors = options.uris.associateWith { SourceTraffic() }
    val down = TransferRate()
    var state = TransferState.WAITING
    var total: Long? = null
    var completed = 0L
    var failure: String? = null
    /** Ranges of the file and those received whole; zero while the file comes in one request. */
    var spans = 0
    var spansHeld = 0
    var step = 0L

    /** The path the file is written to. */
    val outPath: String
        get() {
            val name = options.out ?: options.uris.first().substringBefore('?').substringAfterLast('/')
            return options.dir?.let { fileOps.resolvePath(it, name) } ?: name
        }

    /** Fetches, verifies and writes the file; returns its path. A present file with the pinned checksum is kept under `continue`. */
    suspend fun execute(): String {
        require(options.uris.isNotEmpty()) { "No URIs provided" }
        val path = outPath
        if (options.continueDownload && options.checksum != null && fileOps.exists(path) &&
            runCatching { verify(fileOps.readAllBytes(path)) }.isSuccess) return path
        val bytes = fetch()
        options.dir?.let(fileOps::mkdirs)
        fileOps.write(path, bytes)
        return path
    }

    /** The file's bytes, verified against the checksum when one is given; in one request when no mirror serves it in ranges. */
    suspend fun fetch(): ByteArray {
        require(options.uris.isNotEmpty()) { "No URIs provided" }
        observation.withLock { state = TransferState.ACTIVE; failure = null }
        try {
            val length = length()
            observation.withLock { total = length.takeIf { it >= 0 } }
            val bytes = if (length > options.minSplitSize && options.split > 1) {
                try { ranges(length) } catch (cancelled: CancellationException) { throw cancelled } catch (unranged: IllegalStateException) { whole(length) }
            } else whole(length)
            verify(bytes)
            observation.withLock { state = TransferState.COMPLETE; total = bytes.size.toLong(); completed = bytes.size.toLong() }
            return bytes
        } catch (cancelled: CancellationException) {
            observation.withLock { state = TransferState.WAITING }
            throw cancelled
        } catch (failed: Exception) {
            observation.withLock { state = TransferState.ERROR; failure = failed.message }
            throw failed
        }
    }

    /**
     * The file's length: the total a mirror names in the `Content-Range` of a one-byte range, which also shows it
     * serves ranges, else the `Content-Length` of a HEAD; -1 when no mirror gives either.
     */
    suspend fun length(): Long {
        for (uri in options.uris) {
            try {
                val ranged = exchange(uri, HtxRange(0, 0), HtxMethod.GET, 1)
                if (ranged.status == 206) ranged.headers.headerValue("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull()?.let { return it }
            } catch (cancelled: CancellationException) { throw cancelled } catch (failed: Exception) { }
            val response = try { exchange(uri, null, HtxMethod.HEAD, 0) } catch (cancelled: CancellationException) { throw cancelled } catch (failed: Exception) { continue }
            if (response.status !in 200..299) continue
            return response.headers.headerValue("Content-Length")?.trim()?.toLongOrNull() ?: continue
        }
        return -1
    }

    /** Bytes [offset, offset + count) of the file, from the first mirror, taken in turn from [first], that answers 206 with exactly that many. */
    suspend fun read(offset: Long, count: Int, first: Int = 0): ByteArray {
        require(options.uris.isNotEmpty()) { "No URIs provided" }
        require(offset >= 0 && count > 0) { "range $offset+$count" }
        val failures = ArrayList<String>()
        for (turn in options.uris.indices) {
            val uri = options.uris[(first + turn) % options.uris.size]
            try {
                val response = exchange(uri, HtxRange(offset, offset + count - 1), HtxMethod.GET, count)
                val bytes = response.body.toArray()
                if (response.status == 206 && bytes.size == count) return bytes.also { held(uri, count) }
                failures += "$uri: status ${response.status}, ${bytes.size} bytes for $count"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failed: Exception) { failures += "$uri: $failed" }
            observation.withLock { mirrors.getValue(uri).failure = failures.last() }
        }
        error("bytes $offset-${offset + count - 1} failed on every mirror: ${failures.joinToString("; ")}")
    }

    /** The file in ranges, gathered into one array. */
    suspend fun ranges(length: Long): ByteArray = coroutineScope {
        require(length <= MAX_BYTES) { "$length bytes: a file this large needs positional writes, which this engine does not have" }
        val step = maxOf(options.minSplitSize, (length + options.split - 1) / options.split)
        val count = ((length + step - 1) / step).toInt()
        observation.withLock { spans = count; spansHeld = 0; this@HtxAria2Engine.step = step }
        val gate = Semaphore(maxOf(1, options.maxConnectionPerServer) * options.uris.size)
        val out = ByteArray(length.toInt())
        (0 until count).map { k ->
            async {
                val from = k * step
                gate.withPermit { read(from, (minOf(from + step, length) - from).toInt(), k).copyInto(out, from.toInt()) }
                observation.withLock { spansHeld++ }
            }
        }.awaitAll()
        out
    }

    /** The file in one request, from the first mirror that answers 200 with [length] bytes (any length when it is unknown). */
    suspend fun whole(length: Long): ByteArray {
        require(length <= MAX_BYTES) { "$length bytes: a file this large needs positional writes, which this engine does not have" }
        observation.withLock { spans = 0; spansHeld = 0; completed = 0 }
        val failures = ArrayList<String>()
        for (uri in options.uris) {
            try {
                val response = exchange(uri, null, HtxMethod.GET, if (length < 0) MAX_BYTES.toInt() else length.toInt())
                val bytes = response.body.toArray()
                if (response.status == 200 && (length < 0 || bytes.size.toLong() == length)) return bytes.also { held(uri, it.size) }
                failures += "$uri: status ${response.status}, ${bytes.size} bytes" + if (length < 0) "" else " for $length"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failed: Exception) { failures += "$uri: $failed" }
            observation.withLock { mirrors.getValue(uri).failure = failures.last() }
        }
        error("failed on every mirror: ${failures.joinToString("; ")}")
    }

    /** One exchange with [uri]: room for [body] bytes and the headers, and time for them at [SLOWEST] bytes a second. */
    suspend fun exchange(uri: String, range: HtxRange?, method: HtxMethod, body: Int): HtxResponse {
        val response = clientReactor.request(parseHtxRequest(uri, range = range, method = method).copy(
            maxResponseBytes = minOf(Int.MAX_VALUE.toLong(), body.toLong() + HEADER_ROOM).toInt(),
            timeoutMs = maxOf(HtxRequest.DEFAULT_TIMEOUT_MS, body.toLong() * 1000 / SLOWEST)))
        requests++
        received += response.body.rem
        observation.withLock { mirrors[uri]?.down?.add(response.body.rem.toLong()); down.add(response.body.rem.toLong()) }
        return response
    }

    /** [count] bytes from [uri] came whole. */
    suspend fun held(uri: String, count: Int) = observation.withLock {
        completed += count
        mirrors.getValue(uri).let { it.completed += count; it.failure = null }
    }

    /** This file as a transfer row: its mirrors are the sources, its ranges the pieces. */
    suspend fun row(): Transfer = observation.withLock {
        Transfer(options.uris.first(), outPath.substringAfterLast('/'), TransferProtocol.HTTP, state, total, completed, down.now(), 0,
            if (spans == 0) null else TransferPieces(spans, spansHeld, step.toInt()),
            options.uris.map { uri ->
                val traffic = mirrors.getValue(uri)
                TransferSource(uri, TransferProtocol.HTTP, if (uri.startsWith("https:")) TransferTransport.TLS else TransferTransport.TCP,
                    when {
                        traffic.down.now() > 0 -> TransferState.ACTIVE
                        traffic.failure != null -> TransferState.ERROR
                        traffic.completed > 0 && state == TransferState.COMPLETE -> TransferState.COMPLETE
                        else -> TransferState.WAITING
                    }, traffic.down.now(), 0, traffic.completed)
            }.toSeries(), context, content, contentClass, failure.takeIf { state == TransferState.ERROR })
    }

    /** Fails unless [bytes] has the pinned length and the `checksum` option's digest; what is not pinned is not checked. */
    fun verify(bytes: ByteArray) = verify(bytes, options.checksum, options.length)

    companion object {
        /** Fails unless [bytes] has [length] and the digest [checksum] names (`sha-256=…` or `sha-1=…`); a null is not checked. */
        fun verify(bytes: ByteArray, checksum: String?, length: Long?) {
            length?.let { check(bytes.size.toLong() == it) { "length differs: ${bytes.size} bytes, pinned $it" } }
            val pin = checksum ?: return
            val type = pin.substringBefore('=').lowercase()
            val digest = when (type) {
                "sha-256" -> borg.trikeshed.job.sha256(bytes)
                "sha-1" -> borg.trikeshed.torrent.Sha1.digest(bytes)
                else -> throw IllegalArgumentException("checksum type $type is not supported: sha-256 and sha-1 are")
            }
            val actual = digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            check(actual == pin.substringAfter('=').trim().lowercase()) { "checksum differs: $type of ${bytes.size} bytes is $actual, pinned ${pin.substringAfter('=')}" }
        }

        /** The most one in-memory file holds. */
        const val MAX_BYTES: Long = Int.MAX_VALUE.toLong() - 64
        /** Response bytes allowed beyond the body: status line and headers. */
        const val HEADER_ROOM: Long = 64 * 1024
        /** The slowest link a request's time budget allows for, in bytes a second. */
        const val SLOWEST: Long = 64 * 1024
    }
}
