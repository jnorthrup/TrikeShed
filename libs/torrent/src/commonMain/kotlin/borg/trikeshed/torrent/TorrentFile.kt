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
        /** Parse a .torrent file from raw bencode bytes. */
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

    private fun parseTorrent(map: Map<String, Node>): TorrentFile {
        val announceNode = map["announce"] as? Node.Str
        val announceListNode = map["announce-list"] as? Node.BList
        val creationDateNode = map["creation date"] as? Node.Int
        val commentNode = map["comment"] as? Node.Str
        val createdByNode = map["created by"] as? Node.Str
        val encodingNode = map["encoding"] as? Node.Str
        val infoNode = map["info"] as? Node.Dict
        val announceList: List<List<String>> = if (announceListNode != null) {
            announceListNode.items?.mapNotNull { tier ->
                (tier as? Node.BList)?.items?.mapNotNull { (it as? Node.Str)?.value }
            } ?: emptyList()
        } else emptyList()
        return TorrentFile(
            announce = announceNode?.value,
            announceList = announceList,
            creationDate = creationDateNode?.intValue,
            comment = commentNode?.value,
            createdBy = createdByNode?.value,
            encoding = encodingNode?.value,
            info = parseInfo(infoNode?.entries ?: emptyMap()),
        )
    }

    private fun parseInfo(map: Map<String, Node>): TorrentInfo {
        val nameNode = map["name"] as? Node.Str
        val pieceLengthNode = map["piece length"] as? Node.Int
        val lengthNode = map["length"] as? Node.Int
        val metaVersionNode = map["meta version"] as? Node.Int
        val fileTreeNode = map["file tree"] as? Node.Dict
        val filesNode = map["files"] as? Node.BList
        val sha1RootTreeNode = map["sha1 root tree"] as? Node.Str
        val sourceNode = map["source"] as? Node.Str
        val privateNode = map["private"] as? Node.Int
        return TorrentInfo(
            name = nameNode?.value ?: "unknown",
            pieceLength = pieceLengthNode?.intValue,
            pieces = parsePieceLayer(map),
            length = lengthNode?.intValue,
            metaVersion = metaVersionNode?.intValue?.toInt(),
            fileTree = fileTreeNode?.let { parseFileTree(it.entries ?: emptyMap()) },
            files = filesNode?.items?.mapNotNull { parseFileEntry(it) },
            sha1RootTree = sha1RootTreeNode?.value,
            source = sourceNode?.value,
            private = privateNode?.intValue?.toInt(),
        )
    }

    private fun parsePieceLayer(map: Map<String, Node>): PieceLayer {
        val hex = (map["pieces"] as? Node.Str)?.value
        val v1Hashes = if (hex != null && hex.length % 40 == 0) {
            (0 until hex.length step 40).map { hex.substring(it, it + 40) }
        } else emptyList()
        return PieceLayer(v1Hashes, (map["sha256"] as? Node.Str)?.value)
    }

    private fun parseFileTree(entries: Map<String, Node>): FileTree {
        return FileTree(entries.mapValues { parseFileTreeNode(it.value) })
    }

    private fun parseFileTreeNode(node: Node): FileTreeNode {
        val dictNode = node as? Node.Dict
        val dict = dictNode?.entries ?: return FileTreeNode.Directory(emptyMap())
        return when {
            dict.containsKey("length") -> {
                val lenNode = dict["length"] as? Node.Int
                val piecesRootNode = dict["pieces (v2)"] as? Node.Str
                FileTreeNode.File(
                    length = lenNode?.intValue ?: 0L,
                    piecesRoot = piecesRootNode?.value,
                )
            }
            else -> FileTreeNode.Directory(
                dict.filterKeys { it != "pieces (v2)" }.mapValues { parseFileTreeNode(it.value) }
            )
        }
    }

    private fun parseFileEntry(node: Node): TorrentFileEntry? {
        val dictNode = node as? Node.Dict
        val dict = dictNode?.entries ?: return null
        val pathNode = dict["path"] as? Node.BList
        val lenNode = dict["length"] as? Node.Int
        val md5sumNode = dict["md5sum"] as? Node.Str
        return TorrentFileEntry(
            length = lenNode?.intValue ?: return null,
            path = pathNode?.items?.mapNotNull { (it as? Node.Str)?.value } ?: emptyList(),
            md5sum = md5sumNode?.value,
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
        while (pos < data.size) tokens.add(nextToken())
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
            'l' -> Token.BListStart
            'd' -> Token.BDictStart
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

// ─── Bencode Decoder ──────────────────────────────────────────────────────────

private class Decoder(private val it: Iterator<Tokenizer.Token>) {
    fun decodeDict(): Map<String, Node> {
        val map = LinkedHashMap<String, Node>()
        while (it.hasNext()) {
            val t = it.next()
            if (t == Tokenizer.Token.BEnd) break
            val key = (t as Tokenizer.Token.BStr).bytes.toString(Charsets.ISO_8859_1)
            map[key] = decodeNext()
        }
        return map
    }

    private fun decodeNext(): Node {
        return when (val t = it.next()) {
            is Tokenizer.Token.BStr -> Node.Str(t.bytes)
            is Tokenizer.Token.BInt -> Node.Int(t.value)
            Tokenizer.Token.BListStart -> {
                val items = mutableListOf<Node>()
                while (it.hasNext()) {
                    val next = it.next()
                    if (next == Tokenizer.Token.BEnd) break
                    it.previous()
                    items.add(decodeNext())
                }
                Node.BList(items)
            }
            Tokenizer.Token.BDictStart -> Node.Dict(decodeDict())
            Tokenizer.Token.BEnd -> Node.Dict(emptyMap())
        }
    }
}

private fun <T> Iterator<T>.previous() {} // no-op put-back

// ─── Bencode Node Types ───────────────────────────────────────────────────────

private sealed class Node {
    abstract val value: String?
    abstract val intValue: Long?
    abstract val items: List<Node>?
    abstract val entries: Map<String, Node>?

    class Str(val bytes: ByteArray) : Node() {
        override val value: String? = bytes.toString(Charsets.ISO_8859_1)
        override val intValue: Long? = value?.toLongOrNull()
        override val items: List<Node>? get() = null
        override val entries: Map<String, Node>? get() = null
    }
    class Int(val intVal: Long) : Node() {
        override val value: String? get() = intVal.toString()
        override val intValue: Long? get() = intVal
        override val items: List<Node>? get() = null
        override val entries: Map<String, Node>? get() = null
    }
    class BList(val items_: List<Node>) : Node() {
        override val value: String? get() = null
        override val intValue: Long? get() = null
        override val items: List<Node>? = items_
        override val entries: Map<String, Node>? get() = null
    }
    class Dict(val entries_: Map<String, Node>) : Node() {
        override val value: String? get() = null
        override val intValue: Long? get() = null
        override val items: List<Node>? get() = null
        override val entries: Map<String, Node>? = entries_
    }
}

// ─── Bencode Encoder (info-bytes only) ───────────────────────────────────────

object BencodeEncoder {
    /**
     * Encode the info dict to canonical bencode bytes for info-hash.
     * Keys must be encoded in sorted order for determinism.
     */
    fun encodeInfo(info: TorrentInfo): ByteArray {
        val out = StringBuilder()
        out.append("d")
        encodeStr(out, "name")
        encodeStrContent(out, info.name)

        info.pieceLength?.let { encodeInt(out, "piece length", it) }
        info.metaVersion?.let { encodeInt(out, "meta version", it.toLong()) }

        if (info.pieces.pieceHashes.isNotEmpty()) {
            encodeStr(out, "pieces")
            encodeStrContent(out, info.pieces.pieceHashes.joinToString(""))
        }
        info.pieces.rootSha256?.let { encodeStr(out, "sha256"); encodeStrContent(out, it) }
        info.length?.let { encodeInt(out, "length", it) }
        info.fileTree?.let { ft ->
            encodeStr(out, "file tree")
            encodeFileTree(out, ft)
        }
        info.files?.let { fl ->
            encodeStr(out, "files")
            out.append("l")
            fl.forEach { entry ->
                out.append("d")
                encodeStr(out, "length"); encodeInt(out, entry.length)
                encodeStr(out, "path")
                out.append("l")
                entry.path.forEach { encodeStrContent(out, it) }
                out.append("e")
                entry.md5sum?.let { encodeStr(out, "md5sum"); encodeStrContent(out, it) }
                out.append("e")
            }
            out.append("e")
        }
        info.sha1RootTree?.let { encodeStr(out, "sha1 root tree"); encodeStrContent(out, it) }
        info.private?.let { encodeInt(out, "private", it.toLong()) }

        out.append("e")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun encodeStr(sb: StringBuilder, s: String) {
        sb.append(s.length).append(":").append(s)
    }

    private fun encodeStrContent(sb: StringBuilder, s: String) {
        sb.append(s.length).append(":").append(s)
    }

    private fun encodeInt(sb: StringBuilder, n: Long) {
        sb.append("i").append(n).append("e")
    }

    private fun encodeInt(sb: StringBuilder, key: String, n: Long) {
        encodeStr(sb, key)
        sb.append("i").append(n).append("e")
    }

    private fun encodeFileTree(sb: StringBuilder, ft: FileTree) {
        sb.append("d")
        ft.root.entries.forEach { (name, node) ->
            encodeStr(sb, name)
            when (node) {
                is FileTreeNode.File -> {
                    sb.append("d6:lengthi").append(node.length).append("ee")
                }
                is FileTreeNode.Directory -> {
                    val sub = StringBuilder()
                    node.files.entries.forEach { (nk, nv) ->
                        encodeStr(sub, nk)
                        when (nv) {
                            is FileTreeNode.File -> {
                                sub.append("d6:lengthi").append(nv.length).append("ee")
                            }
                            is FileTreeNode.Directory -> {
                                val sub2 = StringBuilder()
                                nv.files.entries.forEach { (nnk, nnv) ->
                                    encodeStr(sub2, nnk)
                                    when (nnv) {
                                        is FileTreeNode.File -> {
                                            sub2.append("d6:lengthi").append(nnv.length).append("ee")
                                        }
                                        is FileTreeNode.Directory -> {
                                            sub2.append("de") // depth limit: empty dir
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
