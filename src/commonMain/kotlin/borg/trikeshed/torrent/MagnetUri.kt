package borg.trikeshed.torrent

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view

/** BEP 9 exact topics and ordered repeated peer sources, before metadata is available. */
class MagnetUri private constructor(
    val infoHashes: Series<InfoHash>,
    val displayName: String?,
    val trackers: Series<String>,
    val peers: Series<PeerAddress>,
) {
    val infoHash: InfoHash = infoHashes.view.firstOrNull { it.isV2 } ?: infoHashes.view.first()

    /** A hybrid magnet binds both advertised hashes to the same exact info dictionary. */
    fun validateMetadata(bytes: ByteArray): TorrentFile {
        val torrent = TorrentFile.fromInfoBytes(bytes, infoHash)
        require(infoHashes.view.all { it == torrent.v1InfoHash || it == torrent.v2InfoHash }) { "Magnet exact topics disagree with metadata" }
        return torrent
    }

    companion object {
        const val MAX_URI_LENGTH = 65_536
        fun parse(uri: String): MagnetUri {
            require(uri.length <= MAX_URI_LENGTH && uri.startsWith("magnet:?", ignoreCase = true)) { "Expected bounded magnet URI" }
            val query = uri.substring(8).substringBefore('#')
            val fields = query.split('&')
            require(fields.size <= 1024) { "Magnet parameter limit" }
            val hashes = mutableListOf<InfoHash>()
            val trackers = mutableListOf<String>()
            val peers = mutableListOf<PeerAddress>()
            var name: String? = null
            for (field in fields) {
                val split = field.indexOf('=')
                if (split < 0) continue
                val key = percent(field.substring(0, split))
                val value = percent(field.substring(split + 1))
                when (key) {
                    "xt" -> {
                        val hash = when {
                            value.startsWith("urn:btih:", ignoreCase = true) -> {
                                val encoded = value.substring(9)
                                InfoHash(if (encoded.length == 40) hex(encoded) else base32(encoded))
                            }
                            value.startsWith("urn:btmh:", ignoreCase = true) -> {
                                val encoded = hex(value.substring(9))
                                require(encoded.size == 34 && encoded[0] == 0x12.toByte() && encoded[1] == 32.toByte()) { "btmh requires SHA2-256 multihash" }
                                InfoHash(encoded.copyOfRange(2, 34))
                            }
                            else -> null
                        }
                        if (hash != null) {
                            require(hashes.none { it.isV2 == hash.isV2 && it != hash }) { "Conflicting magnet exact topics" }
                            if (hash !in hashes) hashes += hash
                        }
                    }
                    "dn" -> if (name == null) name = value
                    "tr" -> { require(value.isNotEmpty()); trackers += value }
                    "x.pe" -> peers += peer(value)
                }
            }
            require(hashes.isNotEmpty()) { "Magnet lacks supported btih/btmh exact topic" }
            return MagnetUri(hashes.toSeries(), name, trackers.toSeries(), peers.toSeries())
        }

        private fun peer(text: String): PeerAddress {
            val host: String
            val port: String
            if (text.startsWith('[')) {
                val end = text.indexOf(']')
                require(end > 1 && end + 1 < text.length && text[end + 1] == ':') { "Invalid bracketed peer address" }
                host = text.substring(1, end); port = text.substring(end + 2)
                require(':' in host && host.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }) { "Invalid IPv6 peer literal" }
            } else {
                val split = text.lastIndexOf(':')
                require(split > 0 && text.indexOf(':') == split) { "Peer address requires host:port or [IPv6]:port" }
                host = text.substring(0, split); port = text.substring(split + 1)
                require(host.none { it.isWhitespace() || it in "/\\[]@?#\u0000" }) { "Invalid peer host" }
            }
            require(port.isNotEmpty() && port.all { it in '0'..'9' }) { "Invalid peer port" }
            val number = port.toIntOrNull() ?: throw IllegalArgumentException("Invalid peer port")
            require(number in 1..65535)
            return PeerAddress(host, number)
        }
        internal fun percent(text: String): String {
            val bytes = text.encodeToByteArray()
            val decoded = ByteArray(bytes.size)
            var read = 0; var write = 0
            while (read < bytes.size) {
                if (bytes[read] == 37.toByte()) {
                    require(read + 2 < bytes.size) { "Truncated URI escape" }
                    val high = bytes[read + 1].toInt().toChar().digitToIntOrNull(16)
                    val low = bytes[read + 2].toInt().toChar().digitToIntOrNull(16)
                    require(high != null && low != null) { "Invalid URI escape" }
                    decoded[write++] = ((high shl 4) or low).toByte(); read += 3
                } else decoded[write++] = bytes[read++]
            }
            return BencodeValue.Bytes(decoded.copyOf(write)).text().also { require('\u0000' !in it) { "NUL in magnet URI" } }
        }
        private fun hex(text: String): ByteArray {
            require(text.length % 2 == 0 && text.length in 2..68) { "Invalid hash width" }
            return ByteArray(text.length / 2) { i ->
                val a = text[i * 2].digitToIntOrNull(16); val b = text[i * 2 + 1].digitToIntOrNull(16)
                require(a != null && b != null) { "Invalid hash hex" }; ((a shl 4) or b).toByte()
            }
        }
        private fun base32(text: String): ByteArray {
            require(text.length == 32) { "btih requires 40 hex or 32 base32 digits" }
            val output = ByteArray(20)
            var buffer = 0; var bits = 0; var at = 0
            for (char in text) {
                val digit = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(char.uppercaseChar())
                require(digit >= 0) { "Invalid btih base32" }
                buffer = (buffer shl 5) or digit; bits += 5
                if (bits >= 8) { bits -= 8; output[at++] = (buffer ushr bits).toByte() }
            }
            return output
        }
    }
}
