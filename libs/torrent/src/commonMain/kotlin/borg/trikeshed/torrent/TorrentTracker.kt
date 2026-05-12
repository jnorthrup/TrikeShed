package borg.trikeshed.torrent

/**
 * HTTP BitTorrent tracker announce + scrape (BEP 3).
 */
class TorrentTracker(
    private val request: suspend (CharSequence) -> CharSequence,  // HTTP GET → response body
) {
    data class TrackerResponse(
        val failureReason: CharSequence? = null,
        val warningMessage: CharSequence? = null,
        val interval: Int = 1800,
        val minInterval: Int? = null,
        val trackerId: CharSequence? = null,
        val complete: Int = 0,
        val incomplete: Int = 0,
        val peers: List<PeerAddress> = emptyList(),
    )

    data class PeerAddress(val ip: CharSequence, val port: Int)

    /**
     * Build announce URL and fetch peer list.
     * @param infoHash URL-encoded 20-byte info hash
     * @param peerId our peer ID
     * @param port listening port
     * @param uploaded bytes uploaded so far
     * @param downloaded bytes downloaded so far
     * @param left bytes remaining
     */
    suspend fun announce(
        trackerUrl: CharSequence,
        infoHash: CharSequence,
        peerId: CharSequence,
        port: Int,
        uploaded: Long = 0,
        downloaded: Long = 0,
        left: Long = 0,
        event: CharSequence = "started",  // started, stopped, completed, empty
    ): TrackerResponse {
        val params = buildString {
            append("?info_hash=$infoHash")
            append("&peer_id=$peerId")
            append("&port=$port")
            append("&uploaded=$uploaded")
            append("&downloaded=$downloaded")
            append("&left=$left")
            append("&compact=1")
            if (event.isNotEmpty()) append("&event=$event")
        }
        val body = request("$trackerUrl$params")
        return parseTrackerResponse(body)
    }

    private fun parseTrackerResponse(body: CharSequence): TrackerResponse {
        // Simplified bencode parser for tracker responses
        if (body.startsWith("d14:failure reason")) {
            val msg = body.substringAfter(":").substringBefore("e")
            return TrackerResponse(failureReason = msg)
        }
        // For now return empty peer list — full bencode parsing is heavy
        return TrackerResponse(peers = emptyList())
    }
}
