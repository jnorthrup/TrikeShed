package borg.trikeshed.isam

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.meta.IsamMetaFileReader
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.MemoryMapping
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.IOException
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.OpenOption
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex

internal typealias IsamChannelFactory = (String, Set<OpenOption>) -> FileChannel

internal fun openIsamChannel(path: String, options: Set<OpenOption>): FileChannel = FileChannel.open(path, options)

enum class IsamReadMode { CHANNEL, MMAP }

fun groupLength(columns: List<RecordMeta>): Int {
    val bytes = columns.fold(0L) { total, column ->
        require(column.begin >= 0 && column.end > column.begin) { "ISAM columns require positive fixed widths" }
        total + column.end.toLong() - column.begin
    }
    require(bytes in 1..Int.MAX_VALUE.toLong()) { "ISAM group row length exceeds buffer capacity" }
    return bytes.toInt()
}

fun groupPath(datafilename: String, columns: List<RecordMeta>, primary: Int): String =
    if (columns.first().groupId == primary) datafilename else getGroupFilename(datafilename, columns.first().groupName)

/** Complete one fixed-width transfer; FileChannel owns the facade and CQE validation. */
fun transfer(channel: FileChannel, bytes: ByteArray, offset: Long, read: Boolean) {
    require(offset >= 0 && offset <= Long.MAX_VALUE - bytes.size)
    val buffer = ByteBuffer.wrap(bytes)
    while (buffer.hasRemaining()) {
        val position = buffer.position()
        val remaining = buffer.remaining()
        val count = if (read) channel.read(buffer, offset + position) else channel.write(buffer, offset + position)
        if (count <= 0 || count > remaining || buffer.position() != position + count) {
            throw IOException("Incomplete ISAM ${if (read) "read" else "write"} at ${offset + position}: count=$count remaining=$remaining")
        }
    }
}

internal fun closeChannels(channels: Collection<FileChannel>, cause: Throwable? = null) {
    var failure = cause
    for (channel in channels) try { channel.close() } catch (caught: Throwable) {
        if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
    }
    if (cause == null) failure?.let { throw it }
}

class UringIsamDataReader internal constructor(
    val datafileFilename: String,
    val metafileFilename: String,
    metafile: IsamMetaFileReader,
    private val channelFactory: IsamChannelFactory,
    private val readMode: IsamReadMode = IsamReadMode.CHANNEL,
) : IsamDataReader {
    val metafile = metafile.withDefaultFiles(IsamFileOperations(channelFactory))
    constructor(datafileFilename: String, metafileFilename: String, metafile: IsamMetaFileReader,
                readMode: IsamReadMode = IsamReadMode.CHANNEL) :
        this(datafileFilename, metafileFilename, metafile, ::openIsamChannel, readMode)

    constructor(scope: CoroutineScope, datafileFilename: String, metafileFilename: String,
                metafile: IsamMetaFileReader, readMode: IsamReadMode = IsamReadMode.CHANNEL) :
        this(datafileFilename, metafileFilename, metafile, { path, options -> FileChannel.open(scope, path, options) }, readMode)

    private var constraints: Series<RecordMeta>? = null
    private val groupFiles = mutableMapOf<String, FileChannel>()
    private val groupMappings = mutableMapOf<String, MemoryMapping>()
    /** Per column: its group, its offset in the group row, and its place among the group's columns. */
    var columns: Array<RecordMeta> = emptyArray()
    var columnGroup = IntArray(0)
    var columnAt = IntArray(0)
    var columnSlot = IntArray(0)
    /** Per group: row width, row read, and the leaves when the group file holds them. */
    var groupWidth = IntArray(0)
    var groupRow: Array<(Int, ByteArray) -> Unit> = emptyArray()
    var groupLeaves: Array<IsamLeaves?> = emptyArray()
    var every = IntArray(0)
    private var opened = false
    private var rows = 0

    override val recordCount: Int get() {
        check(opened) { "ISAM reader is closed" }
        return rows
    }

    override val readRow: (Int) -> RowVec = { row ->
        check(opened) { "ISAM reader is closed" }
        rowOf(row, every)
    }

    override fun projection(names: List<CharSequence>): (Int) -> RowVec {
        check(opened) { "ISAM reader is closed" }
        val picked = IntArray(names.size) { k ->
            val name = names[k].toString()
            columns.indexOfFirst { it.name == name }.also { if (it < 0) error("ISAM column '$name' not found") }
        }
        return { row ->
            check(opened) { "ISAM reader is closed" }
            rowOf(row, picked)
        }
    }

    /** Row of the [picked] columns: each touched group is read once into this row's own buffer; cells decode on access. */
    fun rowOf(row: Int, picked: IntArray): RowVec {
        require(row in 0 until rows) { "ISAM row is outside the data file" }
        val columns = columns
        val group = columnGroup
        val at = columnAt
        val widths = groupWidth
        val reads = groupRow
        val bytes = arrayOfNulls<ByteArray>(widths.size)
        try {
            for (c in picked) {
                val g = group[c]
                if (bytes[g] == null) bytes[g] = ByteArray(widths[g]).also { reads[g](row, it) }
            }
        } catch (failure: Throwable) {
            closeResources(failure)
            throw failure
        }
        // The returned row retains heap bytes and this open's schema, never a channel or mapping.
        return picked.size j { index: Int ->
            require(index in 0 until picked.size) { "ISAM column is outside the row" }
            val c = picked[index]
            val column = columns[c]
            val source = bytes[group[c]]!!
            val width = column.end - column.begin
            val cell = if (width == source.size) source else source.copyOfRange(at[c], at[c] + width)
            requireNotNull(column.decoder(cell)) { "ISAM decoder returned null for ${column.name}" } j column.`↺`
        }
    }

    override fun open() {
        if (opened) return
        check(groupFiles.isEmpty() && groupMappings.isEmpty()) { "ISAM reader cleanup must complete before reopening" }
        try {
            metafile.open()
            val metadata = metafile.constraints
            val order = metadata.view.toList()
            val groups = order.groupBy { it.groupName }
            val primary = order.maxOf { it.groupId }
            val group = IntArray(order.size)
            val at = IntArray(order.size)
            val slot = IntArray(order.size)
            val widths = IntArray(groups.size)
            val reads = arrayOfNulls<(Int, ByteArray) -> Unit>(groups.size)
            val leaves = arrayOfNulls<IsamLeaves>(groups.size)
            var count: Long? = null
            for ((g, entry) in groups.entries.withIndex()) {
                val (name, members) = entry
                val length = groupLength(members)
                val channel = channelFactory(groupPath(datafileFilename, members, primary), setOf(StandardOpenOption.READ))
                groupFiles[name] = channel
                val size = channel.size()
                val mapping = if (readMode == IsamReadMode.MMAP && size > 0)
                    channel.map(FileChannel.MapMode.READ_ONLY, 0, size).also { groupMappings[name] = it } else null
                val read: IsamRead = if (mapping != null) { offset, dst -> mapping.read(offset, dst) }
                    else { offset, dst -> transfer(channel, dst, offset, read = true) }
                val stored = openLeaves(read, size, length, members)
                val groupCount = if (stored != null) stored.rows.toLong() else {
                    require(size >= 0 && size % length == 0L) { "ISAM group $name contains an incomplete row" }
                    size / length
                }
                require(groupCount <= Int.MAX_VALUE && (count == null || count == groupCount)) { "ISAM group row counts disagree or exceed cursor capacity" }
                count = groupCount
                leaves[g] = stored
                reads[g] = if (stored != null) { row, dst -> stored.row(row, dst) } else { row, dst -> read(row.toLong() * length, dst) }
                widths[g] = length
                var offset = 0
                for ((k, column) in members.withIndex()) {
                    val c = order.indexOf(column)
                    group[c] = g
                    at[c] = offset
                    slot[c] = k
                    offset += column.end - column.begin
                }
            }
            rows = requireNotNull(count).toInt()
            constraints = metadata
            columns = order.toTypedArray()
            columnGroup = group
            columnAt = at
            columnSlot = slot
            groupWidth = widths
            groupRow = Array(reads.size) { requireNotNull(reads[it]) }
            groupLeaves = leaves
            every = IntArray(order.size) { it }
            opened = true
        } catch (failure: Throwable) {
            closeResources(failure)
            throw failure
        }
    }

    override fun fence(column: String): Series<Join<IntRange, Twin<Any?>>> {
        check(opened) { "ISAM reader is closed" }
        val c = columns.indices.first { columns[it].name == column }
        val leaves = groupLeaves[columnGroup[c]]
        val slot = columnSlot[c]
        return if (leaves == null || leaves.fenceAt[slot] < 0) emptySeriesOf()
        else leaves.table.frames j { k: Int -> leaves.leafRows(k) j requireNotNull(leaves.bounds(k, slot)) }
    }

    override fun close(): Unit = closeResources()

    private fun closeResources(cause: Throwable? = null) {
        opened = false
        rows = 0
        constraints = null
        columns = emptyArray()
        columnGroup = IntArray(0)
        columnAt = IntArray(0)
        columnSlot = IntArray(0)
        groupWidth = IntArray(0)
        groupRow = emptyArray()
        groupLeaves = emptyArray()
        every = IntArray(0)
        var failure = cause
        fun failed(caught: Throwable) {
            if (failure == null) failure = caught else if (failure !== caught) failure!!.addSuppressed(caught)
        }
        // Retain a failed unmap and its descriptor so close can be retried safely.
        for ((name, mapping) in groupMappings.toMap()) try {
            mapping.close()
            groupMappings.remove(name)
        } catch (caught: Throwable) { failed(caught) }
        for ((name, channel) in groupFiles.toMap()) if (name !in groupMappings) try {
            channel.close()
            groupFiles.remove(name)
        } catch (caught: Throwable) { failed(caught) }
        try { metafile.close() } catch (caught: Throwable) { failed(caught) }
        if (cause == null) failure?.let { throw it }
    }
}

/** [leafBytes] > 0 writes every group file as zstd seekable leaves of that many bytes (see [IsamLeaves]). */
class UringIsamOperations internal constructor(
    private val channelFactory: IsamChannelFactory,
    private val readMode: IsamReadMode = IsamReadMode.CHANNEL,
    val leafBytes: Int = 0,
) : IsamOperations {
    constructor(readMode: IsamReadMode = IsamReadMode.CHANNEL, leafBytes: Int = 0) : this(::openIsamChannel, readMode, leafBytes)
    constructor(scope: CoroutineScope, readMode: IsamReadMode = IsamReadMode.CHANNEL, leafBytes: Int = 0) :
        this({ path, options -> FileChannel.open(scope, path, options) }, readMode, leafBytes)
    private val metadataFiles = IsamFileOperations(channelFactory)
    private val lock = Mutex()

    override fun createReader(datafileFilename: String, metafileFilename: String, metafile: IsamMetaFileReader): IsamDataReader =
        UringIsamDataReader(datafileFilename, metafileFilename, metafile, channelFactory, readMode)

    override fun write(cursor: Cursor, datafilename: String, varChars: Map<String, Int>, useMonocursorGroupings: Boolean): Unit = exclusive {
        require(cursor.size > 0) { "ISAM write needs at least one row to establish its schema" }
        val first = cursor[0]
        val metadata = IsamMetaFileReader.write("$datafilename.meta", rowMetadata(first), varChars,
            metadataFiles = metadataFiles, useMonocursorGroupings = useMonocursorGroupings)
        writeRows(cursor.view, datafilename, metadata, append = false, create = true)
    }

    override fun append(msf: Iterable<RowVec>, datafilename: String, varChars: Map<String, Int>,
                        transform: ((RowVec) -> RowVec)?, useMonocursorGroupings: Boolean): Unit = exclusive {
        val source = msf.iterator()
        if (!source.hasNext()) return@exclusive
        val first = source.next().let { transform?.invoke(it) ?: it }
        val metafilename = "$datafilename.meta"
        val exists = metadataFiles.exists(metafilename)
        val metadata = if (exists) {
            val reader = IsamMetaFileReader(metafilename, metadataFiles)
            try {
                reader.open()
                reader.constraints.also {
                    val proposed = IsamMetaFileReader.sanitize(rowMetadata(first), varChars, useMonocursorGroupings)
                    require(sameSchema(it, proposed, datafilename)) { "ISAM append schema differs from existing metadata" }
                }
            } finally { reader.close() }
        } else IsamMetaFileReader.write(metafilename, rowMetadata(first), varChars,
            metadataFiles = metadataFiles, useMonocursorGroupings = useMonocursorGroupings)
        val rows = sequence {
            yield(first)
            while (source.hasNext()) yield(source.next().let { transform?.invoke(it) ?: it })
        }
        writeRows(rows.asIterable(), datafilename, metadata, append = true, create = !exists)
    }

    private fun rowMetadata(row: RowVec): Series<ColumnMeta> {
        require(row.size > 0) { "ISAM requires columns" }
        return row.size j { index: Int -> row[index].b() }
    }

    private fun sameSchema(left: Series<RecordMeta>, right: Series<RecordMeta>, filename: String): Boolean {
        if (left.size != right.size) return false
        val leftPrimary = left.view.maxOf { it.groupId }
        val rightPrimary = right.view.maxOf { it.groupId }
        return (0 until left.size).all { index ->
            val a = left[index]
            val b = right[index]
            a.name == b.name && a.type == b.type && a.end - a.begin == b.end - b.begin &&
                (if (a.groupId == leftPrimary) filename else getGroupFilename(filename, a.groupName)) ==
                (if (b.groupId == rightPrimary) filename else getGroupFilename(filename, b.groupName))
        }
    }

    private fun writeRows(rows: Iterable<RowVec>, filename: String, metadata: Series<RecordMeta>, append: Boolean, create: Boolean) {
        if (leafBytes > 0) return writeLeafRows(rows, filename, metadata, append, create, channelFactory, leafBytes)
        val groups = metadata.view.groupBy { it.groupName }
        val primary = metadata.view.maxOf { it.groupId }
        val channels = mutableMapOf<String, FileChannel>()
        val offsets = mutableMapOf<String, Long>()
        var failure: Throwable? = null
        try {
            var existingCount: Long? = null
            for ((name, columns) in groups) {
                val options = mutableSetOf<OpenOption>(StandardOpenOption.READ, StandardOpenOption.WRITE)
                if (create) options.add(StandardOpenOption.CREATE)
                if (!append) options.add(StandardOpenOption.TRUNCATE_EXISTING)
                val channel = channelFactory(groupPath(filename, columns, primary), options)
                channels[name] = channel
                val length = groupLength(columns)
                val size = if (append) channel.size() else 0L
                require(size >= 0 && size % length == 0L) { "ISAM group $name contains an incomplete row" }
                val count = size / length
                require(count <= Int.MAX_VALUE && (existingCount == null || existingCount == count)) {
                    "ISAM group row counts disagree or exceed cursor capacity"
                }
                existingCount = count
                offsets[name] = size
            }
            var count = requireNotNull(existingCount)
            // One buffer per group for the whole write; each row is encoded into them before its first physical write.
            val members = groups.values.map { it.toTypedArray() }.toTypedArray()
            val order = metadata.view.toList()
            val indexes = Array(members.size) { g -> IntArray(members[g].size) { order.indexOf(members[g][it]) } }
            val buffers = Array(members.size) { ByteArray(groupLength(members[it].asList())) }
            val sinks = groups.keys.map { channels.getValue(it) }.toTypedArray()
            val at = groups.keys.map { offsets.getValue(it) }.toLongArray()
            for (row in rows) {
                require(count < Int.MAX_VALUE) { "ISAM append exceeds cursor capacity" }
                require(row.size == metadata.size) { "ISAM row width differs from metadata" }
                for (g in members.indices) {
                    val bytes = buffers[g]
                    val columns = members[g]
                    val index = indexes[g]
                    var offset = 0
                    for (k in columns.indices) {
                        val column = columns[k]
                        val encoded = column.encoder(row[index[k]].a)
                        val width = column.end - column.begin
                        require(encoded.size <= width && (column.type.networkSize == null || encoded.size == width)) {
                            "ISAM encoded width differs from metadata for ${column.name}"
                        }
                        encoded.copyInto(bytes, offset)
                        bytes.fill(0, offset + encoded.size, offset + width)
                        offset += width
                    }
                }
                try {
                    for (g in sinks.indices) transfer(sinks[g], buffers[g], at[g], read = false)
                } catch (caught: Throwable) {
                    // Restore the failed row across every group; completed rows remain appendable.
                    for (g in sinks.indices) try {
                        sinks[g].truncate(at[g])
                        sinks[g].force(true)
                    } catch (cleanup: Throwable) {
                        if (cleanup !== caught) caught.addSuppressed(cleanup)
                    }
                    throw caught
                }
                for (g in sinks.indices) at[g] += buffers[g].size
                count++
            }
            channels.values.forEach { it.force(true) }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally { closeChannels(channels.values, failure) }
    }
    private inline fun <T> exclusive(operation: () -> T): T {
        check(lock.tryLock()) { "Concurrent ISAM writes require serialization by their owner" }
        try { return operation() } finally { lock.unlock() }
    }
}
