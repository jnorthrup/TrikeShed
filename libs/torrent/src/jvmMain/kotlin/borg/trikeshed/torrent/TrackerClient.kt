package borg.trikeshed.torrent

import borg.trikeshed.htx.client.HtxElement
import kotlinx.coroutines.*
import java.net.URLEncoder

/**
 * BitTorrent Tracker Protocol (BEP 3) client.
 *
 * Trackers speak HTTP/HTTPS. We announce with info-hash, peer-id, and download statistics,
 * and receive a bencoded response with peer list and interval.
 *
 * This client supports:
 *   - Regular announce (HTTP GET)
 *   - Compact peer list (bencoded "peers" as 6-byte binary records)
 *   - Tracker scrape (BEP 15) — not implemented here
 */
class TrackerClient(private val scope: CoroutineScope) {

    /**
     * Announce to a tracker and return discovered peer list.
     */
    suspend fun announce(
        torrentFile: TorrentFile,
        peerId: ByteArray,
        port: Int,
        uploaded: Long,
        downloaded: Long,
        left: Long,
    ): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        val infoHash = torrentFile.infoHashV1()
        val trackerUrl = torrentFile.announce ?: return@withContext emptyList()

        val params = buildList {
            add("info_hash" to urlEncode(infoHash))
            add("peer_id" to urlEncode(peerId))
            add("port" to port.toString())
            add("uploaded" to uploaded.toString())
            add("downloaded" to downloaded.toString())
            add("left" to left.toString())
            add("compact" to "1")
            add("event" to "started")
        }.joinToString("&") { "${it.first}=${it.second}" }

        val url = "$trackerUrl?$params"
        val response = try {
            HtxElement(baseUrl = trackerUrl).let { htx ->
                // Minimal GET request — just fetch the URL
                val resp = java.net.URL(url).readText()
                resp
            }
        } catch (e: Exception) {
            return@withContext emptyList()
        }

        parsePeers(response)
    }

    private fun parsePeers(response: String): List<Pair<String, Int>> {
        // Simple bencode parser for tracker response
        return try {
            val peersStart = response.indexOf("5:peers")
            if (peersStart < 0) return emptyList()
            // Compact: peers6:<<6 bytes each>>
            val colon = response.indexOf(':', peersStart + 6)
            val lengthStr = response.substring(peersStart + 6, colon)
            val length = lengthStr.toIntOrNull() ?: return emptyList()
            val peersData = response.substring(colon + 1, colon + 1 + length)
            parseCompactPeers(peersData)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseCompactPeers(data: ByteArray): List<Pair<String, Int>> {
        val peers = mutableListOf<Pair<String, Int>>()
        var offset = 0
        while (offset + 6 <= data.size) {
            val ip = "${data[offset].toInt() and 0xFF}.${data[offset + 1].toInt() and 0xFF}.${data[offset + 2].toInt() and 0xFF}.${data[offset + 3].toInt() and 0xFF}"
            val port = ((data[offset + 4].toInt() and 0xFF) shl 8) or (data[offset + 5].toInt() and 0xFF)
            peers.add(ip to port)
            offset += 6
        }
        return peers
    }

    private fun urlEncode(data: ByteArray): String {
        return data.joinToString("") { "%%%02X".format(it) }
    }

    fun close() { /* No resources to close in this stub */ }
}

private infix fun <A, B> A.to(that: B): Pair<A, B> = Pair(this, that)
