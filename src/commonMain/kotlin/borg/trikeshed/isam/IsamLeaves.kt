@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package borg.trikeshed.isam

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.*
import borg.trikeshed.tilting.zran.*
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.OpenOption
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean

/** btrfs nodesize: each leaf is one zstd frame of whole rows. */
const val ISAM_LEAF_BYTES: Int = 16384

/** Skippable frame between the last leaf and the seek table: per-leaf min/max of each fixed-width column. */
const val LEAF_FENCE_MAGIC: Int = 0x184D2A5D

/** Fence header: magic, size, frames, column count, then one fenced flag per column. */
const val LEAF_FENCE_HEAD: Int = 14

/** Positioned read of exactly dst.size bytes. */
typealias IsamRead = (Long, ByteArray) -> Unit

fun fenced(column: RecordMeta): Boolean = column.type.networkSize != null

fun width(column: RecordMeta): Int = column.end - column.begin

/**
 * A meta-v2 group file stored as zstd seekable leaves of whole rows, then a leaf fence frame, then
 * the seek table. A row read decodes one leaf; `zstd -d` of the file yields the plain group file.
 * Reads are idempotent: each decode fills a fresh leaf, published whole and never written after.
 */
class IsamLeaves(val read: IsamRead, val table: SeekTable, val rowBytes: Int, val columns: List<RecordMeta>, val fence: ByteArray?) {
    val rows: Int
    val decoder = ZstdDecoder()
    /** Claimed by one decode at a time; a decode that finds it claimed brings its own decoder. */
    val decoderHeld = AtomicBoolean(false)
    /** The last decoded leaf: its index and content. */
    @Volatile var image: Join<Int, ByteArray>? = null
    val fenceAt = IntArray(columns.size) { -1 }
    val fenceStride: Int

    init {
        require(table.contentBytes % rowBytes == 0L && table.contentBytes / rowBytes <= Int.MAX_VALUE) { "ISAM leaves hold an incomplete row" }
        for (k in 0 until table.frames) require((table.content[k + 1] - table.content[k]) % rowBytes == 0L) { "ISAM leaf $k splits a row" }
        rows = (table.contentBytes / rowBytes).toInt()
        var stride = 0
        if (fence != null) {
            require(fence.le32(8) == table.frames && fence.le16(12) == columns.size) { "ISAM leaf fence disagrees with its seek table" }
            for (c in columns.indices) if (fence.u8(LEAF_FENCE_HEAD + c) != 0) { fenceAt[c] = stride; stride += 2 * width(columns[c]) }
            require(fence.size == LEAF_FENCE_HEAD + columns.size + stride * table.frames) { "ISAM leaf fence size" }
        }
        fenceStride = stride
    }

    /** Content of leaf [k]. */
    fun load(k: Int): ByteArray {
        val held = image
        if (held != null && held.a == k) return held.b
        val size = (table.content[k + 1] - table.content[k]).toInt()
        val packed = ByteArray((table.packed[k + 1] - table.packed[k]).toInt())
        read(table.packed[k], packed)
        val leaf = ByteArray(size)
        if (decoderHeld.compareAndSet(false, true)) try {
            decoder.decodeInto(packed, 0, packed.size, leaf, 0, size)
        } finally { decoderHeld.store(false) }
        else ZstdDecoder().decodeInto(packed, 0, packed.size, leaf, 0, size)
        image = k j leaf
        return leaf
    }

    fun row(r: Int, dst: ByteArray) {
        val at = r.toLong() * rowBytes
        val held = image
        val hk = held?.a ?: -1
        val k = if (hk >= 0 && at >= table.content[hk] && at < table.content[hk + 1]) hk else table.frameOf(at)
        val leaf = if (k == hk) held!!.b else load(k)
        val from = (at - table.content[k]).toInt()
        leaf.copyInto(dst, 0, from, from + rowBytes)
    }

    fun leafRows(k: Int): IntRange = (table.content[k] / rowBytes).toInt() until (table.content[k + 1] / rowBytes).toInt()

    /** Decoded (min, max) of the group's column [c] in leaf [k]; null when that column carries no fence. */
    fun bounds(k: Int, c: Int): Twin<Any?>? {
        val f = fence ?: return null
        val at = fenceAt[c]
        if (at < 0) return null
        val w = width(columns[c])
        val base = LEAF_FENCE_HEAD + columns.size + k * fenceStride + at
        val decode = columns[c].decoder
        return decode(f.copyOfRange(base, base + w)) j decode(f.copyOfRange(base + w, base + 2 * w))
    }
}

/** Opens [size] bytes read through [read] as leaves; null when they are a plain group file. */
fun openLeaves(read: IsamRead, size: Long, rowBytes: Int, columns: List<RecordMeta>): IsamLeaves? {
    if (size < 8 + SEEK_TABLE_FOOTER) return null
    val footer = ByteArray(SEEK_TABLE_FOOTER).also { read(size - SEEK_TABLE_FOOTER, it) }
    val length = seekTableLength(footer)
    if (length < 0 || length > size) return null
    val table = parseSeekTable(ByteArray(length.toInt()).also { read(size - length, it) }) ?: return null
    val gap = size - length - table.packedBytes
    if (gap < 0) return null
    if (table.frames > 0) require(ByteArray(4).also { read(0, it) }.le32(0) == ZSTD_MAGIC) { "ISAM leaf 0 is not a zstd frame" }
    var fence: ByteArray? = null
    if (gap > 0) {
        fence = ByteArray(gap.toInt()).also { read(table.packedBytes, it) }
        require(gap >= LEAF_FENCE_HEAD && fence.le32(0) == LEAF_FENCE_MAGIC && fence.le32(4).toLong() == gap - 8) {
            "ISAM leaves carry an unknown frame before their seek table"
        }
    }
    return IsamLeaves(read, table, rowBytes, columns, fence)
}

/** Writes one group's columns as leaves; [indexes] place the group's columns in the row. */
class LeafSink(val channel: FileChannel, val columns: List<RecordMeta>, val indexes: IntArray, val rowBytes: Int, leafBytes: Int) {
    val rowsPerLeaf = maxOf(1, leafBytes / rowBytes)
    val leaf = ByteArray(rowsPerLeaf * rowBytes)
    val offsets = IntArray(columns.size)
    var fill = 0
    var frames = 0
    var packedSizes = IntArray(16)
    var contentSizes = IntArray(16)
    var at = 0L
    val encoder = ZstdEncoder()
    val fenceAt = IntArray(columns.size) { -1 }
    val fenceStride: Int
    var fence: ByteArray
    val lo = arrayOfNulls<Any>(columns.size)
    val hi = arrayOfNulls<Any>(columns.size)
    var keepAt = 0L
    var tail = ByteArray(0)

    init {
        var stride = 0
        var o = 0
        for (c in columns.indices) {
            offsets[c] = o
            o += width(columns[c])
            if (fenced(columns[c])) { fenceAt[c] = stride; stride += 2 * width(columns[c]) }
        }
        fenceStride = stride
        fence = ByteArray(16 * stride)
    }

    fun read(offset: Long, dst: ByteArray) = transfer(channel, dst, offset, read = true)

    /** Takes over an existing leaf file: keeps its full leaves and reopens a partial last leaf; returns its rows. */
    fun resume(): Long {
        val size = channel.size()
        if (size == 0L) return 0
        val leaves = requireNotNull(openLeaves(::read, size, rowBytes, columns)) { "ISAM group holds plain rows; leaf writes need a leaf file" }
        val prior = requireNotNull(leaves.fence) { "ISAM leaf file lacks its fence" }
        require(leaves.fenceAt.contentEquals(fenceAt)) { "ISAM leaf fence columns differ from metadata" }
        val t = leaves.table
        var keep = t.frames
        if (keep > 0 && t.content[keep] - t.content[keep - 1] < leaf.size) keep--
        for (k in 0 until keep) {
            record((t.packed[k + 1] - t.packed[k]).toInt(), (t.content[k + 1] - t.content[k]).toInt(),
                prior, LEAF_FENCE_HEAD + columns.size + k * fenceStride)
        }
        at = t.packed[keep]
        if (keep < t.frames) {
            val n = (t.content[keep + 1] - t.content[keep]).toInt()
            leaves.load(keep).copyInto(leaf, 0, 0, n)
            fill = n / rowBytes
            for (r in 0 until fill) for (c in columns.indices) if (fenceAt[c] >= 0) {
                val from = r * rowBytes + offsets[c]
                widen(c, columns[c].decoder(leaf.copyOfRange(from, from + width(columns[c]))))
            }
        }
        keepAt = at
        tail = ByteArray((size - keepAt).toInt()).also { read(keepAt, it) }
        return leaves.rows.toLong()
    }

    @Suppress("UNCHECKED_CAST")
    fun widen(c: Int, v: Any?) {
        val x = requireNotNull(v) { "ISAM fence column ${columns[c].name} holds null" } as Comparable<Any>
        if (lo[c] == null || x < lo[c]!!) lo[c] = v
        if (hi[c] == null || x > hi[c]!!) hi[c] = v
    }

    fun add(row: RowVec) {
        val base = fill * rowBytes
        for (c in columns.indices) {
            val column = columns[c]
            val value = row[indexes[c]].a
            val encoded = column.encoder(value)
            val w = width(column)
            require(encoded.size <= w && (column.type.networkSize == null || encoded.size == w)) {
                "ISAM encoded width differs from metadata for ${column.name}"
            }
            val o = base + offsets[c]
            encoded.copyInto(leaf, o)
            leaf.fill(0, o + encoded.size, o + w)
            if (fenceAt[c] >= 0) widen(c, value)
        }
        if (++fill == rowsPerLeaf) flush()
    }

    fun flush() {
        if (fill == 0) return
        val n = fill * rowBytes
        val frame = encoder.compress(leaf, 0, n)
        transfer(channel, frame, at, read = false)
        at += frame.size
        val entry = ByteArray(fenceStride)
        for (c in columns.indices) {
            val o = fenceAt[c]
            if (o < 0) continue
            columns[c].encoder(lo[c]).copyInto(entry, o)
            columns[c].encoder(hi[c]).copyInto(entry, o + width(columns[c]))
        }
        record(frame.size, n, entry, 0)
        fill = 0
        lo.fill(null)
        hi.fill(null)
    }

    fun record(packed: Int, content: Int, src: ByteArray, from: Int) {
        if (frames == packedSizes.size) {
            packedSizes = packedSizes.copyOf(frames * 2)
            contentSizes = contentSizes.copyOf(frames * 2)
        }
        if ((frames + 1) * fenceStride > fence.size) fence = fence.copyOf(maxOf(fence.size * 2, (frames + 1) * fenceStride))
        packedSizes[frames] = packed
        contentSizes[frames] = content
        src.copyInto(fence, frames * fenceStride, from, from + fenceStride)
        frames++
    }

    /** Flushes the partial leaf, writes the fence and seek table, and cuts the file after them. */
    fun finish() {
        flush()
        val head = LEAF_FENCE_HEAD + columns.size
        val f = ByteArray(head + frames * fenceStride)
        f.put32(0, LEAF_FENCE_MAGIC)
        f.put32(4, f.size - 8)
        f.put32(8, frames)
        f.put16(12, columns.size)
        for (c in columns.indices) f[LEAF_FENCE_HEAD + c] = if (fenceAt[c] >= 0) 1 else 0
        fence.copyInto(f, head, 0, frames * fenceStride)
        transfer(channel, f, at, read = false)
        val packed = LongArray(frames + 1)
        val content = LongArray(frames + 1)
        for (k in 0 until frames) {
            packed[k + 1] = packed[k] + packedSizes[k]
            content[k + 1] = content[k] + contentSizes[k]
        }
        val table = SeekTable(frames, packed, content).bytes()
        transfer(channel, table, at + f.size, read = false)
        channel.truncate(at + f.size + table.size)
        channel.force(true)
    }

    /** Puts the file back as it was before this write. */
    fun restore(cause: Throwable) {
        try {
            channel.truncate(keepAt)
            if (tail.isNotEmpty()) transfer(channel, tail, keepAt, read = false)
            channel.force(true)
        } catch (caught: Throwable) {
            if (caught !== cause) cause.addSuppressed(caught)
        }
    }
}

/** Leaf counterpart of the plain row writer: every group file becomes leaves + fence + seek table. */
fun writeLeafRows(rows: Iterable<RowVec>, filename: String, metadata: Series<RecordMeta>, append: Boolean, create: Boolean,
                  channelFactory: IsamChannelFactory, leafBytes: Int) {
    val groups = metadata.view.groupBy { it.groupName }
    val primary = metadata.view.maxOf { it.groupId }
    val order = metadata.view.toList()
    val sinks = mutableListOf<LeafSink>()
    var failure: Throwable? = null
    try {
        var existing: Long? = null
        for ((_, columns) in groups) {
            val options = mutableSetOf<OpenOption>(StandardOpenOption.READ, StandardOpenOption.WRITE)
            if (create) options.add(StandardOpenOption.CREATE)
            if (!append) options.add(StandardOpenOption.TRUNCATE_EXISTING)
            val channel = channelFactory(groupPath(filename, columns, primary), options)
            val sink = LeafSink(channel, columns, IntArray(columns.size) { order.indexOf(columns[it]) }, groupLength(columns), leafBytes)
            sinks += sink
            val count = if (append) sink.resume() else 0L
            require(count <= Int.MAX_VALUE && (existing == null || existing == count)) { "ISAM group row counts disagree or exceed cursor capacity" }
            existing = count
        }
        var count = requireNotNull(existing)
        try {
            for (row in rows) {
                require(count < Int.MAX_VALUE) { "ISAM append exceeds cursor capacity" }
                require(row.size == metadata.size) { "ISAM row width differs from metadata" }
                for (sink in sinks) sink.add(row)
                count++
            }
            for (sink in sinks) sink.finish()
        } catch (caught: Throwable) {
            for (sink in sinks) sink.restore(caught)
            throw caught
        }
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        closeChannels(sinks.map { it.channel }, failure)
    }
}
