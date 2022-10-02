package borg.trikeshed.common.isam

import borg.trikeshed.common.isam.meta.IOMemento
import borg.trikeshed.lib.*
import kotlinx.cinterop.*
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
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
 *
 * the first two lines are comments written in english which hint at the metadata file text tokenization of the following 3 lines
 * the third line is the sequence of begin+end offsets of the fields. the offsets are 1-based.  the offsets are separated by WS tokens.
 * the last token on the third line is the number of bytes in the record coincident with the end of the last field
 * the 4th line is the sequence of field names separated by WS tokens
 * the 5th line is the encoder types corresponding to the above sequences of fields separated by WS tokens
 *
 * the class will read the metadata file and create a collection of record constraints
 *
 * the class will also create a collection of field names and a collection of field types
 *
 * the class will also create a collection of field decoders
 *
 * the class will also create a collection of field encoders
 *
 * the class will also create a collection of field offsets
 *
 * the class will also create a collection of field sizes
 */
class IsamMetaFileReader(val metafileFilename: String) {
    companion object {
        fun createEncoder(type: IOMemento, size: Int): (Any?) -> ByteArray {
            //must use corresponding  networkOrderSetXXX functions to set the bytes in the ByteArray
            return when (type) {
                IOMemento.IoBoolean -> { value -> byteArrayOf(if (value as Boolean) 1 else 0) }
                IOMemento.IoByte -> { value -> byteArrayOf(value as Byte) }
                IOMemento.IoInt -> { value -> ByteArray(size).apply { networkOrderSetIntAt(0, value as Int) } }
                IOMemento.IoLong -> { value -> ByteArray(size).apply { networkOrderSetLongAt(0, value as Long) } }
                IOMemento.IoFloat -> { value -> ByteArray(size).apply { networkOrderSetFloatAt(0, value as Float) } }
                IOMemento.IoDouble -> { value -> ByteArray(size).apply { networkOrderSetDoubleAt(0, value as Double) } }
                IOMemento.IoInstant -> { value ->
                    ByteArray(size).apply {
                        networkOrderSetLongAt(0, (value as Instant).epochSeconds)
                        networkOrderSetIntAt(0, value.nanosecondsOfSecond)
                    }
                }
                IOMemento.IoLocalDate -> { value ->
                    ByteArray(size).apply {
                        networkOrderSetLongAt(
                            0,
                            (value as LocalDate).toEpochDays().toLong()
                        )
                    }
                }

                IOMemento.IoString -> { value -> (value as String).encodeToByteArray() }
                IOMemento.IoNothing -> { _ -> ByteArray(0) }


            }
        }

        fun createDecoder(
            type: IOMemento, size: Int
        ): (ByteArray) -> Any? {
            return when (type) {
                // all values must be read and written in network endian order
                // we must call the marshalling functions inside the NetworkOrder ByteArray extension functions to ensure this

                IOMemento.IoBoolean -> { value -> value[0] == 1.toByte() }
                IOMemento.IoByte -> { value -> value[0] }
                IOMemento.IoInt -> { value -> value.networkOrderGetIntAt(0) }
                IOMemento.IoLong -> { value -> value.networkOrderGetLongAt(0) }
                IOMemento.IoFloat -> { value -> value.networkOrderGetFloatAt(0) }
                IOMemento.IoDouble -> { value -> value.networkOrderGetDoubleAt(0) }
                IOMemento.IoString -> { value -> value.decodeToString() }
                IOMemento.IoLocalDate -> { value -> LocalDate.fromEpochDays(value.networkOrderGetLongAt(0).toInt()) }
                IOMemento.IoInstant -> { value ->
                    Instant.fromEpochSeconds(
                        value.networkOrderGetLongAt(0),
                        value.networkOrderGetIntAt(8)
                    )
                }

                IOMemento.IoNothing -> { _ -> null }


            }
        }

    }

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
    init {
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
                val decoder = createDecoder(type, size)
                val encoder = createEncoder(type, size)

                RecordMeta(name, type, begin, end, decoder, encoder)
            }
        }
    }
}


