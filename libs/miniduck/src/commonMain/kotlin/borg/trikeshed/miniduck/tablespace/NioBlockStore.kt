package borg.trikeshed.miniduck.tablespace

import borg.trikeshed.miniduck.BlockRowVec
import borg.trikeshed.miniduck.MiniDuckBlockCodec
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.coroutines.currentCoroutineContext

/**
 * Persistent [BlockStore] backed by [FileOperations] NIO SPI.
 *
 * Stores each block as an NDJSON file:
 *   {root}/{collection}/{blockId}.ndjson
 *
 * All IO goes through [FileOperations] retrieved from coroutine context.
 * Portable across JVM, JS, WASM, and POSIX.
 */
class NioBlockStore(
    private val root: CharSequence,
) : BlockStore {

    private suspend fun fs(): FileOperations =
        currentCoroutineContext()[FileOperations.Key]
            ?: throw IllegalStateException("FileOperations not in coroutine context")

    override suspend fun put(collection: CharSequence, block: BlockRowVec): CharSequence? {
        val id = nextId(collection)
        putWithId(collection, id, block)
        return id
    }

    override suspend fun putWithId(collection: CharSequence, id: CharSequence, block: BlockRowVec) {
        ensureDir(collection)
        val path = blockPath(collection, id)
        val encoded = MiniDuckBlockCodec.encode(block)
        fs().write(path, encoded)
    }

    override suspend fun remove(collection: CharSequence, blockId: CharSequence) {
        val path = blockPath(collection, blockId)
        if (fs().exists(path)) fs().deleteRecursively(path)
    }

    override suspend fun get(collection: CharSequence, blockId: CharSequence): BlockRowVec? {
        val path = blockPath(collection, blockId)
        if (!fs().exists(path) || !fs().isFile(path)) return null
        val text = fs().readString(path).toString()
        return MiniDuckBlockCodec.decode(text)
    }

    override suspend fun list(collection: CharSequence): List<CharSequence> {
        val dir = collectionDir(collection)
        if (!fs().isDir(dir)) return emptyList()
        return fs().listDir(dir)
            .filter { it.endsWith(".ndjson") }
            .map { it.removeSuffix(".ndjson") }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private suspend fun collectionDir(collection: CharSequence): CharSequence =
        fs().resolvePath(root, collection).toString()

    private suspend fun blockPath(collection: CharSequence, id: CharSequence): CharSequence =
        fs().resolvePath(collectionDir(collection), "$id.ndjson").toString()

    private suspend fun ensureDir(collection: CharSequence) {
        val dir = collectionDir(collection)
        if (!fs().isDir(dir)) fs().mkdirs(dir)
    }

    private suspend fun nextId(collection: CharSequence): CharSequence {
        val existing = list(collection)
        val maxN = existing.mapNotNull { it.toString().toIntOrNull() }.maxOrNull() ?: -1
        return (maxN + 1).toString()
    }
}
