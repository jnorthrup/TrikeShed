package borg.trikeshed.isam

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import kotlinx.cinterop.*
import platform.posix.*

actual class IsamDataFile actual constructor(
    actual val datafileFilename: String,
    metafileFilename: String,
    actual val metafile: IsamMetaFileReader
) : Cursor {


    val recordlen: Int by lazy {
        this.metafile.recordlen.also {
            require(it > 0) { "recordlen must be > 0" }
        }

    } // unfortunately due to seperatoin of ctor and open, this is not immutable
    val constraints: Series<RecordMeta> by lazy { metafile.constraints }
    private lateinit var data: COpaquePointer
    var fileSize: Long = -1

    private var first = true
    actual fun open() {
        if (!first) return
        memScoped {
            val fd = open(datafileFilename, O_RDONLY)
            val stat = alloc<stat>()
            fstat(fd, stat.ptr)
            fileSize = stat.st_size.toLong()

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

    override val b: (Int) -> Join<Int, (Int) -> Join<*, () -> RecordMeta>>

    override fun toString(): String =
        "IsamDataFile(metafile=$metafile, recordlen=$recordlen, constraints=$constraints, datafileFilename='$datafileFilename', fileSize=$fileSize)"

    actual fun close() {
        memScoped {
            munmap(data, fileSize.toULong())
        }
    }


    actual companion object {
        actual fun write(cursor: Cursor, datafilename: String): Unit = memScoped {
            val metafilename = "$datafilename.meta"

            IsamMetaFileReader.write(metafilename, cursor.meta.map { colMeta: ColMeta -> colMeta as RecordMeta })

            //open RandomAccessDataFile

            val data = fopen(datafilename, "w")

            //create row buffer
            val meta = cursor.meta α { it as RecordMeta }
            val rowLen = meta.last().end
            val rowBuffer = ByteArray(rowLen)

            val clears = meta.`▶`.withIndex().filter { it.value.type.networkSize == null }.map { it.index }.toIntArray()

            //write rows
            for (y in 0 until cursor.a) {
                val rowData = cursor.row(y).left

                for (x in 0 until cursor.meta.size) {
                    val colMeta = meta[x]
                    val colData = rowData[x]
                    val colBytes = colMeta.encoder(colData)
                    colBytes.copyInto(rowBuffer, colMeta.begin, 0, colBytes.size)
                    if (x in clears && colBytes.size < colMeta.end - colMeta.begin)   //write 1 zero
                        rowBuffer[colMeta.begin + colBytes.size] = 0
                }
                val fwrite = fwrite(rowBuffer.refTo(0), 1, rowLen.toULong(), data)
            }
            val fclose = fclose(data)
        }.let {}
    }

    init {
        this.b = { row ->
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
    }
}


