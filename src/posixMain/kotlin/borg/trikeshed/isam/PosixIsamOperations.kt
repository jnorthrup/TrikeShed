@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.isam

import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.meta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IsamMetaFileReader
import borg.trikeshed.lib.*
import kotlinx.cinterop.*
import platform.posix.*
import simple.PosixFile
import simple.PosixOpenOpts

class PosixIsamDataReader(
    val datafileFilename: String,
    val metafileFilename: String,
    val metafile: IsamMetaFileReader
) : IsamDataReader {
    private val recordlen: Int by lazy {
        metafile.recordlen.also {
            require(it > 0) { "recordlen must be > 0" }
        }
    }
    private val constraints: Series<RecordMeta> by lazy { metafile.constraints }
    private lateinit var data: COpaquePointer
    private var fileSize: Long = -1
    private var first = true

    override val recordCount: Int
        get() {
            open()
            return (fileSize / recordlen).toInt()
        }

    override val readRow: (Int) -> RowVec = { row ->
        memScoped {
            val d2 = data.toLong() + (row * recordlen)
            constraints.size j { col ->
                constraints[col].let { recordMeta ->
                    val d4 = d2 + recordMeta.begin
                    val d5: COpaquePointer = d4.toCPointer()!!
                    val d6: ByteArray = d5.readBytes(recordMeta.end - recordMeta.begin)
                    recordMeta.decoder(d6)!! j { recordMeta }
                }
            }
        }
    }

    override fun open() {
        if (!first) return
        first = false
        metafile.open()
        memScoped {
            val fd = open(datafileFilename, O_RDONLY)
            val stat = alloc<stat>()
            fstat(fd, stat.ptr)
            fileSize = stat.st_size

            require(fileSize % recordlen == 0L) { "fileSize must be a multiple of recordlen" }

            data = mmap(null, fileSize.toULong(), PROT_READ, MAP_PRIVATE, fd, 0)!!
            close(fd)

            // report on record alignment of the file
            val alignment = fileSize % recordlen
            if (alignment != 0L) {
                println("WARN: file $datafileFilename is not aligned to recordlen $recordlen")
            } else
                println("DEBUG: file $datafileFilename is aligned to recordlen $recordlen")

            println("DEBUG: each record is ${recordlen.toLong().humanReadableByteCountIEC} bytes long")

            val fieldCounts = mutableMapOf<IOMemento, Int>()
            val fieldOccupancy = mutableMapOf<IOMemento, Int>()
            constraints.forEach { constraint ->
                val count = fieldCounts.getOrPut(constraint.type) { 0 }
                fieldCounts[constraint.type] = count + 1
                val occupancy = fieldOccupancy.getOrPut(constraint.type) { 0 }
                fieldOccupancy[constraint.type] = occupancy + constraint.end - constraint.begin
            }
            val recordCount = fileSize / recordlen
            println("DEBUG: file  $datafileFilename has $recordCount records in ${fileSize.humanReadableByteCountIEC}")
            fieldCounts.forEach { (type, count) ->
                val occupancy = fieldOccupancy[type]!!
                println("DEBUG: file $datafileFilename has $count fields of type $type occupying $occupancy bytes (${occupancy * 100 / recordlen}%) of each record (${(occupancy * recordCount).humanReadableByteCountSI} in the file)")
            }
        }
    }

    override fun close() {
        memScoped {
            munmap(data, fileSize.toULong())
        }
        metafile.close()
    }
}

class PosixIsamOperations : IsamOperations {
    override fun createReader(
        datafileFilename: String,
        metafileFilename: String,
        metafile: IsamMetaFileReader
    ): IsamDataReader = PosixIsamDataReader(datafileFilename, metafileFilename, metafile)

    override fun write(cursor: Cursor, datafilename: String, varChars: Map<String, Int>) {
        val metafilename = "$datafilename.meta"

        val meta0 = IsamMetaFileReader.write(metafilename, cursor.meta, varChars)

        val data = PosixFile(
            datafilename,
            PosixOpenOpts.withFlags(PosixOpenOpts.O_Creat, PosixOpenOpts.O_Trunc, PosixOpenOpts.O_Rdwr)
        )

        meta0.debug {
            logDebug { "toIsam: " + it.toList() }
        }

        val last = meta0.last()
        val meta = (meta0 α {
            val encoder = it.type.createEncoder(it.end - it.begin)
            RecordMeta(it.name, it.type, it.begin, it.end, encoder = encoder)
        }).toArray()
        val rowLen = last.end

        val rowBuffer1 = ByteArray(rowLen)

        cursor.iterator().forEach { rowVec ->
            WireProto.writeToBuffer(rowVec, rowBuffer1, meta0)
            data.write(rowBuffer1)
        }
        data.close()
    }

    override fun append(
        msf: Iterable<RowVec>,
        datafilename: String,
        varChars: Map<String, Int>,
        transform: ((RowVec) -> RowVec)?
    ) {
        TODO("append not implemented for PosixIsamOperations")
    }
}

actual fun defaultIsamOperations(): IsamOperations = PosixIsamOperations()
