package borg.trikeshed.miniduck.tablespace

import borg.trikeshed.miniduck.BlockRowVec
import borg.trikeshed.miniduck.MiniDuckBlockCodec
import borg.trikeshed.userspace.nio.file.spi.FileOperations

/**
 * Persistent [BlockStore] backed by [FileOperations] NIO SPI.
 *
 * Stores each block as an NDJSON file:
 *   {root}/{collection}/{blockId}.ndjson
 *
 * All IO goes through [FileOperations] — no java.nio.file dependency.
 * Portable across JVM, JS, WASM, and POSIX.
 */
class NioBlockStore(
    private val root: String,
    private val fs: FileOperations,
) : BlockStore {

    override fun put(collection: String, block: BlockRowVec): String? {
        val id = nextId(collection)
        putWithId(collection, id, block)
        return id
    }

    override fun putWithId(collection: String, id: String, block: BlockRowVec) {
        ensureDir(collection)
        val path = blockPath(collection, id)
        val encoded = MiniDuckBlockCodec.encode(block)
        fs.write(path, encoded)
    }

    override fun remove(collection: String, blockId: String) {
        val path = blockPath(collection, blockId)
        if (fs.exists(path)) fs.deleteRecursively(path)
    }

    override fun get(collection: String, blockId: String): BlockRowVec? {
        val path = blockPath(collection, blockId)
        if (!fs.exists(path) || !fs.isFile(path)) return null
        val text = fs.readString(path)
        return MiniDuckBlockCodec.decode(text)
    }

    override fun list(collection: String): List<String> {
        val dir = collectionDir(collection)
        if (!fs.isDir(dir)) return emptyList()
        return fs.listDir(dir)
            .filter { it.endsWith(".ndjson") }
            .map { it.removeSuffix(".ndjson") }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun collectionDir(collection: String): String =
        fs.resolvePath(root, collection)

    private fun blockPath(collection: String, id: String): String =
        fs.resolvePath(collectionDir(collection), "$id.ndjson")

    private fun ensureDir(collection: String) {
        val dir = collectionDir(collection)
        if (!fs.isDir(dir)) fs.mkdirs(dir)
    }

    private fun nextId(collection: String): String {
        val existing = list(collection)
        val maxN = existing.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: -1
        return (maxN + 1).toString()
    }
}
