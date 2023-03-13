package borg.trikeshed.isam

import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.*
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import kotlinx.cinterop.*
import platform.posix.*
import simple.PosixFile
import simple.PosixOpenOpts

actual class IsamDataFile actual constructor(
    datafileFilename: String,
    metafileFilename: String,
    metafile: IsamMetaFileReader,
) : Usable, Cursor {
    actual val datafileFilename: String = datafileFilename
    actual val metafile: IsamMetaFileReader = metafile


    val recordlen: Int by lazy {
        this.metafile.recordlen.also {
            require(it > 0) { "recordlen must be > 0" }
        }

    } // unfortunately due to seperatoin of ctor and open, this is not immutable
    val constraints: Series<RecordMeta> by lazy { metafile.constraints }
    private lateinit var data: COpaquePointer
    var fileSize: Long = -1

    private var first = true
    actual override fun open() {
        if (!first) return
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

            // mention record counts and percentages of each field type by record byte occupancy and by file byte occupancy, and percentage of file
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

    override val a: Int
        get() {
            open().let {
                return (fileSize / recordlen).toInt()
            }
        }
    override val b: (Int) -> Join<Int, (Int) -> Join<Any, () -> ColumnMeta>> = { row ->
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

    override fun toString(): String =
        "IsamDataFile(metafile=$metafile, recordlen=$recordlen, constraints=$constraints, datafileFilename='$datafileFilename', fileSize=$fileSize)"

    actual override fun close() {
        memScoped {
            munmap(data, fileSize.toULong())
        }
    }

    actual companion object {

        actual fun write(cursor: Cursor, datafilename: String, varChars: Map<String, Int>) {
            val metafilename = "$datafilename.meta"

            val meta0 = IsamMetaFileReader.write(metafilename, cursor.meta, varChars)

            //open RandomAccessDataFile

            val data = PosixFile(
                datafilename,
                PosixOpenOpts.withFlags(PosixOpenOpts.O_Creat, PosixOpenOpts.O_Trunc, PosixOpenOpts.O_Rdwr)
            )

            //create row buffer
            meta0.debug {
                logDebug { "toIsam: " + it.toList() }
            }

            val last = meta0.last()
            val meta = (meta0 α {
                val encoder = it.type.createEncoder(it.end - it.begin)
                RecordMeta(it.name, it.type, it.begin, it.end, encoder = encoder)
            }).toArray()
            val rowLen = last.end

            val clears = meta.withIndex().filter {
                it.value.type.networkSize == null
            }.map { it.index }.toIntArray()

            val rowBuffer1 = ByteArray(rowLen)
            val rowBuffer = rowBuffer1

            //write rows
            cursor.iterator().forEach { rowVec ->
                WireProto.writeToBuffer(rowVec, rowBuffer, meta0)

                data.write(rowBuffer)
            }
            data.close()
        }

        actual fun append(
            cseq: Iterator<RowVec>,
            meta: Series<ColumnMeta>,
            datafilename: String,
            varChars: Map<String, Int>,
        ) {
            val metafilename = "$datafilename.meta"

            //            TODO("not assume we have to write this file for this call.  if it exists, verify it and use it")
            val meta0 = IsamMetaFileReader.write(metafilename, meta, varChars)

            //open RandomAccessDataFile
            val data = PosixFile(
                datafilename,
                PosixOpenOpts.withFlags(PosixOpenOpts.O_Creat, PosixOpenOpts.O_Append, PosixOpenOpts.O_WrOnly)
            )

            meta0.debug {
                logDebug { "toIsam: " + it.toList() }
            }

            val last = meta0.last()

            val rowLen = last.end


            val rowBuffer = ByteArray(rowLen) { 0 }


            //write rows
            cseq .forEach { rowVec ->
                WireProto.writeToBuffer(rowVec, rowBuffer, meta0)
                data.write(rowBuffer)
            }
            data.close()
        }
    }
}