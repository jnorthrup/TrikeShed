package borg.trikeshed.torrent

import borg.trikeshed.htx.HtxKey
import borg.trikeshed.htx.HtxMethod
import borg.trikeshed.htx.parseHtxRequest
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toArray
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext

data class TrackerAnnounce(val peers: Series<PeerAddress>, val intervalMillis: Long)
enum class TrackerEvent(val wire: String) { STARTED("started"), COMPLETED("completed"), STOPPED("stopped"), PERIODIC("") }

/** Binary tracker responses stay bytes throughout HTX and bencode decoding. */
class TrackerClient(private val timeoutMillis: Long = 15000) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<TrackerClient> {
        fun parseResponse(bytes: ByteArray): TrackerAnnounce {
            require(bytes.size <= 2 * 1048576) { "Tracker response too large" }
            val dict = TorrentBencode.decode(bytes) as? BencodeValue.Dictionary
                ?: throw IllegalArgumentException("Tracker response must be a dictionary")
            (dict["failure reason"] as? BencodeValue.Bytes)?.let { throw IllegalStateException(it.text()) }
            val interval = (dict["interval"] as? BencodeValue.Integer)?.value
                ?: throw IllegalArgumentException("Tracker interval missing")
            require(interval in 1..86400) { "Invalid tracker interval" }
            val peers = mutableListOf<PeerAddress>()
            when (val value = dict["peers"]) {
                is BencodeValue.Bytes -> {
                    val compact = value.bytes
                    require(compact.size % 6 == 0 && compact.size <= 6 * 4096) { "Invalid compact peer list" }
                    for (at in compact.indices step 6) {
                        val port = ((compact[at + 4].toInt() and 255) shl 8) or (compact[at + 5].toInt() and 255)
                        if (port != 0) peers += PeerAddress((0..3).joinToString(".") { (compact[at + it].toInt() and 255).toString() }, port)
                    }
                }
                is BencodeValue.ListValue -> {
                    for (item in value.values.view) {
                        require(peers.size < 4096) { "Too many tracker peers" }
                        val peer = item as? BencodeValue.Dictionary ?: throw IllegalArgumentException("Invalid tracker peer")
                        val host = (peer["ip"] as? BencodeValue.Bytes)?.text() ?: throw IllegalArgumentException("Peer IP missing")
                        val port = (peer["port"] as? BencodeValue.Integer)?.value ?: throw IllegalArgumentException("Peer port missing")
                        require(port in 1..65535)
                        peers += PeerAddress(host, port.toInt())
                    }
                }
                else -> throw IllegalArgumentException("Tracker peers missing")
            }
            return TrackerAnnounce(peers.distinct().toSeries(), interval * 1000)
        }
    }
    override val key: CoroutineContext.Key<*> get() = Key

    suspend fun announce(url: String, infoHash: InfoHash, peerId: ByteArray, port: Int,
                         uploaded: Long, downloaded: Long, left: Long, event: TrackerEvent): TrackerAnnounce = withTimeout(timeoutMillis) {
        if (url.startsWith("udp://")) return@withTimeout TorrentUdpTracker.announce(url, infoHash, peerId,
            port, uploaded, downloaded, left, event, timeoutMillis)
        require(url.startsWith("http://") || url.startsWith("https://")) { "Tracker must use HTTP or HTTPS: $url" }
        require(peerId.size == 20 && port in 1..65535 && uploaded >= 0 && downloaded >= 0 && left >= 0)
        val htx = requireNotNull(currentCoroutineContext()[HtxKey]) { "Torrent tracker requires the owning HTX context" }
        val target = buildString {
            append(url.substringBefore('#')); append(if ('?' in url) '&' else '?')
            append("info_hash=").append(percentEncodeBinary(infoHash.wireBytes))
            append("&peer_id=").append(percentEncodeBinary(peerId)).append("&port=").append(port)
            append("&uploaded=").append(uploaded).append("&downloaded=").append(downloaded)
            append("&left=").append(left).append("&compact=1&numwant=64")
            if (event.wire.isNotEmpty()) append("&event=").append(event.wire)
        }
        val response = htx.request(parseHtxRequest(target, method = HtxMethod.GET))
        check(response.status in 200..299) { "Tracker HTTP ${response.status}" }
        parseResponse(response.body.toArray())
    }
}
