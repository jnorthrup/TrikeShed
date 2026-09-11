package borg.trikeshed.isam

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.meta.IsamMetaFileReader
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.IOException
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.OpenOption
import borg.trikeshed.userspace.nio.file.StandardOpenOption

internal typealias IsamChannelFactory = (String, Set<OpenOption>) -> FileChannel

private fun openIsamChannel(path: String, options: Set<OpenOption>): FileChannel = FileChannel.open(path, options)

private fun groupLength(columns: List<RecordMeta>): Int {
    val bytes = columns.fold(0L) { total, column ->
        require(column.begin >= 0 && column.end > column.begin) { "ISAM columns require positive fixed widths" }
        total + column.end.toLong() - column.begin
    }
    require(bytes in 1..Int.MAX_VALUE.toLong()) { "ISAM group row length exceeds buffer capacity" }
    return bytes.toInt()
}

private fun groupPath(datafilename: String, columns: List<RecordMeta>, primary: Int): String =
    if (columns.first().groupId == primary) datafilename else getGroupFilename(datafilename, columns.first().groupName)

/** Complete one fixed-width transfer; FileChannel owns the facade and CQE validation. */
private fun transfer(channel: FileChannel, bytes: ByteArray, offset: Long, read: Boolean) {
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

class JvmIsamDataReader internal constructor(
    val datafileFilename: String,
    val metafileFilename: String,
    metafile: IsamMetaFileReader,
    private val channelFactory: IsamChannelFactory,
) : IsamDataReader {
    val metafile = if (metafile.fileOps == null)
        IsamMetaFileReader(metafileFilename, IsamFileOperations(channelFactory)) else metafile
    constructor(datafileFilename: String, metafileFilename: String, metafile: IsamMetaFileReader) :
        this(datafileFilename, metafileFilename, metafile, ::openIsamChannel)

    private var constraints: Series<RecordMeta>? = null
    private var columnsByGroup: Map<String, List<RecordMeta>> = emptyMap()
    private val groupFiles = mutableMapOf<String, FileChannel>()
    private var opened = false
    private var rows = 0

    override val recordCount: Int get() = synchronized(this) {
        check(opened) { "ISAM reader is closed" }
        rows
    }

    override val readRow: (Int) -> RowVec = { row -> synchronized(this) {
        check(opened) { "ISAM reader is closed" }
        require(row in 0 until rows) { "ISAM row is outside the data file" }
        try {
            val metadata = requireNotNull(constraints)
            val groups = columnsByGroup
            val groupBuffers = mutableMapOf<String, ByteArray>()
            for ((name, columns) in groups) {
                val length = groupLength(columns)
                val bytes = ByteArray(length)
                transfer(requireNotNull(groupFiles[name]), bytes, row.toLong() * length, read = true)
                groupBuffers[name] = bytes
            }
            // The returned row retains heap bytes and this open's schema, never a channel or mapping.
            metadata.size j { index: Int ->
                require(index in 0 until metadata.size) { "ISAM column is outside the row" }
                val column = metadata[index]
                val columns = groups.getValue(column.groupName)
                val offset = columns.takeWhile { it !== column }.sumOf { it.end - it.begin }
                val bytes = groupBuffers.getValue(column.groupName).copyOfRange(offset, offset + (column.end - column.begin))
                requireNotNull(column.decoder(bytes)) { "ISAM decoder returned null for ${column.name}" } j { column }
            }
        } catch (failure: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
            throw failure
        }
    } }

    @Synchronized
    override fun open() {
        if (opened) return
        try {
            metafile.open()
            val metadata = metafile.constraints
            val groups = metadata.view.groupBy { it.groupName }
            val primary = metadata.view.maxOf { it.groupId }
            var count: Long? = null
            for ((name, columns) in groups) {
                val length = groupLength(columns)
                val channel = channelFactory(groupPath(datafileFilename, columns, primary), setOf(StandardOpenOption.READ))
                groupFiles[name] = channel
                val size = channel.size()
                require(size >= 0 && size % length == 0L) { "ISAM group $name contains an incomplete row" }
                val groupCount = size / length
                require(groupCount <= Int.MAX_VALUE && (count == null || count == groupCount)) { "ISAM group row counts disagree or exceed cursor capacity" }
                count = groupCount
            }
            rows = requireNotNull(count).toInt()
            constraints = metadata
            columnsByGroup = groups
            opened = true
        } catch (failure: Throwable) {
            closeChannels(groupFiles.values, failure)
            groupFiles.clear()
            runCatching { metafile.close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
            throw failure
        }
    }

    @Synchronized
    override fun close() {
        if (!opened && groupFiles.isEmpty()) return
        opened = false
        rows = 0
        constraints = null
        columnsByGroup = emptyMap()
        try { closeChannels(groupFiles.values) } finally {
            groupFiles.clear()
            metafile.close()
        }
    }
}

class JvmIsamOperations internal constructor(private val channelFactory: IsamChannelFactory) : IsamOperations {
    constructor() : this(::openIsamChannel)
    private val metadataFiles = IsamFileOperations(channelFactory)

    override fun createReader(datafileFilename: String, metafileFilename: String, metafile: IsamMetaFileReader): IsamDataReader =
        JvmIsamDataReader(datafileFilename, metafileFilename, metafile, channelFactory)

    override fun write(cursor: Cursor, datafilename: String, varChars: Map<String, Int>, useMonocursorGroupings: Boolean) {
        require(cursor.size > 0) { "ISAM write needs at least one row to establish its schema" }
        val first = cursor[0]
        val metadata = IsamMetaFileReader.write("$datafilename.meta", rowMetadata(first), varChars,
            fileOps = metadataFiles, useMonocursorGroupings = useMonocursorGroupings)
        writeRows(cursor.view, datafilename, metadata, append = false, create = true)
    }

    override fun append(msf: Iterable<RowVec>, datafilename: String, varChars: Map<String, Int>,
                        transform: ((RowVec) -> RowVec)?, useMonocursorGroupings: Boolean) {
        val source = msf.iterator()
        if (!source.hasNext()) return
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
            fileOps = metadataFiles, useMonocursorGroupings = useMonocursorGroupings)
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
            for (row in rows) {
                require(count < Int.MAX_VALUE) { "ISAM append exceeds cursor capacity" }
                require(row.size == metadata.size) { "ISAM row width differs from metadata" }
                // Encode every group before admitting this row's first physical write.
                val buffers = mutableMapOf<String, ByteArray>()
                for ((name, columns) in groups) {
                    val bytes = ByteArray(groupLength(columns))
                    var offset = 0
                    for (column in columns) {
                        val index = metadata.view.indexOf(column)
                        val cell = row[index]
                        val source = cell.b()
                        require(source.name.toString() == column.name && source.type == column.type) { "ISAM row schema differs from metadata" }
                        val encoded = column.encoder(cell.a)
                        val width = column.end - column.begin
                        require(encoded.size <= width && (column.type.networkSize == null || encoded.size == width)) {
                            "ISAM encoded width differs from metadata for ${column.name}"
                        }
                        encoded.copyInto(bytes, offset)
                        offset += width
                    }
                    buffers[name] = bytes
                }
                try {
                    for ((name, bytes) in buffers) {
                        transfer(channels.getValue(name), bytes, offsets.getValue(name), read = false)
                    }
                } catch (caught: Throwable) {
                    // Restore the failed row across every group; completed rows remain appendable.
                    for ((name, channel) in channels) try {
                        channel.truncate(offsets.getValue(name))
                        channel.force(true)
                    } catch (cleanup: Throwable) {
                        if (cleanup !== caught) caught.addSuppressed(cleanup)
                    }
                    throw caught
                }
                for ((name, bytes) in buffers) offsets[name] = offsets.getValue(name) + bytes.size
                count++
            }
            channels.values.forEach { it.force(true) }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally { closeChannels(channels.values, failure) }
    }
}

actual fun defaultIsamOperations(): IsamOperations = JvmIsamOperations()
