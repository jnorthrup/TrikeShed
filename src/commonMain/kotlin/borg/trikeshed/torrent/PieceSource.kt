package borg.trikeshed.torrent

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.transfer.*
import kotlinx.coroutines.currentCoroutineContext

/** A source's bytes for piece [index] do not hash to the metainfo's. */
class TorrentPieceMismatch(val index: Int, val source: String) : IllegalStateException("Torrent piece $index from $source failed verification")

/** Where a session's pieces come from: a connected peer or a web seed. Its bytes are unverified until the session checks the piece. */
interface PieceSource {
    val address: String
    val traffic: SourceTraffic
    val protocol: TransferProtocol
    val transport: TransferTransport
    /** Whether a request for piece [index] can be made of this source now. */
    fun holds(index: Int): Boolean
    /** The bytes of piece [index], [length] of them; [received] counts them as they arrive. */
    suspend fun piece(index: Int, length: Int, received: suspend (Int) -> Unit): ByteArray
}

/**
 * BEP 19: a URL that serves the torrent's files over HTTP. A piece is the byte ranges of the files it spans, each
 * asked of the client reactor in the caller's context, which follows redirects. A single-file torrent's file is the
 * URL itself unless the URL ends in '/'; otherwise a file is the URL, the torrent's name and the file's path.
 */
class TorrentWebSeed(val url: String, val file: TorrentFile) : PieceSource {
    override val address: String get() = url
    override val traffic = SourceTraffic()
    override val protocol: TransferProtocol get() = TransferProtocol.HTTP
    override val transport: TransferTransport get() = if (url.startsWith("https:")) TransferTransport.TLS else TransferTransport.TCP
    override fun holds(index: Int): Boolean = true

    fun uri(entry: TorrentFileEntry): String {
        val name = percentEncode(file.info.name)
        return if (file.info.files == null && file.info.fileTree == null) { if (url.endsWith('/')) url + name else url }
        else (if (url.endsWith('/')) url else "$url/") + name + "/" + entry.path.joinToString("/") { percentEncode(it) }
    }

    override suspend fun piece(index: Int, length: Int, received: suspend (Int) -> Unit): ByteArray {
        val client = requireNotNull(currentCoroutineContext()[HtxClientKey]) { "A web seed needs the HTX client reactor in its context" }
        val begin = index.toLong() * file.pieceSize
        val bytes = ByteArray(length)
        for (entry in file.files.view) {
            val lo = maxOf(begin, entry.offset)
            val hi = minOf(begin + length, entry.offset + entry.length)
            if (entry.padding || lo >= hi) continue
            val count = (hi - lo).toInt()
            val response = client.request(parseHtxRequest(uri(entry), range = HtxRange(lo - entry.offset, hi - entry.offset - 1)).copy(
                maxResponseBytes = count + HEADER_ROOM, timeoutMs = maxOf(HtxRequest.DEFAULT_TIMEOUT_MS, count * 1000L / SLOWEST)))
            val body = response.body.toArray()
            check(response.status == 206 && body.size == count) {
                "$url: status ${response.status}, ${body.size} bytes for $count of ${entry.path.joinToString("/")}"
            }
            body.copyInto(bytes, (lo - begin).toInt())
            received(count)
        }
        return bytes
    }

    companion object {
        /** Response bytes allowed beyond a range: status line and headers. */
        const val HEADER_ROOM = 64 * 1024
        /** The slowest link a range's time budget allows for, in bytes a second. */
        const val SLOWEST = 64 * 1024L
    }
}
