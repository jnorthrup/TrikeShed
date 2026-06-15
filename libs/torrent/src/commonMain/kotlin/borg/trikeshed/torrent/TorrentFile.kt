package borg.trikeshed.torrent

import java.security.MessageDigest

/**
 * BitTorrent v2 (BEP 52) Torrent File — bencode decode + info-hash computation.
 *
 * v2 torrents use SHA-256 piece hashes in a Merkle tree file tree (BEP 52).
 * Hybrid torrents additionally carry the v1 file tree root for backwards compat.
 */

/** .torrent file decoded from bencode. */
data class TorrentFile(
    val announce: String? = null,
    val announceList: List<List<String>> = emptyList(),
    val creationDate: Long? = null,
    val comment: String? = null,
    val createdBy: String? = null,
    val encoding: String? = null,
    val info: TorrentInfo,
) {
    /** Bencode-encoded info-bytes (deterministic for info-hash). */
    fun infoBytes(): ByteArray = BencodeEncoder.encodeInfo(info)

    /** v2 info-hash: SHA-256 of info-bytes (40 bytes zero-padded). */
    fun infoHashV2(): InfoHash {
        val digest = MessageDigest.getInstance("SHA-256")
        return InfoHash(digest.digest(infoBytes()))
    }

    /** v1 info-hash: SHA-1 of info-bytes (20 bytes). */
    fun infoHashV1(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        return digest.digest(infoBytes())
    }

    val isHybrid: Boolean get() = info.pieces.rootSha256 != null && info.sha1RootTree != null
    val isV2Only: Boolean get() = info.pieces.rootSha256 != null && info.sha1RootTree == null

    companion object {
        /**
         * Parse a .torrent file from raw bencode bytes.
         */
        fun parse(data: ByteArray): TorrentFile = BencodeParser.parse(data)
    }
}

data class TorrentInfo(
    val name: String,
    val pieceLength: Long? = null,
    val pieces: PieceLayer = PieceLayer(),
    val length: Long? = null,
    val metaVersion: Int? = null,
    val fileTree: FileTree? = null,
    val files: List<TorrentFileEntry>? = null,
    val sha1RootTree: String? = null,
    val source: String? = null,
    val private: Int? = null,
) {
    val isV2: Boolean get() = metaVersion == 2 || fileTree != null
    val v2PieceSize: Long get() = pieceLength ?: 16384
}

data class PieceLayer(
    val pieceHashes: List<String> = emptyList(), // v1: hex(SHA-1)
    val rootSha256: String? = null,               // v2: hex(SHA-256 root)
)

/** v2 file tree — hierarchical Merkle tree. */
data class FileTree(val root: Map<String, FileTreeNode> = emptyMap())

sealed class FileTreeNode {
    data class File(val length: Long, val piecesRoot: String? = null) : FileTreeNode()
    data class Directory(val files: Map<String, FileTreeNode> = emptyMap()) : FileTreeNode()
}

data class TorrentFileEntry(
    val length: Long,
    val path: List<String>,
    val md5sum: String? = null,
)

// ─── Bencode Parser ───────────────────────────────────────────────────────────

object BencodeParser {
    fun parse(data: ByteArray): TorrentFile {
        val tokens = Tokenizer(data).tokenize()
        val root = Decoder(tokens.iterator()).decodeDict()
        return parseTorrent(root)
    }

    private fun parseTorrent(map: Map<String, BencodeNode>): TorrentFile {
        return TorrentFile(
            announce = map["announce"]?.string,
            announceList = map["announce-list"]?.list?.map { tier ->
                tier.list.map { it.string }
            } ?: emptyList(),
            creationDate = map["creation date"]?.integer,
            comment = map["comment"]?.string,
            createdBy = map["created by"]?.string,
            encoding = map["encoding"]?.string,
            info = parseInfo(map["info"]!!.asDict()),
        )
    }

    private fun parseInfo(map: Map<String, BencodeNode>): TorrentInfo {
        val name = map["name"]?.string ?: "unknown"
        return TorrentInfo(
            name = name,
            pieceLength = map["piece length"]?.integer,
            pieces = parsePieceLayer(map),
            length = map["length"]?.integer,
            metaVersion = map["meta version"]?.integer?.toInt(),
            fileTree = map["file tree"]?.let { parseFileTree(it.asDict()) },
            files = map["files"]?.list?.map { parseFileEntry(it.asDict()) },
            sha1RootTree = map["sha1 root tree"]?.string,
            source = map["source"]?.string,
            private = map["private"]?.integer?.toInt(),
        )
    }

    private fun parsePieceLayer(map: Map<String, BencodeNode>): PieceLayer {
        val hex = map["pieces"]?.bytes?.toString(Charsets.ISO_8859_1)
        val v1Hashes = if (hex != null && hex.length % 40 == 0) {
            (0 until hex.length step 40).map { hex.substring(it, it + 40) }
        } else emptyList()
        return PieceLayer(v1Hashes, map["sha256"]?.string)
    }

    private fun parseFileTree(map: Map<String, BencodeNode>): FileTree {
        return FileTree(map.mapValues { parseFileTreeNode(it.value) })
    }

    private fun parseFileTreeNode(node: BencodeNode): FileTreeNode {
        val m = node.asDict()
        return when {
            m.containsKey("length") -> FileTreeNode.File(
                length = m["length"]!!.integer,
                piecesRoot = m["pieces (v2)"]?.string,
            )
            else -> FileTreeNode.Directory(m.mapValues { parseFileTreeNode(it.value) })
        }
    }

    private fun parseFileEntry(map: Map<String, BencodeNode>): TorrentFileEntry {
        return TorrentFileEntry(
            length = map["length"]!!.integer,
            path = map["path"]!!.list.map { it.string },
            md5sum = map["md5sum"]?.string,
        )
    }
}

// ─── Bencode Tokenizer ───────────────────────────────────────────────────────

private class Tokenizer(private val data: ByteArray) {
    private var pos = 0

    sealed class Token {
        data class BStr(val bytes: ByteArray) : Token()
        data class BInt(val value: Long) : Token()
        data object BListStart : Token()
        data object BDictStart : Token()
        data object BEnd : Token()
    }

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (pos < data.size) {
            tokens.add(nextToken())
        }
        return tokens
    }

    private fun nextToken(): Token {
        val c = data[pos++].toInt().toChar()
        return when (c) {
            'i' -> {
                val start = pos
                while (pos < data.size && data[pos].toInt().toChar() != 'e') pos++
                val num = data.copyOfRange(start, pos).toString(Charsets.ISO_8859_1).toLong()
                pos++ // skip 'e'
                Token.BInt(num)
            }
            'l' -> { pos--; pos++; Token.BListStart }
            'd' -> { pos--; pos++; Token.BDictStart }
            'e' -> Token.BEnd
            in '0'..'9' -> {
                val start = pos - 1
                while (pos < data.size && data[pos].toInt().toChar() in '0'..'9') pos++
                val len = data.copyOfRange(start, pos).toString(Charsets.ISO_8859_1).toInt()
                pos++ // skip ':'
                val bytes = data.copyOfRange(pos, pos + len)
                pos += len
                Token.BStr(bytes)
            }
            else -> throw IllegalArgumentException("Invalid bencode at pos ${pos - 1}: '$c'")
        }
    }
}

// ─── Bencode Decoder ────────────────────────────────────────────────────────

private class Decoder(private val it: Iterator<BencodeParser.Token>) {
    fun decodeDict(): Map<String, BencodeNode> {
        val map = LinkedHashMap<String, BencodeNode>()
        while (it.hasNext()) {
            val t = it.next()
            if (t == BencodeParser.Token.BEnd) break
            val key = (t as BencodeParser.Token.BStr).bytes.toString(Charsets.ISO_8859_1)
            map[key] = decodeNext()
        }
        return map
    }

    private fun decodeNext(): BencodeNode {
        return when (val t = it.next()) {
            is BencodeParser.Token.BStr -> BencodeNode.Str(t.bytes)
            is BencodeParser.Token.BInt -> BencodeNode.Int(t.value)
            BencodeParser.Token.BListStart -> {
                val list = mutableListOf<BencodeNode>()
                while (it.hasNext()) {
                    val next = it.next()
                    if (next == BencodeParser.Token.BEnd) break
                    it.previous()
                    list.add(decodeNext())
                }
                BencodeNode.List(list)
            }
            BencodeParser.Token.BDictStart -> BencodeNode.Dict(decodeDict())
            BencodeParser.Token.BEnd -> BencodeNode.Dict(emptyMap())
        }
    }
}

private fun <T> Iterator<T>.previous() {} // no-op put-back for end-of-list detection

private sealed class BencodeNode {
    abstract val string: String
    abstract val integer: Long
    abstract val bytes: ByteArray
    abstract val list: List<BencodeNode>
    abstract val asDict: Map<String, BencodeNode>

    class Str(val bytes_: ByteArray) : BencodeNode() {
        override val bytes: ByteArray = bytes_
        override val string: String = bytes_.toString(Charsets.ISO_8859_1)
        override val integer: Long get() = string.toLong()
        override val list: List<BencodeNode> get() = throw IllegalStateException("Not a list")
        override val asDict: Map<String, BencodeNode> get() = throw IllegalStateException("Not a dict")
    }
    class Int(val value: Long) : BencodeNode() {
        override val bytes: ByteArray get() = value.toString().toByteArray()
        override val string: String get() = value.toString()
        override val integer: Long get() = value
        override val list: List<BencodeNode> get() = throw IllegalStateException("Not a list")
        override val asDict: Map<String, BencodeNode> get() = throw IllegalStateException("Not a dict")
    }
    class List(val list_: List<BencodeNode>) : BencodeNode() {
        override val bytes: ByteArray get() = throw IllegalStateException("Not bytes")
        override val string: String get() = throw IllegalStateException("Not a string")
        override val integer: Long get() = throw IllegalStateException("Not an int")
        override val list: List<BencodeNode> get() = list_
        override val asDict: Map<String, BencodeNode> get() = throw IllegalStateException("Not a dict")
    }
    class Dict(val dict: Map<String, BencodeNode>) : BencodeNode() {
        override val bytes: ByteArray get() = throw IllegalStateException("Not bytes")
        override val string: String get() = throw IllegalStateException("Not a string")
        override val integer: Long get() = throw IllegalStateException("Not an int")
        override val list: List<BencodeNode> get() = throw IllegalStateException("Not a list")
        override val asDict: Map<String, BencodeNode> get() = dict
    }
}

// ─── Bencode Encoder (info-bytes only) ───────────────────────────────────────

object BencodeEncoder {
    /**
     * Encode the info dict to canonical bencode bytes for info-hash.
     * The info dict must be encoded in deterministic (sorted) key order.
     */
    fun encodeInfo(info: TorrentInfo): ByteArray {
        val out = StringBuilder()
        out.append("d")
        encodeStr(out, "name")
        encodeStrContent(out, info.name)

        // Piece length
        info.pieceLength?.let {
            encodeStr(out, "piece length")
            encodeInt(out, it)
        }

        // Meta version (v2 indicator)
        info.metaVersion?.let {
            encodeStr(out, "meta version")
            encodeInt(out, it.toLong())
        }

        // v1 piece hashes
        if (info.pieces.pieceHashes.isNotEmpty()) {
            encodeStr(out, "pieces")
            val hex = info.pieces.pieceHashes.joinToString("")
            encodeStrContent(out, hex)
        }

        // v2 piece hash tree root
        info.pieces.rootSha256?.let {
            encodeStr(out, "sha256")
            encodeStrContent(out, it)
        }

        // v1 single file length
        info.length?.let {
            encodeStr(out, "length")
            encodeInt(out, it)
        }

        // v2 file tree
        info.fileTree?.let { ft ->
            encodeStr(out, "file tree")
            encodeFileTree(out, ft)
        }

        // v1 multi-file file list
        info.files?.let { fl ->
            encodeStr(out, "files")
            out.append("l")
            fl.forEach { entry ->
                out.append("d")
                encodeStr(out, "length")
                encodeInt(out, entry.length)
                encodeStr(out, "path")
                out.append("l")
                entry.path.forEach { part -> encodeStrContent(out, part) }
                out.append("e")
                entry.md5sum?.let {
                    encodeStr(out, "md5sum")
                    encodeStrContent(out, it)
                }
                out.append("e")
            }
            out.append("e")
        }

        // v1 root tree (for hybrid)
        info.sha1RootTree?.let {
            encodeStr(out, "sha1 root tree")
            encodeStrContent(out, it)
        }

        info.private?.let {
            encodeStr(out, "private")
            encodeInt(out, it.toLong())
        }

        out.append("e")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun encodeStr(sb: StringBuilder, s: String) {
        sb.append(s.length).append(":").append(s)
    }

    private fun encodeStrContent(sb: StringBuilder, s: String) {
        // Bencode strings are raw bytes in UTF-8
        sb.append(s.length).append(":").append(s)
    }

    private fun encodeInt(sb: StringBuilder, n: Long) {
        sb.append("i").append(n).append("e")
    }

    private fun encodeFileTree(sb: StringBuilder, ft: FileTree) {
        sb.append("d")
        ft.root.entries.forEach { (name, node) ->
            encodeStr(sb, name)
            when (node) {
                is FileTreeNode.File -> {
                    sb.append("d")
                    encodeStr(sb, "length")
                    encodeInt(sb, node.length)
                    node.piecesRoot?.let {
                        encodeStr(sb, "pieces (v2)")
                        encodeStrContent(sb, it)
                    }
                    sb.append("e")
                }
                is FileTreeNode.Directory -> {
                    val sub = StringBuilder()
                    node.files.entries.forEach { (nk, nv) ->
                        encodeStr(sub, nk)
                        when (nv) {
                            is FileTreeNode.File -> {
                                sub.append("d")
                                encodeStr(sub, "length")
                                encodeInt(sub, nv.length)
                                nv.piecesRoot?.let {
                                    encodeStr(sub, "pieces (v2)")
                                    encodeStrContent(sub, it)
                                }
                                sub.append("e")
                            }
                            is FileTreeNode.Directory -> {
                                val sub2 = StringBuilder()
                                nv.files.entries.forEach { (nnk, nnv) ->
                                    encodeStr(sub2, nnk)
                                    when (nnv) {
                                        is FileTreeNode.File -> {
                                            sub2.append("d")
                                            encodeStr(sub2, "length")
                                            encodeInt(sub2, nnv.length)
                                            nnv.piecesRoot?.let {
                                                encodeStr(sub2, "pieces (v2)")
                                                encodeStrContent(sub2, it)
                                            }
                                            sub2.append("e")
                                        }
                                        is FileTreeNode.Directory -> {
                                            // Depth limit — encode as empty dir
                                            sub2.append("de")
                                        }
                                    }
                                }
                                sub.append("d").append(sub2).append("e")
                            }
                        }
                    }
                    sb.append("d").append(sub).append("e")
                }
            }
        }
        sb.append("e")
    }
}
