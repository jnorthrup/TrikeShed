package borg.trikeshed.torrent

/**
 * DHT wire protocol messages (BEP 5).
 *
 * Bencoded UDP messages: {"t":"aa", "y":"q", "q":"ping", "a":{"id":"..."}}
 *
 * Query types: ping, find_node, get_peers, announce_peer
 * Response types: r (return), e (error)
 */
object DhtProtocol {
    // ── Query messages ───────────────────────────────────────────

    /** Ping a node to check if it's alive. {"t":"aa","y":"q","q":"ping","a":{"id":"<our_id>"}} */
    fun ping(txId: String, ourId: ByteArray): ByteArray =
        buildQuery(txId, "ping", mapOf("id" to ourId))

    /** Find nodes closest to target. {"t":"aa","y":"q","q":"find_node","a":{"id":"<our_id>","target":"<target>"}} */
    fun findNode(txId: String, ourId: ByteArray, target: ByteArray): ByteArray =
        buildQuery(txId, "find_node", mapOf("id" to ourId, "target" to target))

    /** Get peers for an info hash. Returns peers or closest nodes. */
    fun getPeers(txId: String, ourId: ByteArray, infoHash: ByteArray): ByteArray =
        buildQuery(txId, "get_peers", mapOf("id" to ourId, "info_hash" to infoHash))

    /** Announce that we have this torrent. {"t":"aa","y":"q","q":"announce_peer","a":{"id":"...","info_hash":"...","port":6881,"token":"..."}} */
    fun announcePeer(txId: String, ourId: ByteArray, infoHash: ByteArray, port: Int, token: ByteArray): ByteArray =
        buildQuery(txId, "announce_peer", mapOf(
            "id" to ourId, "info_hash" to infoHash,
            "port" to port, "token" to token,
            "implied_port" to 0))

    // ── Response messages ────────────────────────────────────────

    /** Successful response. {"t":"aa","y":"r","r":{"id":"<remote_id>",...}} */
    fun response(txId: String, values: Map<String, Any>): ByteArray =
        bencode(mapOf("t" to txId, "y" to "r", "r" to values))

    /** Error response. {"t":"aa","y":"e","e":[201,"Generic Error"]} */
    fun error(txId: String, code: Int, message: String): ByteArray =
        bencode(mapOf("t" to txId, "y" to "e", "e" to listOf(code, message)))

    // ── Parsing ──────────────────────────────────────────────────

    data class DhtMessage(
        val txId: String,
        val type: Char,      // 'q', 'r', 'e'
        val query: String?,
        val args: Map<String, Any>?,
        val response: Map<String, Any>?,
        val error: List<Any>?,
    )

    /** Parse a bencoded DHT message from raw bytes. */
    fun parse(data: ByteArray): DhtMessage? {
        val dict = parseBencode(data) as? Map<*, *> ?: return null
        @Suppress("UNCHECKED_CAST")
        val m = dict as Map<String, Any>
        val t = m["t"] as? String ?: return null
        val y = (m["y"] as? String)?.firstOrNull() ?: return null
        return DhtMessage(
            txId = t, type = y,
            query = m["q"] as? String,
            args = m["a"] as? Map<String, Any>,
            response = m["r"] as? Map<String, Any>,
            error = (m["e"] as? List<*>)?.map { it!! },
        )
    }

    // ── Response interpretation ──────────────────────────────────

    /** Extract compact node info from a response (26 bytes per node: 20-byte ID + 4-byte IP + 2-byte port). */
    fun nodes6(response: Map<String, Any>): List<KademliaDht.Node> {
        val raw = response["nodes"] as? String ?: (response["nodes"] as? ByteArray)
            ?: return emptyList()
        val bytes = when (raw) {
            is String -> raw.encodeToByteArray()
            is ByteArray -> raw
            else -> return emptyList()
        }
        val nodes = mutableListOf<KademliaDht.Node>()
        var i = 0
        while (i + 26 <= bytes.size) {
            val id = bytes.copyOfRange(i, i + 20)
            val ip = "${bytes[i+20].toInt() and 0xFF}.${bytes[i+21].toInt() and 0xFF}.${bytes[i+22].toInt() and 0xFF}.${bytes[i+23].toInt() and 0xFF}"
            val port = ((bytes[i+24].toInt() and 0xFF) shl 8) or (bytes[i+25].toInt() and 0xFF)
            nodes.add(KademliaDht.Node(id, ip, port))
            i += 26
        }
        return nodes
    }

    /** Extract peer addresses from a response (6 bytes per peer: 4-byte IP + 2-byte port). */
    fun peers6(response: Map<String, Any>): List<KademliaDht.Node> {
        val raw = response["values"] as? String ?: (response["values"] as? ByteArray)
            ?: return emptyList()
        val bytes = when (raw) {
            is String -> raw.encodeToByteArray()
            is ByteArray -> raw
            else -> return emptyList()
        }
        val peers = mutableListOf<KademliaDht.Node>()
        var i = 0
        while (i + 6 <= bytes.size) {
            val ip = "${bytes[i].toInt() and 0xFF}.${bytes[i+1].toInt() and 0xFF}.${bytes[i+2].toInt() and 0xFF}.${bytes[i+3].toInt() and 0xFF}"
            val port = ((bytes[i+4].toInt() and 0xFF) shl 8) or (bytes[i+5].toInt() and 0xFF)
            peers.add(KademliaDht.Node(ByteArray(20), ip, port)) // peer ID unknown
            i += 6
        }
        return peers
    }

    // ── Bencode helpers ───────────────────────────────────────────

    private fun buildQuery(txId: String, query: String, args: Map<String, Any>): ByteArray =
        bencode(mapOf("t" to txId, "y" to "q", "q" to query, "a" to args))

    /** Minimal bencode encoder (strings, integers, lists, maps). */
    fun bencode(value: Any): ByteArray {
        return when (value) {
            is String -> "${value.length}:$value".encodeToByteArray()
            is ByteArray -> "${value.size}:${value.decodeToString()}".encodeToByteArray()
            is Int -> "i${value}e".encodeToByteArray()
            is Long -> "i${value}e".encodeToByteArray()
            is List<*> -> {
                val parts = mutableListOf<ByteArray>()
                parts.add("l".encodeToByteArray())
                for (item in value) { if (item != null) parts.add(bencode(item)) }
                parts.add("e".encodeToByteArray())
                parts.fold(ByteArray(0)) { a, b -> a + b }
            }
            is Map<*, *> -> {
                val parts = mutableListOf<ByteArray>()
                parts.add("d".encodeToByteArray())
                val sorted = value.entries.sortedBy { it.key.toString() }
                for ((k, v) in sorted) {
                    val keyStr = k.toString()
                    parts.add(bencode(keyStr))
                    if (v != null) parts.add(bencode(v))
                }
                parts.add("e".encodeToByteArray())
                parts.fold(ByteArray(0)) { a, b -> a + b }
            }
            else -> error("Cannot bencode: $value")
        }
    }

    /** Minimal bencode parser (returns Any? — String, Long, List<Any?>, Map<String, Any?>). */
    fun parseBencode(data: ByteArray): Any? {
        return parseBencodeAt(data, 0).first
    }

    private fun parseBencodeAt(data: ByteArray, pos: Int): Pair<Any?, Int> {
        if (pos >= data.size) return null to pos
        return when (val c = data[pos].toInt().toChar()) {
            'i' -> {
                val end = (pos..<data.size).first { data[it].toInt().toChar() == 'e' }
                val num = data.decodeToString(pos + 1, end).toLong()
                num to (end + 1)
            }
            'l' -> {
                val list = mutableListOf<Any?>()
                var p = pos + 1
                while (p < data.size && data[p].toInt().toChar() != 'e') {
                    val (item, next) = parseBencodeAt(data, p)
                    list.add(item)
                    p = next
                }
                list to (p + 1)
            }
            'd' -> {
                val map = mutableMapOf<String, Any?>()
                var p = pos + 1
                while (p < data.size && data[p].toInt().toChar() != 'e') {
                    val (keyObj, kp) = parseBencodeAt(data, p)
                    val key = keyObj as? String ?: keyObj.toString()
                    val (valObj, vp) = parseBencodeAt(data, kp)
                    map[key] = valObj
                    p = vp
                }
                map to (p + 1)
            }
            in '0'..'9' -> {
                val colon = (pos..<data.size).first { data[it].toInt().toChar() == ':' }
                val len = data.decodeToString(pos, colon).toInt()
                val str = ByteArray(len)
                for (i in 0 until len) str[i] = data[colon + 1 + i]
                str to (colon + 1 + len)
            }
            else -> error("Unexpected bencode char '$c' at $pos")
        }
    }
}
