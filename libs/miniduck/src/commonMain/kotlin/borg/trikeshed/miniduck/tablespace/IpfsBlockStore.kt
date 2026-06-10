package borg.trikeshed.miniduck.tablespace

import borg.trikeshed.ipfs.BlockStore as IpfsBlockStoreApi
import borg.trikeshed.ipfs.CID
import borg.trikeshed.lib.j
import borg.trikeshed.miniduck.BlockRowVec
import borg.trikeshed.miniduck.MiniDuckBlockCodec
import java.security.MessageDigest

/**
 * IpfsBlockStore — miniduck BlockStore SPI backed by IPFS content-addressed storage.
 *
 * Architecture:
 * - Each miniduck block (BlockRowVec) is encoded via MiniDuckBlockCodec to NDJSON bytes
 * - Block CID = SHA-256(encodedBytes) as raw bytes (CIDv1 raw multihash)
 * - Collection index: separate index block per collection, mapping blockId → CID
 *   Index CID is deterministic: SHA-256(collectionName + "index")
 * - All IPFS ops route through reactor via IpfsElement (CCEK)
 *
 * PRELOAD.md contract: cold Series α-projection, Join algebra, structured CCEK fanout.
 */
class IpfsBlockStore(
    private val ipfsStore: IpfsBlockStoreApi,
    private val ipfsElement: borg.trikeshed.ipfs.IpfsElement,
) : BlockStore {

    private val indexCache = mutableMapOf<String, IndexBlock>()

    private data class IndexBlock(
        val collection: String,
        val entries: MutableMap<String, CID>, // blockId -> CID
    ) {
        fun toBytes(): ByteArray {
            val lines = buildList<String> {
                add(linkedMapOf("kind" to "IpfsIndex", "collection" to collection).toJsonString())
                entries.forEach { (blockId, cid) ->
                    add(linkedMapOf(
                        "blockId" to blockId,
                        "cid" to cid.bytes.joinToString("") { "%02x".format(it) }
                    ).toJsonString())
                }
            }
            return lines.joinToString("\n").encodeToByteArray()
        }

        companion object {
            fun fromBytes(collection: String, data: ByteArray): IndexBlock {
                val lines = String(data).lineSequence().filter { it.isNotBlank() }.toList()
                val entries = mutableMapOf<String, CID>()
                lines.drop(1).forEach { line ->
                    val map = parseJsonMap(line)
                    val blockId = map["blockId"] as String
                    val cidHex = map["cid"] as String
                    val cidBytes = hexToBytes(cidHex)
                    entries[blockId] = CID(cidBytes)
                }
                return IndexBlock(collection, entries)
            }
        }
    }

    /** Compute deterministic CID for a collection's index block. */
    private fun indexCid(collection: String): CID {
        val digest = MessageDigest.getInstance("SHA-256").digest(("ipfs-index:$collection").encodeToByteArray())
        return CID(digest)
    }

    /** Compute CID from block content (SHA-256 of encoded bytes). */
    private fun contentCid(bytes: ByteArray): CID {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return CID(digest)
    }

    /** Load index block from IPFS, caching locally. */
    private suspend fun loadIndex(collection: String): IndexBlock {
        return indexCache.getOrPut(collection) {
            val cid = indexCid(collection)
            val data = ipfsElement.get(cid) ?: return@getOrPut IndexBlock(collection, mutableMapOf())
            IndexBlock.fromBytes(collection, data)
        }
    }

    /** Persist index block to IPFS and update cache. */
    private suspend fun persistIndex(index: IndexBlock) {
        val cid = indexCid(index.collection)
        val data = index.toBytes()
        ipfsElement.putBlock(cid, data)
        indexCache[index.collection] = index
    }

    override suspend fun put(collection: String, block: BlockRowVec): String {
        check(block.state == BlockRowVec.State.SEALED) { "Block must be sealed before put" }

        // Encode block to bytes
        val encoded = MiniDuckBlockCodec.encode(block).encodeToByteArray()
        val cid = contentCid(encoded)

        // Store block in IPFS
        ipfsElement.putBlock(cid, encoded)

        // Generate blockId (short prefix of CID hex)
        val blockId = cid.bytes.take(8).joinToString("") { "%02x".format(it) }

        // Update collection index
        val index = loadIndex(collection)
        index.entries[blockId] = cid
        persistIndex(index)

        return blockId
    }

    override suspend fun get(collection: String, blockId: String): BlockRowVec? {
        val index = loadIndex(collection)
        val cid = index.entries[blockId] ?: return null
        val data = ipfsElement.get(cid) ?: return null
        return try {
            MiniDuckBlockCodec.decode(String(data))
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun list(collection: String): List<String> {
        val index = loadIndex(collection)
        return index.entries.keys.toList()
    }

    override suspend fun remove(collection: String, blockId: String): Boolean {
        val index = loadIndex(collection)
        val removed = index.entries.remove(blockId) != null
        if (removed) persistIndex(index)
        return removed
    }

    companion object {
        /** Factory: create IpfsBlockStore with default DiskBlockStore + IpfsElement. */
        @JvmStatic
        fun create(ipfsElement: borg.trikeshed.ipfs.IpfsElement): IpfsBlockStore {
            return IpfsBlockStore(ipfsElement.blockStore, ipfsElement)
        }
    }
}

/** JSON helpers (shared with MiniDuckBlockCodec pattern) */
private fun String.toJsonString(): String = when (this) {
    // Handled by MiniDuckBlockCodec's extension
    else -> this
}

private fun Map<*, *>.toJsonString(): String = buildString {
    append('{')
    var first = true
    for ((k, v) in this) {
        if (!first) append(',')
        first = false
        append('"')
        append(k.toString())
        append("\":")
        append(v.toJsonString())
    }
    append('}')
}

private fun List<*>.toJsonString(): String = buildString {
    append('[')
    var first = true
    for (elem in this) {
        if (!first) append(',')
        first = false
        append(elem.toJsonString())
    }
    append(']')
}

private fun Any?.toJsonString(): String = when (this) {
    null -> "null"
    is Boolean -> toString()
    is Number -> toString()
    is String -> buildString { append('"'); append(this@toJsonString); append('"') }
    is Map<*, *> -> toJsonString()
    is List<*> -> toJsonString()
    is ByteArray -> map { it.toInt() }.toJsonString()
    else -> "\"$this\""
}

private fun parseJsonMap(line: String): Map<String, Any?> {
    // Minimal JSON parser for our index format
    val map = mutableMapOf<String, Any?>()
    // Strip braces
    val content = line.trim()
    require(content.startsWith("{") && content.endsWith("}")) { "Not a JSON object: $line" }
    val inner = content.substring(1, content.length - 1)
    inner.split(",").forEach { pair ->
        val parts = pair.split(":", limit = 2)
        if (parts.size == 2) {
            val key = parts[0].trim().trimSurrounding('"')
            val value = parts[1].trim()
            map[key] = when {
                value == "null" -> null
                value == "true" -> true
                value == "false" -> false
                value.startsWith("\"") && value.endsWith("\"") -> value.substring(1, value.length - 1)
                else -> value.toIntOrNull() ?: value.toLongOrNull() ?: value
            }
        }
    }
    return map
}

private fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "Invalid hex length" }
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

private fun String.trimSurrounding(c: Char): String {
    var s = this
    while (s.startsWith(c.toString())) s = s.substring(1)
    while (s.endsWith(c.toString())) s = s.substring(0, s.length - 1)
    return s
}