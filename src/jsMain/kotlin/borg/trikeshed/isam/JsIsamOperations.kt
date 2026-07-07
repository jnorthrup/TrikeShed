package borg.trikeshed.isam

import borg.trikeshed.common.Usable
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.JsFileOperations
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.*
import borg.trikeshed.isam.meta.IsamMetaFileReader
import borg.trikeshed.lib.*

class JsIsamDataReader(
    val datafileFilename: String,
    val metafileFilename: String,
    val metafile: IsamMetaFileReader
) : IsamDataReader {
    private val fileOps: FileOperations = JsFileOperations()
    private val recordlen: Int get() = metafile.recordlen

    override val recordCount: Int
        get() = if (fileOps.exists(datafileFilename) && recordlen > 0) {
            fileOps.readAllBytes(datafileFilename).size / recordlen
        } else {
            0
        }

    override val readRow: (Int) -> RowVec = { row: Int ->
        val bytes: ByteArray = fileOps.readAllBytes(datafileFilename)
        val base: Int = row * recordlen
        metafile.constraints.size j { col ->
            val recordMeta: RecordMeta = metafile.constraints[col]
            val start = base + recordMeta.begin
            val len = recordMeta.end - recordMeta.begin
            val d: ByteArray = ByteArray(len)
            bytes.copyInto(d, 0, start, start + len)
            recordMeta.decoder(d) j { -> recordMeta }
        }
    }

    override fun open() {
        metafile.open()
    }

    override fun close() {
        metafile.close()
    }
}

class JsIsamOperations : IsamOperations {
    override fun createReader(
        datafileFilename: String,
        metafileFilename: String,
        metafile: IsamMetaFileReader
    ): IsamDataReader = JsIsamDataReader(datafileFilename, metafileFilename, metafile)

    override fun write(cursor: Cursor, datafilename: String, varChars: Map<String, Int>) {
        val fileOps: FileOperations = JsFileOperations()
        val metafilename = "$datafilename.meta"

        val meta0 = IsamMetaFileReader.write(metafilename, cursor.meta, varChars)

        val last = meta0.last()
        val meta = (meta0 α {
            val encoder = it.type.createEncoder(it.end - it.begin)
            RecordMeta(it.name, it.type, it.begin, it.end, encoder = encoder)
        }).toArray()
        val rowLen = last.end

        val out = ByteArray(rowLen * cursor.size)
        val rowBuf = ByteArray(rowLen)
        var offset = 0
        cursor.iterator().forEach { rowVec ->
            WireProto.writeToBuffer(rowVec, rowBuf, meta0)
            rowBuf.copyInto(out, offset, 0, rowLen)
            offset += rowLen
        }
        fileOps.write(datafilename, out)
    }

    override fun append(
        msf: Iterable<RowVec>,
        datafilename: String,
        varChars: Map<String, Int>,
        transform: ((RowVec) -> RowVec)?
    ) {
        val fileOps: FileOperations = JsFileOperations()
        val metafilename = "$datafilename.meta"
        val meta0: Series<RecordMeta> = if (fileOps.exists(metafilename)) {
            val reader = IsamMetaFileReader(metafilename)
            reader.open()
            reader.constraints
        } else {
            // No existing meta; cannot reliably append, so write new file from msf
            val rows = msf.map { transform?.invoke(it) ?: it }.toList()
            val cursor = rows.toSeries()
            write(cursor, datafilename, varChars)
            return
        }

        val last = meta0.last()
        val rowLen = last.end

        val existing = if (fileOps.exists(datafilename)) fileOps.readAllBytes(datafilename) else ByteArray(0)
        val out = ByteArray(existing.size + (rowLen * msf.count()))
        existing.copyInto(out, 0, 0, existing.size)
        var offset = existing.size
        msf.forEach { rowVec ->
            val rv = transform?.invoke(rowVec) ?: rowVec
            val rowBuf = ByteArray(rowLen)
            WireProto.writeToBuffer(rv, rowBuf, meta0).copyInto(out, offset, 0, rowLen)
            offset += rowLen
        }
        fileOps.write(datafilename, out)
    }
}

actual fun defaultIsamOperations(): IsamOperations = JsIsamOperations()
