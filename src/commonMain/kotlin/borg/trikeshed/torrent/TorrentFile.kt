package borg.trikeshed.torrent

import borg.trikeshed.job.sha256
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view

/** Validated metainfo retaining the exact info-dictionary substring used by BEP 3/52. */
class TorrentFile private constructor(
    rawInfo: ByteArray,
    val info: TorrentInfo,
    val announce: String?,
    val announceList: List<List<String>>,
    val creationDate: Long?,
    val comment: String?,
    val createdBy: String?,
    val encoding: String?,
    private val v1Files: List<TorrentFileEntry>,
    private val v2Files: List<TorrentFileEntry>,
    private val v1Hashes: ByteArray?,
    layers: Map<InfoHash, ByteArray>,
    requested: InfoHash?,
) {
    private val raw = rawInfo.copyOf()
    private val layers = layers.mapValues { it.value.copyOf() }
    val v1InfoHash: InfoHash? = v1Hashes?.let { InfoHash(Sha1.digest(raw)) }
    val v2InfoHash: InfoHash? = if (info.isV2) InfoHash(sha256(raw)) else null
    val infoHash: InfoHash = requested ?: v2InfoHash ?: requireNotNull(v1InfoHash)
    val wireInfoHash: ByteArray get() = infoHash.wireBytes
    val isHybrid: Boolean get() = v1InfoHash != null && v2InfoHash != null
    val isV2Only: Boolean get() = v2InfoHash != null && v1InfoHash == null
    val pieceSize: Int = requireNotNull(info.pieceLength).toInt()
    val files: Series<TorrentFileEntry> = (if (infoHash.isV2) v2Files else v1Files).toSeries()
    val totalSize: Long = sumLengths(files.view.filter { !it.padding })
    private val v1Size = sumLengths(v1Files)
    val numPieces: Int = if (infoHash.isV2) v2Files.sumOf { pieceCount(it.length, pieceSize) }
        else pieceCount(v1Size, pieceSize)
    val pieceLayersComplete: Boolean get() = v2Files.all { it.length <= pieceSize || it.piecesRoot in layers }

    init { require(infoHash == v1InfoHash || infoHash == v2InfoHash) { "Requested torrent identity does not match metadata" } }

    fun infoBytes(): ByteArray = raw.copyOf()
    fun infoHashV1(): ByteArray = requireNotNull(v1InfoHash) { "Torrent has no v1 identity" }.bytes
    fun infoHashV2(): InfoHash = requireNotNull(v2InfoHash) { "Torrent has no v2 identity" }

    fun pieceLength(index: Int): Int {
        require(index in 0 until numPieces) { "Piece index outside torrent" }
        return if (infoHash.isV2) v2Length(index) else minOf(pieceSize.toLong(), v1Size - index.toLong() * pieceSize).toInt()
    }

    /** v2 metadata acquired by BEP 9 becomes piece-verifiable after authenticated layers arrive. */
    fun withPieceLayers(pieceLayers: Map<InfoHash, ByteArray>): TorrentFile {
        validateLayers(v2Files, pieceSize, pieceLayers, required = true)
        return TorrentFile(raw, info, announce, announceList, creationDate, comment, createdBy, encoding,
            v1Files, v2Files, v1Hashes, pieceLayers, infoHash)
    }

    /** BEP 52 piece-layer exchange; the caller owns and routes every request through its peer session. */
    suspend fun acquirePieceLayers(request: suspend (PeerHashRange) -> ByteArray): TorrentFile {
        if (pieceLayersComplete) return this
        val acquired = layers.toMutableMap()
        val base = (pieceSize / BLOCK_SIZE).countTrailingZeroBits()
        val zero = zeroHash(base)
        for (file in v2Files) {
            if (file.length <= pieceSize || file.piecesRoot in acquired) continue
            val root = requireNotNull(file.piecesRoot)
            val count = pieceCount(file.length, pieceSize)
            require(count.toLong() * 32 <= TorrentBencode.MAX_BYTES) { "Piece-layer size limit" }
            val layer = ByteArray(count * 32)
            var index = 0
            while (index < count) {
                val length = minOf(512, powerOfTwo(maxOf(2, count - index)))
                val response = request(PeerHashRange(root.bytes, base, index, length, 0))
                require(response.size == length * 32) { "Unexpected piece-layer response width" }
                val actual = minOf(length, count - index)
                response.copyInto(layer, index * 32, 0, actual * 32)
                for (pad in actual until length) require(response.copyOfRange(pad * 32, pad * 32 + 32).contentEquals(zero)) {
                    "Incorrect BEP 52 zero padding"
                }
                index += actual
            }
            acquired[root] = layer
        }
        return withPieceLayers(acquired)
    }

    /** Authenticated stored piece layers can supply their ancestors, but not unavailable block hashes. */
    fun hashes(range: PeerHashRange): ByteArray? {
        val file = v2Files.firstOrNull { it.piecesRoot?.bytes?.contentEquals(range.root) == true } ?: return null
        val stored = layers[file.piecesRoot] ?: return null
        val base = (pieceSize / BLOCK_SIZE).countTrailingZeroBits()
        if (range.baseLayer < base) return null
        val count = pieceCount(file.length, pieceSize)
        val width = powerOfTwo(count)
        val height = width.countTrailingZeroBits() + base
        if (range.baseLayer >= height || range.proofLayers > height - range.baseLayer - 1) return null
        val zero = zeroHash(base)
        var level: List<ByteArray> = List(width) { i -> if (i < count) stored.copyOfRange(i * 32, i * 32 + 32) else zero }
        repeat(range.baseLayer - base) { level = level.chunked(2).map { sha256(it[0] + it[1]) } }
        if (range.index.toLong() + range.length > level.size) return null
        val result = mutableListOf<ByteArray>()
        for (i in range.index until range.index + range.length) result += level[i]
        val covered = range.length.countTrailingZeroBits()
        var index = range.index
        repeat(covered) { level = level.chunked(2).map { sha256(it[0] + it[1]) }; index /= 2 }
        repeat(maxOf(0, range.proofLayers - covered + 1)) {
            if (level.size < 2) return null
            result += level[index xor 1]
            level = level.chunked(2).map { sha256(it[0] + it[1]) }; index /= 2
        }
        return ByteArray(result.size * 32) { result[it / 32][it % 32] }
    }

    /** Checks exact piece width and every advertised hash format, including hybrid zero padding. */
    fun verifyPiece(index: Int, data: ByteArray): Boolean {
        if (index !in 0 until numPieces || data.size != pieceLength(index)) return false
        if (v1Hashes != null) {
            val width = minOf(pieceSize.toLong(), v1Size - index.toLong() * pieceSize).toInt()
            if (data.size > width) return false
            val v1Data = if (data.size == width) data else data.copyOf(width)
            if (!Sha1.digest(v1Data).contentEquals(v1Hashes.copyOfRange(index * 20, index * 20 + 20))) return false
        }
        if (v2InfoHash != null) {
            val file = v2File(index)
            val width = v2Length(index)
            if (data.size < width || data.drop(width).any { it != 0.toByte() }) return false
            val root = requireNotNull(file.piecesRoot)
            val expected = if (file.length <= pieceSize) root.bytes else {
                val layer = layers[root] ?: return false
                val at = (index - file.firstPiece) * 32
                layer.copyOfRange(at, at + 32)
            }
            val leaves = if (file.length <= pieceSize) powerOfTwo(pieceCount(file.length, BLOCK_SIZE)) else pieceSize / BLOCK_SIZE
            if (!merkle(data.copyOf(width), leaves).contentEquals(expected)) return false
        }
        return true
    }

    private fun v2File(index: Int): TorrentFileEntry = v2Files.first {
        index >= it.firstPiece && index.toLong() < it.firstPiece.toLong() + pieceCount(it.length, pieceSize)
    }
    private fun v2Length(index: Int): Int = v2File(index).let {
        minOf(pieceSize.toLong(), it.length - (index - it.firstPiece).toLong() * pieceSize).toInt()
    }

    companion object {
        const val BLOCK_SIZE = 16_384
        const val MAX_METADATA_BYTES = 4 * 1024 * 1024

        fun parse(data: ByteArray): TorrentFile {
            val root = TorrentBencode.decode(data).dictionary()
            val info = root["info"].dictionary()
            require(info.end - info.start <= MAX_METADATA_BYTES) { "Torrent metadata size limit" }
            val raw = data.copyOfRange(info.start, info.end)
            val layers = root["piece layers"]?.dictionary()?.entries?.view?.associate { entry ->
                require(entry.a.size == 32) { "Piece-layer key must be a SHA-256 root" }
                InfoHash(entry.a) to entry.b.bytes()
            }
            return read(info, raw, root, layers, null, completeMetainfo = true)
        }

        /** BEP 9 transfers only info bytes; larger v2 files still need their piece layers. */
        fun fromInfoBytes(raw: ByteArray, expected: InfoHash, pieceLayers: Map<InfoHash, ByteArray>? = null): TorrentFile {
            require(raw.size in 1..MAX_METADATA_BYTES) { "Torrent metadata size limit" }
            val hash = if (expected.isV2) sha256(raw) else Sha1.digest(raw)
            require(hash.contentEquals(expected.bytes)) { "Torrent metadata hash mismatch" }
            return read(TorrentBencode.decode(raw).dictionary(), raw, null, pieceLayers, expected, completeMetainfo = false)
        }

        private fun read(node: BencodeValue.Dictionary, raw: ByteArray, outer: BencodeValue.Dictionary?,
                         layers: Map<InfoHash, ByteArray>?, requested: InfoHash?, completeMetainfo: Boolean): TorrentFile {
            val version = node["meta version"]?.integer()
            require(version == null || version == 2L) { "Unsupported torrent meta version $version" }
            val v2 = version == 2L
            val pieceLength = node["piece length"].integer()
            require(pieceLength in 1..Int.MAX_VALUE.toLong()) { "Invalid piece length" }
            val size = pieceLength.toInt()
            if (v2) require(size >= BLOCK_SIZE && size and (size - 1) == 0) { "v2 piece length must be a power of two >=16 KiB" }
            val name = node["name"].text()
            val v1Hashes = node["pieces"]?.bytes()
            require(v2 || v1Hashes != null) { "Missing v1 pieces" }
            if (!v2) require(node["file tree"] == null) { "File tree requires meta version 2" }
            val v1 = if (v1Hashes != null) readV1(node, name, size) else emptyList()
            if (v1Hashes != null) require(v1Hashes.size % 20 == 0 && v1Hashes.size / 20 == pieceCount(sumLengths(v1), size)) {
                "v1 pieces must contain one binary SHA-1 per logical piece"
            }
            val tree = if (v2) node["file tree"].dictionary() else null
            val v2Files = if (tree != null) readV2(tree, size) else emptyList()
            if (v2 && v1Hashes != null) validateHybrid(v1, v2Files, size)
            val privateFlag = node["private"]?.integer()?.also { require(it in 0L..1L) }
            if (v2 && completeMetainfo) require(layers != null) { "v2 metainfo requires piece layers" }
            validateLayers(v2Files, size, layers ?: emptyMap(), required = completeMetainfo || layers != null)
            val info = TorrentInfo(name, pieceLength,
                PieceLayer(v1Hashes?.asList()?.chunked(20)?.map { hash -> hash.toByteArray().hex() } ?: emptyList()),
                node["length"]?.integer(), version?.toInt(), tree?.let(::fileTree),
                if (node["files"] != null) v1 else null, node["source"]?.text(), privateFlag?.toInt())
            val tiers = outer?.get("announce-list")?.list()?.view?.map { tier -> tier.list().view.map { it.text() } } ?: emptyList()
            return TorrentFile(raw, info, outer?.get("announce")?.text(), tiers,
                outer?.get("creation date")?.integer(), outer?.get("comment")?.text(),
                outer?.get("created by")?.text(), outer?.get("encoding")?.text(), v1, v2Files, v1Hashes, layers ?: emptyMap(), requested)
        }

        private fun readV1(info: BencodeValue.Dictionary, name: String, pieceSize: Int): List<TorrentFileEntry> {
            require((info["length"] != null) xor (info["files"] != null)) { "v1 requires exactly one of length/files" }
            var offset = 0L
            val paths = mutableSetOf<List<String>>()
            fun file(length: Long, path: List<String>, padding: Boolean, md5: String?): TorrentFileEntry {
                require(length >= 0 && paths.add(path)) { "Negative length or duplicate file path" }
                require(path.isNotEmpty()); path.forEach(::pathComponent)
                val entry = TorrentFileEntry(length, path, md5, offset, (offset / pieceSize).checkedInt(), padding)
                offset = addSize(offset, length)
                return entry
            }
            if (info["length"] != null) return listOf(file(info["length"].integer(), listOf(name), false, null))
            return info["files"].list().view.map { item ->
                val entry = item.dictionary()
                file(entry["length"].integer(), entry["path"].list().view.map { it.text() },
                    entry["attr"]?.text()?.contains('p') == true, entry["md5sum"]?.text())
            }.also { files ->
                require(files.isNotEmpty()) { "Empty v1 files list" }
                val paths = files.map { it.path.joinToString("/") }.sorted()
                for (i in 1 until paths.size) require(!paths[i].startsWith(paths[i - 1] + "/")) { "File path is another file's ancestor" }
            }
        }

        private fun readV2(tree: BencodeValue.Dictionary, pieceSize: Int): List<TorrentFileEntry> {
            require(tree[""] == null) { "File-tree root cannot be a file" }
            val files = mutableListOf<TorrentFileEntry>()
            var offset = 0L
            var index = 0L
            fun visit(node: BencodeValue.Dictionary, path: List<String>) {
                val properties = node[""]
                if (properties != null) {
                    require(node.entries.size == 1 && path.isNotEmpty()) { "File has sibling tree entries" }
                    val fields = properties.dictionary()
                    val length = fields["length"].integer()
                    require(length >= 0) { "Negative v2 file length" }
                    val root = fields["pieces root"]?.bytes()
                    if (length == 0L) require(root == null) { "Empty v2 file cannot have pieces root" }
                    else require(root?.size == 32) { "Nonempty v2 file requires SHA-256 pieces root" }
                    files += TorrentFileEntry(length, path, offset = offset, firstPiece = index.checkedInt(), piecesRoot = root?.let(::InfoHash))
                    val count = pieceCount(length, pieceSize)
                    index += count; require(index <= Int.MAX_VALUE) { "Too many pieces" }
                    offset = addSize(offset, count.toLong() * pieceSize)
                } else {
                    require(node.entries.size > 0) { "Empty file-tree directory" }
                    for (entry in node.entries.view) {
                        val name = BencodeValue.Bytes(entry.a).text(); pathComponent(name)
                        visit(entry.b.dictionary(), path + name)
                    }
                }
            }
            visit(tree, emptyList())
            return files
        }

        private fun validateHybrid(v1: List<TorrentFileEntry>, v2: List<TorrentFileEntry>, size: Int) {
            val actual = v1.filter { !it.padding }
            require(actual.size == v2.size) { "Hybrid file count mismatch" }
            for (i in actual.indices) require(actual[i].path == v2[i].path && actual[i].length == v2[i].length &&
                (actual[i].length == 0L || actual[i].offset == v2[i].offset)) { "Hybrid file order, path, length or alignment mismatch" }
            for (pad in v1.filter { it.padding }) require(pad.length > 0 && pad.length < size &&
                (pad.offset + pad.length) % size == 0L) { "Invalid hybrid padding file" }
            require(pieceCount(sumLengths(v1), size) == v2.sumOf { pieceCount(it.length, size) }) { "Hybrid piece count mismatch" }
        }

        private fun validateLayers(files: List<TorrentFileEntry>, pieceSize: Int, layers: Map<InfoHash, ByteArray>, required: Boolean) {
            val expected = files.filter { it.length > pieceSize }.map { requireNotNull(it.piecesRoot) }.toSet()
            require(layers.keys.all { it in expected }) { "Unreferenced piece layer" }
            if (required) require(layers.keys == expected) { "Missing v2 piece layer" }
            for (file in files) {
                val layer = layers[file.piecesRoot] ?: continue
                val count = pieceCount(file.length, pieceSize)
                require(layer.size.toLong() == count.toLong() * 32) { "Wrong piece-layer width" }
                val zero = zeroHash((pieceSize / BLOCK_SIZE).countTrailingZeroBits())
                val hashes = MutableList(powerOfTwo(count)) { i -> if (i < count) layer.copyOfRange(i * 32, i * 32 + 32) else zero }
                require(foldHashes(hashes).contentEquals(file.piecesRoot!!.bytes)) { "Piece layer does not match file root" }
            }
        }

        private fun fileTree(node: BencodeValue.Dictionary): FileTree = FileTree(node.entries.view.associate { entry ->
            val value = entry.b.dictionary()
            val props = value[""]?.dictionary()
            BencodeValue.Bytes(entry.a).text() to if (props != null)
                FileTreeNode.File(props["length"].integer(), props["pieces root"]?.bytes()?.hex())
            else FileTreeNode.Directory(fileTree(value).root)
        })

        internal fun merkle(data: ByteArray, leaves: Int): ByteArray {
            require(leaves > 0 && leaves and (leaves - 1) == 0 && pieceCount(data.size.toLong(), BLOCK_SIZE) <= leaves)
            val hashes = MutableList(leaves) { i ->
                val start = i.toLong() * BLOCK_SIZE
                if (start >= data.size) ByteArray(32) else sha256(data.copyOfRange(start.toInt(), minOf(data.size.toLong(), start + BLOCK_SIZE).toInt()))
            }
            return foldHashes(hashes)
        }
        private fun foldHashes(hashes: MutableList<ByteArray>): ByteArray {
            var count = hashes.size
            while (count > 1) {
                for (i in 0 until count / 2) hashes[i] = sha256(hashes[i * 2] + hashes[i * 2 + 1])
                count /= 2
            }
            return hashes[0]
        }
        private fun zeroHash(layer: Int): ByteArray {
            var hash = ByteArray(32)
            repeat(layer) { hash = sha256(hash + hash) }
            return hash
        }
        private fun pathComponent(value: String) {
            require(value.isNotEmpty() && value != "." && value != ".." && value.none { it == '/' || it == '\\' || it == '\u0000' }) {
                "Unsupported unsafe torrent path component"
            }
        }
        private fun pieceCount(length: Long, size: Int): Int = (length / size + if (length % size == 0L) 0 else 1).checkedInt()
        private fun powerOfTwo(value: Int): Int {
            require(value in 1..(1 shl 30)) { "Merkle tree size limit" }
            var result = 1
            while (result < value) result = result shl 1
            return result
        }
        private fun addSize(a: Long, b: Long): Long { require(b >= 0 && a <= Long.MAX_VALUE - b) { "Torrent size overflow" }; return a + b }
        private fun sumLengths(files: Iterable<TorrentFileEntry>): Long = files.fold(0L) { size, file -> addSize(size, file.length) }
        private fun Long.checkedInt(): Int { require(this in 0..Int.MAX_VALUE.toLong()) { "Torrent index overflow" }; return toInt() }
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
    val source: String? = null,
    val private: Int? = null,
) {
    val isV2: Boolean get() = metaVersion == 2
    val v2PieceSize: Long get() = requireNotNull(pieceLength)
}
data class PieceLayer(val pieceHashes: List<String> = emptyList())
data class FileTree(val root: Map<String, FileTreeNode>)
sealed class FileTreeNode {
    data class File(val length: Long, val piecesRoot: String? = null) : FileTreeNode()
    data class Directory(val files: Map<String, FileTreeNode>) : FileTreeNode()
}
data class TorrentFileEntry(
    val length: Long,
    val path: List<String>,
    val md5sum: String? = null,
    val offset: Long = 0,
    val firstPiece: Int = 0,
    val padding: Boolean = false,
    val piecesRoot: InfoHash? = null,
)
object BencodeParser { fun parse(data: ByteArray): TorrentFile = TorrentFile.parse(data) }
internal fun BencodeValue?.dictionary(): BencodeValue.Dictionary = this as? BencodeValue.Dictionary ?: throw IllegalArgumentException("Expected bencode dictionary")
internal fun BencodeValue?.bytes(): ByteArray = (this as? BencodeValue.Bytes)?.bytes ?: throw IllegalArgumentException("Expected bencode bytes")
internal fun BencodeValue?.integer(): Long = (this as? BencodeValue.Integer)?.value ?: throw IllegalArgumentException("Expected bencode integer")
internal fun BencodeValue?.list(): Series<BencodeValue> = (this as? BencodeValue.ListValue)?.values ?: throw IllegalArgumentException("Expected bencode list")
internal fun BencodeValue?.text(): String = (this as? BencodeValue.Bytes)?.text() ?: throw IllegalArgumentException("Expected bencode text")
internal fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
