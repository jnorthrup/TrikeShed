package borg.trikeshed.isam

import borg.trikeshed.Usable
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.meta
import borg.trikeshed.lib.*

actual class IsamDataFile actual constructor(
    datafileFilename: String,
    metafileFilename: String,
    metafile: IsamMetaFileReader,
    private val fileOps: FileOperations,
) : Usable, Cursor {
    actual val datafileFilename: String = datafileFilename
    actual val metafile: IsamMetaFileReader = metafile

   val recordlen: Int get() = metafile.recordlen

    actual override val a: Int
        get() = if (fileOps.exists(datafileFilename) && recordlen > 0) {
            fileOps.readAllBytes(datafileFilename).size / recordlen
        } else {
            0
        }

    actual override val b: (Int) -> RowVec = { row: Int ->
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

    actual override fun open() {
        metafile.open()
    }

    actual override fun close() {
        metafile.close()
    }

    actual companion object {
        actual fun write(cursor: Cursor, datafilename: String, varChars: Map<String, Int>, fileOps: FileOperations) {
            val metafilename = "$datafilename.meta"

            val meta0 = IsamMetaFileReader.write(metafilename, cursor.meta, varChars, fileOps)

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

        actual fun append(
            msf: Iterable<RowVec>,
            datafilename: String,
            varChars: Map<String, Int>,
            transform: ((RowVec) -> RowVec)?,
            fileOps: FileOperations,
        ): Unit {
            val metafilename = "$datafilename.meta"
            val meta0: Series<RecordMeta> = if (fileOps.exists(metafilename)) {
                val reader = IsamMetaFileReader(metafilename, fileOps)
                reader.open()
                reader.constraints
            } else {
                // No existing meta; cannot reliably append, so write new file from msf
                val rows = msf.map { transform?.invoke(it) ?: it }.toList()
                val cursor = rows.toSeries()
                write(cursor, datafilename, varChars, fileOps)
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
}
