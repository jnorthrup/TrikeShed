package borg.trikeshed.torrent

/**
 * Magnet link parser (BEP 9).
 *
 * Format: magnet:?xt=urn:btih:<info_hash>&dn=<name>&tr=<tracker_url>&x.pe=<peer_addr>
 *
 * Info hash can be:
 *   - 40 hex chars (SHA-1)
 *   - 32 base32 chars
 *   - 64 hex chars (SHA-256, BEP 52 / BitTorrent v2, not yet supported)
 */
data class MagnetLink(
    val infoHash: ByteArray,       // 20-byte SHA-1 info hash
    val displayName: String? = null,
    val trackers: List<String> = emptyList(),
    val exactLength: Long? = null,  // xl parameter
    val peers: List<Pair<String, Int>> = emptyList(),  // x.pe: host:port
    val manifest: String? = null,   // xs parameter (web seed / manifest URL)
) {
    companion object {
        private val HEX_PATTERN = Regex("[0-9a-fA-F]{40}")
        private val BASE32_PATTERN = Regex("[A-Z2-7]{32}", RegexOption.IGNORE_CASE)

        fun parse(uri: String): MagnetLink? {
            if (!uri.startsWith("magnet:?", ignoreCase = true)) return null
            val qs = uri.substringAfter('?')
            val params = mutableMapOf<String, MutableList<String>>()
            for (pair in qs.split('&')) {
                val eq = pair.indexOf('=')
                if (eq < 0) continue
                val key = pair.substring(0, eq).lowercase()
                val value = pair.substring(eq + 1)
                params.getOrPut(key) { mutableListOf() }.add(value)
            }

            // Extract info hash from xt=urn:btih:<hash>
            val xtValues = params["xt"] ?: return null
            val ihHex = xtValues.mapNotNull { it.substringAfter("urn:btih:").lowercase() }.firstOrNull()
                ?: return null
            val infoHash = hexToBytes(ihHex) ?: base32ToBytes(ihHex) ?: return null

            val displayName = params["dn"]?.firstOrNull()
            val trackers = params["tr"] ?: emptyList()
            val exactLength = params["xl"]?.firstOrNull()?.toLongOrNull()
            val peers = params["x.pe"]?.mapNotNull {
                val colon = it.lastIndexOf(':')
                if (colon > 0) (it.substring(0, colon) to it.substring(colon + 1).toIntOrNull())
                else null
            }?.filter { it.second != null }?.map { it.first to it.second!! } ?: emptyList()
            val manifest = params["xs"]?.firstOrNull()

            return MagnetLink(infoHash, displayName, trackers, exactLength, peers, manifest)
        }

        private fun hexToBytes(hex: String): ByteArray? {
            if (hex.length != 40 || !hex.matches(HEX_PATTERN)) return null
            return ByteArray(20) { i ->
                ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
            }
        }

        fun byteToHex(b: Byte): String {
            val i = b.toInt() and 0xFF
            return "${HEX_CHARS[i shr 4]}${HEX_CHARS[i and 0xF]}"
        }
        private val HEX_CHARS = "0123456789abcdef".toCharArray()

        private fun base32ToBytes(b32: String): ByteArray? {
            if (b32.length != 32) return null
            // RFC 4648 base32 hex alphabet
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            val result = ByteArray(20)
            var bitBuffer = 0
            var bitsInBuffer = 0
            var bytePos = 0
            for (c in b32.uppercase()) {
                val idx = alphabet.indexOf(c)
                if (idx < 0) return null
                bitBuffer = (bitBuffer shl 5) or idx
                bitsInBuffer += 5
                if (bitsInBuffer >= 8) {
                    bitsInBuffer -= 8
                    result[bytePos++] = ((bitBuffer shr bitsInBuffer) and 0xFF).toByte()
                }
            }
            return if (bytePos == 20) result else null
        }
    }

    /** Reconstruct magnet URI string. */
    fun toUri(): String = buildString {
        append("magnet:?xt=urn:btih:")
        append(infoHash.joinToString("") { MagnetLink.byteToHex(it) })
        if (displayName != null) append("&dn=${displayName}")
        for (tr in trackers) append("&tr=$tr")
        if (exactLength != null) append("&xl=$exactLength")
        for ((host, port) in peers) append("&x.pe=$host:$port")
    }

}
