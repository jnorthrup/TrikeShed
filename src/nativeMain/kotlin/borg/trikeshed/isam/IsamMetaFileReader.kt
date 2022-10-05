package borg.trikeshed.isam

import borg.trikeshed.common.isam.RecordMeta
import borg.trikeshed.common.isam.meta.IOMemento
import borg.trikeshed.common.isam.meta.IOMemento.Companion.createDecoder
import borg.trikeshed.common.isam.meta.IOMemento.Companion.createEncoder
import kotlinx.cinterop.*
import platform.posix.*

/**
 * 1. create a class that can read the metadata file and create a collection of record constraints
 *
 * the isam metafile format follows this sample
 *
```
# format:  coords WS .. EOL names WS .. EOL TypeMememento WS ..
# last coord is the recordlen
0 12 12 24 24 32 32 40 40 48 48 56 56 64 64 72 72 76 76 84 84 92
Open_time Close_time Open High Low Close Volume Quote_asset_volume Number_of_trades Taker_buy_base_asset_volume Taker_buy_quote_asset_volume
IoInstant IoInstant IoDouble IoDouble IoDouble IoDouble IoDouble IoDouble IoInt IoDouble IoDouble
```
 */
actual class IsamMetaFileReader(val metafileFilename: String) {


    var recordlen: Int = -1
    lateinit var constraints: List<RecordMeta>

    /**
     * 1. open the metafile descriptor for reading
     1.  mmap the file into memory
     1. close the file descriptor
     1.  parse the file into a collection of record constraints
     1. update recordlen
     1. track meta isam field constraints:  name, type, begin, end , decoder, encoder
     1. sanity check begin and end against defined IOMemento networkSizes
     1. log (DEBUG) some dimension features and statistics about the record layouts
     1.  return the collection of record constraints
     */
 actual
    fun open()  {
        memScoped {
            val fd = open(metafileFilename, O_RDONLY)
            val stat = alloc<stat>()
            fstat(fd, stat.ptr)
            val size = stat.st_size.convert<Int>()
            val data: COpaquePointer = mmap(null, size.toULong(), PROT_READ, MAP_PRIVATE, fd, 0)!!
            close(fd)

            val lines = data.readBytes(size).decodeToString().split("[\r\n]+".toRegex())
            val coords = lines[2].split("\\s+".toRegex())
            val names = lines[3].split("\\s+".toRegex())
            val types = lines[4].split("\\s+".toRegex())
            recordlen = coords.last().toInt()

            //avoid using zip to construct the constraints because the zip will stop at the shortest list
            constraints = (0 until types.size - 1).map { i ->
                val begin = coords[i * 2].toInt()
                val end = coords[i * 2 + 1].toInt()
                val name = names[i]
                val type = IOMemento.valueOf(types[i])
                val size = end - begin
                val decoder: (ByteArray) -> Any? =  createDecoder(type, size)
                val encoder: (Any?) -> ByteArray = createEncoder(type, size)

                RecordMeta(name, type, begin, end, decoder, encoder)
            }
        }
    }
}


