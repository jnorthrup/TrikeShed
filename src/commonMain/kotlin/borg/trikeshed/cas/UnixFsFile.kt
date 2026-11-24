package borg.trikeshed.cas

import borg.trikeshed.ipns.IpnsCid
import borg.trikeshed.ipns.IpnsEncoding
import borg.trikeshed.ipns.IpnsProtobuf
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.job.sha256
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.util.toLowerHex

/**
 * A file as IPFS adds it with CIDv1 by default (`ipfs add --cid-version=1`): 256 KiB raw leaves (codec 0x55) under
 * balanced dag-pb UnixFS nodes (codec 0x70) of at most 174 links each; a file of one chunk is its leaf. A block's
 * CID digest is the sha-256 of its bytes, so it is a CAS block under the ContentId of the same digest.
 */
object UnixFsFile {
    const val CHUNK = 262_144
    const val LINKS = 174
    const val RAW: ULong = 0x55u
    const val DAG_PB: ULong = 0x70u

    /** A block of a file DAG: its CID and bytes, the file bytes under it, and the bytes of its whole subtree (a link's Tsize). */
    class Block(val cid: IpnsCid, val bytes: ByteArray, val fileSize: Long, val treeSize: Long)

    /** Every block of [content], leaves first, then each level of nodes; the root is last. */
    fun blocks(content: ByteArray): List<Block> {
        val all = mutableListOf<Block>()
        var level = (0 until maxOf(1, (content.size + CHUNK - 1) / CHUNK)).map { k ->
            val leaf = content.copyOfRange(k * CHUNK, minOf(content.size, (k + 1) * CHUNK))
            Block(IpnsCid(RAW, IpnsEncoding.multihash(0x12u, sha256(leaf))), leaf, leaf.size.toLong(), leaf.size.toLong())
        }
        all += level
        while (level.size > 1) {
            level = level.chunked(LINKS).map(::node)
            all += level
        }
        return all
    }

    /** A dag-pb node over [children]: its links first (Hash, an empty Name, Tsize), then its UnixFS file data. */
    fun node(children: List<Block>): Block {
        val size = children.sumOf { it.fileSize }
        val data = IpnsProtobuf.uint(1, 2u) + IpnsProtobuf.uint(3, size.toULong()) +
            children.fold(ByteArray(0)) { bytes, child -> bytes + IpnsProtobuf.uint(4, child.fileSize.toULong()) }
        val links = children.fold(ByteArray(0)) { bytes, child ->
            bytes + IpnsProtobuf.bytes(2, IpnsProtobuf.bytes(1, child.cid.bytes) + IpnsProtobuf.bytes(2, ByteArray(0)) + IpnsProtobuf.uint(3, child.treeSize.toULong()))
        }
        val bytes = links + IpnsProtobuf.bytes(1, data)
        return Block(IpnsCid(DAG_PB, IpnsEncoding.multihash(0x12u, sha256(bytes))), bytes, size, bytes.size + children.sumOf { it.treeSize })
    }

    /** The ContentId of the CAS block a sha-256 CID names. */
    fun contentId(cid: IpnsCid): ContentId {
        val (code, digest) = IpnsEncoding.multihashParts(cid.multihash)
        require(code == 0x12uL && digest.size == 32) { "Only sha2-256 CIDs name CAS blocks" }
        return ContentId("sha256:" + digest.toLowerHex())
    }

    /** The file under [root], block by block from [cas] (each checked against its digest there); null when a block is absent. */
    fun read(cas: CasStore, root: IpnsCid): ByteArray? {
        val bytes = cas.get(contentId(root)) ?: return null
        if (root.codec == RAW) return bytes
        val size = file(root, bytes).a
        require(size <= Int.MAX_VALUE.toULong()) { "UnixFS file exceeds one array" }
        val out = ByteArray(size.toInt())
        return if (fill(cas, root, bytes, out, 0) == out.size) out else null
    }

    /** A dag-pb file node's size and links. */
    fun file(cid: IpnsCid, bytes: ByteArray): Join<ULong, List<IpnsCid>> {
        require(cid.codec == DAG_PB) { "Not a UnixFS file block: codec ${cid.codec}" }
        val fields = IpnsProtobuf.decode(bytes, maxBytes = 1 shl 20, maxFields = LINKS + 1)
        val data = IpnsProtobuf.decode(fields.single { it.number == 1 }.bytes, maxBytes = 1 shl 20, maxFields = LINKS + 3)
        require(data.first { it.number == 1 }.integer == 2uL) { "Not a UnixFS file node" }
        return data.first { it.number == 3 }.integer j fields.filter { it.number == 2 }.map { link ->
            IpnsCid.fromBytes(IpnsProtobuf.decode(link.bytes, maxFields = 3).first { it.number == 1 }.bytes)
        }
    }

    /** Writes the file bytes under [cid] into [out] at [at]; returns the offset after them, or -1 when a block is absent. */
    fun fill(cas: CasStore, cid: IpnsCid, bytes: ByteArray, out: ByteArray, at: Int): Int {
        if (cid.codec == RAW) {
            check(at + bytes.size <= out.size) { "UnixFS leaves exceed the file size" }
            bytes.copyInto(out, at); return at + bytes.size
        }
        val (size, links) = file(cid, bytes)
        var position = at
        for (link in links) {
            position = fill(cas, link, cas.get(contentId(link)) ?: return -1, out, position)
            if (position < 0) return -1
        }
        check((position - at).toULong() == size) { "UnixFS file size differs from its blocks" }
        return position
    }
}
