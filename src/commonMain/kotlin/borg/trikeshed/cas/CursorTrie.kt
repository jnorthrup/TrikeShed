@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package borg.trikeshed.cas

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IsamMetaFileReader
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.rootCell
import borg.trikeshed.collections.associative.toAny
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicIntArray
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference

/** The leaf of both CursorTrie orders: one 16 KiB page of fixed-width rows, the ISAM leaf geometry. */
const val TRIE_PAGE: Int = 16384

/**
 * The hash order of the CursorTrie: rows keyed by a content digest's leading 8 bytes ([fold]), addressing what a pack
 * holds (`offset`, `length`, `kind`). Fan-out is one byte per level. A leaf at depth d inherits the digest's leading d
 * bytes as its path, so its rows store only the remaining 8 - d bytes: a row is 24 - d bytes, about 680 to a leaf.
 * Leaves start at depth 1 (256 of them, about 174,000 rows); a full leaf seals as one immutable page and later rows
 * under its prefix open its 256 children (65,536 leaves at depth 2, about 44 million rows). Digests are uniform, so
 * leaves fill evenly and depth differs by at most one.
 *
 * Files under [root], all append-only: `hash.log` (every row as put, 24 bytes, the source of an open leaf), `hash.pages`
 * (sealed leaves, sorted by tail) and `hash.dir` (one record per seal: depth, slot, page). Opening replays the log;
 * sealing is a function of put order, so the replay seals the same leaves and checks them against the directory instead
 * of writing them again. One writer; a reader takes no lock: it reads a leaf's published count, then its rows.
 */
class HashTrie(files: FileOperations, root: String) : AutoCloseable {
    class Row(val fold: Long, val offset: Long, val length: Int, val kind: Int)

    private class Level(depth: Int) {
        val slots = 1 shl (8 * depth)
        /** page + 1 once sealed, else 0 */
        val sealed = AtomicIntArray(slots)
        val count = AtomicIntArray(slots)
        val open = arrayOfNulls<ByteArray>(slots)
    }

    private val log: FileChannel
    private val pages: FileChannel
    private val dir: FileChannel
    private val levels = arrayOfNulls<Level>(MAX_DEPTH + 1)
    private val held = AtomicBoolean(false)
    private var logEnd = 0L
    private var dirEnd = 0L
    private var pageCount = 0
    private val pendingDir = ArrayList<ByteArray>()
    private var replay: ArrayDeque<Triple<Int, Int, Int>>? = null
    private val rows = AtomicLong(0L)

    /** Rows held, one per fold except where a sealed leaf's fold was put again (that row lands deeper). */
    val size: Long get() = rows.load()

    /** Sealed pages written. */
    val sealedPages: Int get() = pageCount

    init {
        files.mkdirs(root)
        fun open(name: String) = FileChannel.open(files.resolvePath(root, name), StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
        log = open("hash.log"); pages = open("hash.pages"); dir = open("hash.dir")
        dirEnd = dir.size() - dir.size() % DIR
        if (dirEnd < dir.size()) dir.truncate(dirEnd)
        val seals = ArrayDeque<Triple<Int, Int, Int>>()
        if (dirEnd > 0) {
            val b = readAt(dir, 0, dirEnd.toInt())
            for (r in 0 until (dirEnd / DIR).toInt()) {
                val w = ByteBuffer.wrap(b)
                seals.addLast(Triple(b[r * DIR].toInt(), w.getInt(r * DIR + 1), w.getInt(r * DIR + 5)))
            }
        }
        pageCount = seals.size
        logEnd = log.size() - log.size() % LOG
        if (logEnd < log.size()) log.truncate(logEnd)
        replay = seals
        var at = 0L
        while (at < logEnd) {
            val n = minOf(LOG * 4096L, logEnd - at).toInt()
            val b = ByteBuffer.wrap(readAt(log, at, n))
            for (r in 0 until n / LOG) insert(b.getLong(r * LOG), b.getLong(r * LOG + 8), b.getInt(r * LOG + 16), b.getInt(r * LOG + 20))
            at += n
        }
        check(seals.isEmpty()) { "hash.dir holds ${seals.size} seals the log does not reach" }
        replay = null
    }

    /** Rows per leaf at depth [depth]. */
    fun capacity(depth: Int): Int = TRIE_PAGE / width(depth)

    /**
     * Adds a row, durable at the next [commit]. The newest row for a fold wins, as in the pack: put again while its leaf
     * is open, the row is replaced in place; once its leaf is sealed, the newer row lands deeper and [get] reads the deepest.
     */
    fun put(fold: Long, offset: Long, length: Int, kind: Int): Unit = locked {
        val b = ByteBuffer.allocate(LOG)
        b.putLong(fold); b.putLong(offset); b.putInt(length); b.putInt(kind)
        writeAt(log, logEnd, b.array()); logEnd += LOG
        insert(fold, offset, length, kind)
    }

    /** Forces the log, the pages and then the directory: once per commit, never per put. */
    fun commit(): Unit = locked {
        log.force(false)
        pages.force(false)
        for (r in pendingDir) { writeAt(dir, dirEnd, r); dirEnd += DIR }
        pendingDir.clear()
        dir.force(false)
    }

    /** The newest row for [fold] (the deepest that holds it), or null; no lock. */
    fun get(fold: Long): Row? {
        var found: Row? = null
        var d = 1
        while (d <= MAX_DEPTH) {
            val level = levels[d] ?: return found
            val s = slot(fold, d)
            val page = level.sealed.loadAt(s)
            if (page > 0) {
                search(page - 1, d, fold)?.let { found = it }
                d++; continue
            }
            val c = level.count.loadAt(s)
            val buf = level.open[s] ?: return if (level.sealed.loadAt(s) > 0) get(fold) else found
            val w = ByteBuffer.wrap(buf)
            for (r in 0 until c) if (w.getLong(r * LOG) == fold) return Row(fold, w.getLong(r * LOG + 8), w.getInt(r * LOG + 16), w.getInt(r * LOG + 20))
            return found
        }
        return found
    }

    /** Leaves holding rows, by depth (index 0 unused). */
    fun leavesByDepth(): IntArray {
        val out = IntArray(MAX_DEPTH + 1)
        for (d in 1..MAX_DEPTH) {
            val level = levels[d] ?: continue
            for (s in 0 until level.slots) if (level.count.loadAt(s) > 0) out[d]++
        }
        return out
    }

    /** The bytes of sealed page [page]: what a read-only guest or a replica reads. */
    fun page(page: Int): ByteArray = readAt(pages, page.toLong() * TRIE_PAGE, TRIE_PAGE)

    override fun close(): Unit = locked { log.close(); pages.close(); dir.close() }

    private fun insert(fold: Long, offset: Long, length: Int, kind: Int) {
        var d = 1
        while (true) {
            check(d <= MAX_DEPTH) { "hash trie deeper than $MAX_DEPTH" }
            val level = levels[d] ?: Level(d).also { levels[d] = it }
            val s = slot(fold, d)
            if (level.sealed.loadAt(s) > 0) { d++; continue }
            val c = level.count.loadAt(s)
            var buf = level.open[s]
            if (buf != null) {
                val prior = ByteBuffer.wrap(buf)
                for (r in 0 until c) if (prior.getLong(r * LOG) == fold) {
                    prior.putLong(r * LOG + 8, offset); prior.putInt(r * LOG + 16, length); prior.putInt(r * LOG + 20, kind)
                    return
                }
            }
            if (buf == null || buf.size < (c + 1) * LOG) {
                buf = (buf ?: ByteArray(0)).copyOf(minOf(capacity(d), maxOf(8, c * 2)) * LOG)
                level.open[s] = buf
            }
            val w = ByteBuffer.wrap(buf)
            w.putLong(c * LOG, fold); w.putLong(c * LOG + 8, offset); w.putInt(c * LOG + 16, length); w.putInt(c * LOG + 20, kind)
            level.count.storeAt(s, c + 1)
            rows.addAndFetch(1L)
            if (c + 1 == capacity(d)) seal(level, d, s)
            return
        }
    }

    private fun seal(level: Level, d: Int, s: Int) {
        val buf = level.open[s]!!
        val n = capacity(d)
        val src = ByteBuffer.wrap(buf)
        val mask = -1L ushr (8 * d)
        val order = (0 until n).sortedWith { x, y -> (src.getLong(x * LOG) and mask).toULong().compareTo((src.getLong(y * LOG) and mask).toULong()) }
        val w = width(d)
        val page = ByteArray(TRIE_PAGE)
        for ((i, r) in order.withIndex()) {
            val tail = src.getLong(r * LOG) and mask
            for (k in 0 until 8 - d) page[i * w + k] = (tail ushr (8 * (7 - d - k))).toByte()
            val dst = ByteBuffer.wrap(page)
            dst.putLong(i * w + 8 - d, src.getLong(r * LOG + 8)); dst.putInt(i * w + 16 - d, src.getInt(r * LOG + 16)); dst.putInt(i * w + 20 - d, src.getInt(r * LOG + 20))
        }
        val expected = replay
        val index = if (expected != null) {
            val e = expected.removeFirstOrNull() ?: error("log seals a leaf hash.dir does not hold")
            check(e.first == d && e.second == s) { "replay seals depth $d slot $s where hash.dir holds depth ${e.first} slot ${e.second}" }
            e.third
        } else {
            val p = pageCount++
            writeAt(pages, p.toLong() * TRIE_PAGE, page)
            val r = ByteBuffer.allocate(DIR)
            r.put(d.toByte()); r.putInt(s); r.putInt(p); r.putInt(n)
            pendingDir.add(r.array())
            p
        }
        level.sealed.storeAt(s, index + 1)
        level.open[s] = null
    }

    private fun search(page: Int, d: Int, fold: Long): Row? {
        val bytes = page(page)
        val b = ByteBuffer.wrap(bytes)
        val w = width(d)
        val mask = -1L ushr (8 * d)
        val tail = (fold and mask).toULong()
        fun tailAt(i: Int): ULong { var t = 0L; for (k in 0 until 8 - d) t = (t shl 8) or (bytes[i * w + k].toLong() and 0xff); return t.toULong() }
        var lo = 0; var hi = capacity(d) - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val t = tailAt(mid)
            when {
                t < tail -> lo = mid + 1
                t > tail -> hi = mid - 1
                else -> return Row(fold, b.getLong(mid * w + 8 - d), b.getInt(mid * w + 16 - d), b.getInt(mid * w + 20 - d))
            }
        }
        return null
    }

    private inline fun <T> locked(block: () -> T): T {
        while (!held.compareAndSet(false, true)) Unit
        try { return block() } finally { held.store(false) }
    }

    companion object {
        const val MAX_DEPTH = 3
        private const val LOG = 24
        private const val DIR = 13

        /** Row width at depth [depth]: the digest tail the path does not hold, offset, length, kind. */
        fun width(depth: Int): Int = 24 - depth

        /** The leaf slot of [fold] at [depth]: its leading [depth] bytes. */
        fun slot(fold: Long, depth: Int): Int = (fold ushr (64 - 8 * depth)).toInt()

        /** A digest's leading 8 bytes, big endian: the same fold the CAS pack indexes by. */
        fun fold(digest: ByteArray): Long { var f = 0L; for (i in 0 until 8) f = (f shl 8) or (digest[i].toLong() and 0xff); return f }
    }
}

/**
 * The semantic order of the CursorTrie: project / document / lane / segment, always four levels. A project, a document
 * and a lane are interior nodes whose meta is written once, as canonical CBOR, when the node is first named: the project
 * its decision provider, the document its source reference, the lane its relation's columns. A lane is one (document,
 * relation) cursor; its segments are 16 KiB pages of fixed-width rows and nothing else. The tail segment fills by
 * appending rows into its page and, full, rolls to a new page; a lane never splits and nothing is rewritten.
 *
 * Files under [root], all append-only: `lane.nodes` (length-prefixed node records), `lane.pages` (16 KiB slots, one per
 * segment, every lane's in one pack) and `lane.dir` (SLOT: a lane's segment took a slot; COMMIT: a lane's durable row
 * count). One appender per lane, claimed by [appender]; readers take no lock: a lane's count is published after its row
 * is written, and a reader reads rows below it. [commit] forces the pages, then the directory, once.
 */
class LaneTrie(files: FileOperations, root: String) : AutoCloseable {
    inner class Lane internal constructor(
        val id: Int, val document: Int, val relation: String, val columns: Series<RecordMeta>,
    ) {
        val width: Int = columns.view.maxOf { it.end }
        val perPage: Int = TRIE_PAGE / width
        /** Segment → page slot, replaced whole as a segment opens: a reader loads it without a lock. */
        internal val segments = AtomicReference(IntArray(0))
        internal val published = AtomicLong(0L)
        internal var committed = 0L
        internal val claimed = AtomicBoolean(false)

        /** Rows a reader sees now. */
        val size: Long get() = published.load()

        /** Segments whose page is full: the ones a read-only reader may take. */
        val sealed: Int get() = (published.load() / perPage).toInt()

        /** The bytes of sealed segment [segment] through the trie's own channel (the writer's thread); see [Reader.page]. */
        fun page(segment: Int): ByteArray? = page(pages, segment)

        /** Rows below the published count through the trie's own channel (the writer's thread); see [Reader.cursor]. */
        fun cursor(): Cursor = cursor(pages)

        /** The bytes of sealed segment [segment]; null while it is the tail, which only the appender writes. */
        internal fun page(channel: FileChannel, segment: Int): ByteArray? =
            if (segment >= sealed) null else readAt(channel, slotOf(segment).toLong() * TRIE_PAGE, TRIE_PAGE)

        /** Rows below the published count, decoded cell by cell from each row's one read. */
        internal fun cursor(channel: FileChannel): Cursor {
            val n = published.load().toInt()
            val metas = columns.size j { i: Int -> val m = columns[i]; val f: () -> ColumnMeta = { m }; f }
            return n j { r: Int ->
                val bytes = readAt(channel, slotOf(r / perPage).toLong() * TRIE_PAGE + (r % perPage) * width, width)
                val row: RowVec = columns.size j { c: Int -> val m = columns[c]; m.decoder(bytes.copyOfRange(m.begin, m.end)) j metas[c] }
                row
            }
        }

        private fun slotOf(segment: Int): Int = segments.load()[segment]
    }

    /** A reader with its own channel on the pages: any number run beside the appenders, none takes a lock. */
    inner class Reader internal constructor() : AutoCloseable {
        private val channel = FileChannel.open(pagesPath, StandardOpenOption.READ)
        fun cursor(lane: Lane): Cursor = lane.cursor(channel)
        fun page(lane: Lane, segment: Int): ByteArray? = lane.page(channel, segment)
        override fun close() = channel.close()
    }

    /** The one writer of a lane, with its own channel on the pages; [close] releases the claim. */
    inner class Appender internal constructor(val lane: Lane) : AutoCloseable {
        private val channel = FileChannel.open(pagesPath, StandardOpenOption.READ, StandardOpenOption.WRITE)

        fun append(vararg values: Any?) {
            require(values.size == lane.columns.size) { "lane ${lane.relation} takes ${lane.columns.size} cells" }
            val n = lane.published.load()
            val segment = (n / lane.perPage).toInt()
            if (segment == lane.segments.load().size) newSegment(lane)
            val row = ByteArray(lane.width)
            for (i in 0 until lane.columns.size) { val m = lane.columns[i]; m.encoder(values[i]).copyInto(row, m.begin) }
            val slot = lane.segments.load()[segment]
            writeAt(channel, slot.toLong() * TRIE_PAGE + (n % lane.perPage) * lane.width, row)
            lane.published.store(n + 1)
        }

        override fun close() { channel.close(); lane.claimed.store(false) }
    }

    private val pagesPath = files.resolvePath(root, "lane.pages")
    private val nodes: FileChannel
    private val pages: FileChannel
    private val dir: FileChannel
    private var nodesEnd = 0L
    private var dirEnd = 0L
    private val slots = AtomicInt(0)
    private val held = AtomicBoolean(false)
    private val paths = HashMap<String, Int>()
    private val metas = ArrayList<Map<String, Any?>>()
    private val lanes = HashMap<Int, Lane>()

    /** Forces issued, for the once-per-commit measure. */
    var forces = 0; private set

    init {
        files.mkdirs(root)
        fun open(name: String) = FileChannel.open(files.resolvePath(root, name), StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
        nodes = open("lane.nodes"); pages = open("lane.pages"); dir = open("lane.dir")
        var at = 0L
        val size = nodes.size()
        while (at + 4 <= size) {
            val len = ByteBuffer.wrap(readAt(nodes, at, 4)).getInt(0)
            if (len <= 0 || at + 4 + len > size) break
            node(meta(readAt(nodes, at + 4, len)))
            at += 4 + len
        }
        if (at < size) nodes.truncate(at)
        nodesEnd = at
        dirEnd = dir.size() - dir.size() % DIR
        if (dirEnd < dir.size()) dir.truncate(dirEnd)
        var top = 0
        if (dirEnd > 0) {
            val b = readAt(dir, 0, dirEnd.toInt())
            val w = ByteBuffer.wrap(b)
            for (r in 0 until (dirEnd / DIR).toInt()) {
                val lane = lanes.getValue(w.getInt(r * DIR + 1))
                val a = w.getInt(r * DIR + 5); val v = w.getLong(r * DIR + 9)
                when (b[r * DIR].toInt()) {
                    SLOT -> { lane.segments.store(lane.segments.load().copyOf(a + 1).also { it[a] = v.toInt() }); top = maxOf(top, v.toInt() + 1) }
                    COMMIT -> { lane.committed = v; lane.published.store(v) }
                }
            }
        }
        slots.store(top)
    }

    /** The project node named [name], its meta written when first named. */
    fun project(name: String, meta: Map<String, Any?> = emptyMap()): Int = named("p", -1, name, meta)

    /** The document node [name] under [project]: its source reference, media type and URN in [meta]. */
    fun document(project: Int, name: String, meta: Map<String, Any?> = emptyMap()): Int = named("d", project, name, meta)

    /**
     * The lane of [relation] under [document]: its columns' ISAM codecs fix the row width; a string column takes its
     * width from [varchars]. Named again, the lane keeps the schema it was first written with.
     */
    fun lane(document: Int, relation: String, columns: List<Pair<String, IOMemento>>, varchars: Map<String, Int> = emptyMap()): Lane {
        val schema = columns.map { (n, t) -> listOf(n, t.name, varchars[n] ?: 0) }
        val id = named("l", document, relation, mapOf("columns" to schema))
        return lanes.getValue(id)
    }

    /** The meta [id] was named with. */
    fun meta(id: Int): Map<String, Any?> = metas[id]

    /** The lane [id], if one. */
    fun lane(id: Int): Lane? = lanes[id]

    /** A lock-free reader of every lane. */
    fun reader(): Reader = Reader()

    /** Claims [lane]'s single appender; a second claim while one is open is refused. */
    fun appender(lane: Lane): Appender {
        check(lane.claimed.compareAndSet(false, true)) { "lane ${lane.relation} already has its appender" }
        return Appender(lane)
    }

    /** Forces the pages, then records each lane's durable count and forces the directory: two forces, whatever was appended. */
    fun commit(): Unit = locked {
        pages.force(false); forces++
        for (lane in lanes.values) {
            val n = lane.published.load()
            if (n == lane.committed) continue
            dirRecord(COMMIT, lane.id, 0, n)
            lane.committed = n
        }
        dir.force(false); forces++
    }

    /** Bytes the three files hold. */
    fun bytes(): Long = nodes.size() + pages.size() + dir.size()

    /** Bytes the node file holds: meta only. */
    fun nodeBytes(): Long = nodes.size()

    override fun close(): Unit = locked { nodes.close(); pages.close(); dir.close() }

    private fun named(kind: String, parent: Int, name: String, meta: Map<String, Any?>): Int = locked {
        val key = "$kind\u0000$parent\u0000$name"
        paths[key]?.let { return@locked it }
        val record = linkedMapOf<String, Any?>("kind" to kind, "id" to metas.size.toLong(), "parent" to parent.toLong(), "name" to name, "meta" to meta)
        val bytes = CanonicalCbor.encodeMap(record)
        val len = ByteBuffer.allocate(4); len.putInt(bytes.size)
        writeAt(nodes, nodesEnd, len.array()); writeAt(nodes, nodesEnd + 4, bytes)
        nodesEnd += 4 + bytes.size
        node(record)
    }

    private fun node(record: Map<String, Any?>): Int {
        val id = (record["id"] as Number).toInt()
        val kind = record["kind"].toString(); val parent = (record["parent"] as Number).toInt()
        @Suppress("UNCHECKED_CAST") val meta = (record["meta"] as? Map<String, Any?>).orEmpty()
        check(id == metas.size) { "lane.nodes out of order at $id" }
        metas.add(meta)
        paths["$kind\u0000$parent\u0000${record["name"]}"] = id
        if (kind == "l") {
            val cols = (meta["columns"] as List<*>).map { it as List<*> }
            val varchars = cols.filter { (it[2] as Number).toInt() > 0 }.associate { it[0].toString() to (it[2] as Number).toInt() }
            val metaCols = cols.map { c -> ColumnMeta(c[0].toString(), IOMemento.valueOf(c[1].toString())) }
            val schema = IsamMetaFileReader.sanitize(metaCols.size j { i: Int -> metaCols[i] }, varchars, useMonocursorGroupings = false)
            lanes[id] = Lane(id, parent, record["name"].toString(), schema)
        }
        return id
    }

    private fun meta(bytes: ByteArray): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return CanonicalCbor.item(requireNotNull(confixDoc(bytes, Syntax.CBOR).rootCell)).toAny() as Map<String, Any?>
    }

    private fun newSegment(lane: Lane) {
        val slot = slots.fetchAndAdd(1)
        locked {
            val prior = lane.segments.load()
            dirRecord(SLOT, lane.id, prior.size, slot.toLong())
            lane.segments.store(prior.copyOf(prior.size + 1).also { it[prior.size] = slot })
        }
    }

    private fun dirRecord(tag: Int, lane: Int, a: Int, v: Long) {
        val r = ByteBuffer.allocate(DIR)
        r.put(tag.toByte()); r.putInt(lane); r.putInt(a); r.putLong(v)
        writeAt(dir, dirEnd, r.array()); dirEnd += DIR
    }

    private inline fun <T> locked(block: () -> T): T {
        while (!held.compareAndSet(false, true)) Unit
        try { return block() } finally { held.store(false) }
    }

    private companion object {
        const val DIR = 17
        const val SLOT = 1
        const val COMMIT = 2
    }
}

private fun readAt(channel: FileChannel, at: Long, length: Int): ByteArray {
    val b = ByteBuffer.allocate(length)
    var read = 0
    while (read < length) {
        val n = channel.read(b, at + read)
        check(n > 0) { "short read at ${at + read}" }
        read += n
    }
    return b.array()
}

private fun writeAt(channel: FileChannel, at: Long, bytes: ByteArray) {
    val b = ByteBuffer.wrap(bytes)
    var written = 0
    while (written < bytes.size) {
        val n = channel.write(b, at + written)
        check(n > 0) { "write stalled at ${at + written}" }
        written += n
    }
}
