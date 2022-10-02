package borg.trikeshed.common.isam

import borg.trikeshed.common.isam.meta.IOMemento
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlinx.cinterop.*
import platform.posix.*

class IsamDataFile(
     val datafileFilename: String,
    metafileFilename: String = datafileFilename + ".meta",
    val metafile: IsamMetaFileReader = IsamMetaFileReader(metafileFilename)
) : Series<Series<Join<*, () -> RecordMeta>>> {
    val recordlen = metafile.recordlen
    val constraints = metafile.constraints
    private lateinit var data: COpaquePointer
    var fileSize: Long = -1

    init {
        memScoped {
            val fd = open(datafileFilename, O_RDONLY)
            val stat = alloc<stat>()
            fstat(fd, stat.ptr)
            fileSize = stat.st_size

            data = mmap(null, fileSize.toULong(), PROT_READ, MAP_PRIVATE, fd, 0)!!
            close(fd)

            //report on record alignment of the file
            val alignment = fileSize % recordlen
            if (alignment != 0L) {
                println("WARN: file $datafileFilename is not aligned to recordlen $recordlen")
            }else
                println("DEBUG: file $datafileFilename is aligned to recordlen $recordlen")

            //mention record counts and percentages of each field type by record byte occupancy
            val fieldCounts = mutableMapOf<IOMemento, Int>()
            val fieldOccupancy = mutableMapOf<IOMemento, Int>()
            constraints.forEach { constraint ->
                val count = fieldCounts.getOrPut(constraint.type) { 0 }
                fieldCounts[constraint.type] = count + 1
                val occupancy = fieldOccupancy.getOrPut(constraint.type) { 0 }
                fieldOccupancy[constraint.type] = occupancy + constraint.end - constraint.begin
            }
            println("DEBUG: file $datafileFilename has ${fileSize / recordlen} records")
            fieldCounts.forEach { (type, count) ->
                val occupancy = fieldOccupancy[type]!!
                println("DEBUG: file $datafileFilename has $count fields of type $type occupying $occupancy bytes")
            }
        }
    }

    override val a: Int = fileSize.toInt() / recordlen
    override val b: (Int) -> Join<Int, (Int) -> Join<*, () -> RecordMeta>> = { row ->
        memScoped {

       val d2 = data.toLong() + (row * recordlen)

            constraints.size j {col->
                constraints[col]. let { recordMeta ->
                    val d4 = d2 + recordMeta.begin
                    val d5:COpaquePointer = d4.toCPointer()!!
                    val d6: ByteArray = d5.readBytes(recordMeta.end - recordMeta.begin)
                    recordMeta.decoder(d6)!! j { recordMeta }
                }
            }
        }
    }

    override fun toString(): String {
        return "IsamDataFile(metafile=$metafile, recordlen=$recordlen, constraints=$constraints, datafileFilename='$datafileFilename', fileSize=$fileSize)"
    }
}
