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
        private val readBool = { value: ByteArray -> value[0] == 1.toByte() }
        private val readByte = { value: ByteArray -> value[0] }
        private val readInt = { value: ByteArray -> value.networkOrderGetIntAt(0) }
        private val readLong = { value: ByteArray -> value.networkOrderGetLongAt(0) }
        private val readFloat = { value: ByteArray -> value.networkOrderGetFloatAt(0) }
        private val readDouble = { value: ByteArray -> value.networkOrderGetDoubleAt(0) }
        private val readInstant = { value: ByteArray ->
            Instant.fromEpochSeconds(
                value.networkOrderGetLongAt(0),
                value.networkOrderGetIntAt(0)
            )
        }
        private val readLocalDate = { value: ByteArray ->
            LocalDate.fromEpochDays(
                value.networkOrderGetLongAt(0).toLong().toInt()
            )
        }
        private val readString = { value: ByteArray -> value.decodeToString() }
        private val readNothing = { _: ByteArray -> null }
        private val writeBool = { value: Any? -> byteArrayOf(if (value as Boolean) 1 else 0) }
        private val writeByte = { value: Any? -> byteArrayOf(value as Byte) }
        private val writeInt = { value: Any? -> ByteArray(4).apply { networkOrderSetIntAt(0, value as Int) } }
        private val writeLong = { value: Any? -> ByteArray(8).apply { networkOrderSetLongAt(0, value as Long) } }
        private val writeFloat = { value: Any? -> ByteArray(4).apply { networkOrderSetFloatAt(0, value as Float) } }
        private val writeDouble = { value: Any? -> ByteArray(8).apply { networkOrderSetDoubleAt(0, value as Double) } }
        private val writeInstant = { value: Any? ->
            ByteArray(12).apply {
                networkOrderSetLongAt(
                    0,
                    (value as Instant).epochSeconds
                ); networkOrderSetIntAt(0, value.nanosecondsOfSecond)
            }
        }

        private
        val writeLocalDate = { value: Any? ->
            ByteArray(8).apply<ByteArray> {
                ->
                networkOrderSetLongAt(
                    0,
                    (value as LocalDate).toEpochDays().toLong()
                )
            }
        }
        private val writeString = { value: Any? -> (value as String).encodeToByteArray() }
        private val writeNothing = { _: Any? -> ByteArray(0) }
        fun createEncoder(type: IOMemento, size: Int): (Any?) -> ByteArray {
            //must use corresponding  networkOrderSetXXX functions to set the bytes in the ByteArray
            return when (type) {
                IOMemento.IoBoolean -> writeBool
                IOMemento.IoByte -> writeByte

                IOMemento.IoInt -> writeInt
                IOMemento.IoLong -> writeLong
                IOMemento.IoFloat -> writeFloat
                IOMemento.IoDouble -> writeDouble
                IOMemento.IoString -> writeString
                IOMemento.IoInstant -> writeInstant
                IOMemento.IoLocalDate -> writeLocalDate
                IOMemento.IoNothing -> writeNothing

            }
        }
    }


    fun createDecoder(
        type: IOMemento, size: Int
    ): (ByteArray) -> Any? {
        return when (type) {
            // all values must be read and written in network endian order
            // we must call the marshalling functions inside the NetworkOrder ByteArray extension functions to ensure this

            IOMemento.IoBoolean -> readBool
            IOMemento.IoByte -> readByte
            IOMemento.IoInt -> readInt
            IOMemento.IoLong -> readLong
            IOMemento.IoFloat -> readFloat
            IOMemento.IoDouble -> readDouble
            IOMemento.IoInstant -> readInstant
            IOMemento.IoLocalDate -> readLocalDate
            IOMemento.IoString -> readString
            IOMemento.IoNothing -> readNothing
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


