/**
 * kotlin-native file to perform the following goal:
 *
 *  this parses the metadata file found at file  ~/mpdata/import/klines/1m/BNB/USDT/final-BNB-USDT-1m.isam.meta
 *
 * this opens an network-endian ISAM binary volume file found at
 *  file  ~/mpdata/import/klines/1m/BNB/USDT/final-BNB-USDT-1m.isam
 *
 *  the file is opened using MMAP access flags readonly
 *
 *  the code will parse the metadata format and create a collection of record constraints allowing it to load bytes
 *  from random access field offsets using mmap pointers
 *
 * the metadata format is a plain text file.
 *
 * the first two lines are comments written in english which hint at the metadata file text tokenization of the following 3 lines
 * the third line is the sequence of begin+end offsets of the fields. the offsets are 1-based.  the offsets are separated by WS tokens.
 * the last token on the third line is the number of bytes in the record coincident with the end of the last field
 * the 4th line is the sequence of field names separated by WS tokens
 * the 5th line is the encoder types corresponding to the above sequences of fields separated by WS tokens
 *
 * an example of the metadata file is shown below:
 * ```

# format:  coords WS .. EOL names WS .. EOL TypeMememento WS ..
# last coord is the recordlen
0 12 12 24 24 32 32 40 40 48 48 56 56 64 64 72 72 76 76 84 84 92
Open_time Close_time Open High Low Close Volume Quote_asset_volume Number_of_trades Taker_buy_base_asset_volume Taker_buy_quote_asset_volume
IoInstant IoInstant IoDouble IoDouble IoDouble IoDouble IoDouble IoDouble IoInt IoDouble IoDouble

```

this kotlin native program uses the linux posix c api and the kotlin native cinterop tool to create a kotlin
native library that can be used to read the metadata file and the binary volume file
 */
import IOMemento.*
import kotlinx.cinterop.*
import kotlinx.datetime.Instant
import platform.posix.*


interface TypeMemento {
    val networkSize: Int?
}

enum class IOMemento(override val networkSize: Int? = null) : TypeMemento {
    IoBoolean(1),
    IoByte(1),
    IoInt(4),
    IoLong(8),
    IoFloat(4),
    IoDouble(8),
    IoString,
    IoLocalDate(8),

    /**
     * 12 bytes of storage, first epoch seconds Long , then nanos Int
     */
    IoInstant(12),
    IoNothing
    ;
}

data class RecordConstraint(val name: String, val type: IOMemento, val begin: Int, val end: Int)

fun main(args: Array<String>) {
    memScoped {
        val metafileName = ("/home/jim/mpdata/import/klines/1m/BNB/USDT/final-BNB-USDT-1m.isam.meta")
        val metafile = fopen(metafileName, "r")

        /*
         * the metadata format is a plain text file.
       *
       * the first two lines are comments written in english which hint at the metadata file text tokenization of the following 3 lines
       * the third line is the sequence of begin+end offsets of the fields. the offsets are 1-based.  the offsets are separated by WS tokens.
       * the last token on the third line is the number of bytes in the record coincident with the end of the last field
       * the 4th line is the sequence of field names separated by WS tokens
       * the 5th line is the encoder types corresponding to the above sequences of fields separated by WS tokens
       *
       * an example of the metadata file is shown below:
       * ```

      # format:  coords WS .. EOL names WS .. EOL TypeMememento WS ..
      # last coord is the recordlen
      0 12 12 24 24 32 32 40 40 48 48 56 56 64 64 72 72 76 76 84 84 92
      Open_time Close_time Open High Low Close Volume Quote_asset_volume Number_of_trades Taker_buy_base_asset_volume Taker_buy_quote_asset_volume
      IoInstant IoInstant IoDoub    le IoDouble IoDouble IoDouble IoDouble IoDouble IoInt IoDouble IoDouble

      ```
      */

        //load the whole metafile and parse it into a list of RecordConstraints
        val metafileStat = memScope.alloc<stat>()
        stat(metafileName, metafileStat.ptr)
        val metafileSize = metafileStat.st_size
        val metafileBytes = ByteArray(metafileSize.toInt())
        fread(metafileBytes.refTo(0), 1, metafileSize.toULong(), metafile)
        val metafileString = metafileBytes.decodeToString()
        val metafileLines = metafileString.split("[\r\n]".toRegex())
        val metafileCoords = metafileLines[2].split("[ \t]+".toRegex())
        val metafileNames = metafileLines[3].split("[ \t]+".toRegex())
        val metafileTypes = metafileLines[4].split("[ \t]+".toRegex())
        val metafileConstraints = metafileCoords.zip(metafileNames).zip(metafileTypes).map {
            val (a, b) = it
            val (coords, name) = a
            val type = IOMemento.valueOf(b)
            val begin = coords.toInt()
            val end = metafileCoords[metafileCoords.indexOf(coords) + 1].toInt()
            RecordConstraint(name, type, begin, end)
        }

        println(metafileConstraints)

        val volfileName = ("/home/jim/mpdata/import/klines/1m/BNB/USDT/final-BNB-USDT-1m.isam")

        //open volfile via MMAP and allocate a recordbuffer for cursor seek/read to obtain binary data from the records from the constrants
        val volfile = fopen(volfileName, "r")
        val volfileStat = memScope.alloc<stat>()
        stat(volfileName, volfileStat.ptr)
        val volfileSize = volfileStat.st_size

        //recordsize
        val recordSize = metafileConstraints.last().end
        val recordBuffer = ByteArray(recordSize)

        //mmap the volfile
        val volfileMmap = mmap(null, volfileSize.toULong(), PROT_READ, MAP_PRIVATE, fileno(volfile), 0)

        //create a record count and report if the volume is not aligned on record boundary
        val recordCount = volfileSize / recordSize
        if (volfileSize % recordSize != 0L) {
            println("volume file size is not aligned on record boundary")
        }

        fun readRecord(recordNumber: Int): ByteArray {
            val recordOffset = recordNumber * recordSize
            val recordBuffer = ByteArray(recordSize)

            //access the field memory via the mmap
            val recordMmap = volfileMmap.rawValue + recordOffset.toLong()

            //copy the record into the record buffer
            memcpy(recordBuffer.refTo(0), recordMmap as CValuesRef<*>, recordSize.toULong())
            return recordBuffer
        }

        //decode the first record
        val record0 = readRecord(0)
        val record0Constraints = metafileConstraints.map {
            val begin = it.begin
            val end = it.end
            val type = it.type
            val name = it.name
            //marshal network-endian binary values from meta layout

            val value = when (type) {
                IoBoolean -> record0[begin] == 1.toByte()
                IoByte -> record0[begin]
                IoInt -> record0.getIntAt(begin)
                IoLong -> record0.getLongAt(begin)
                IoFloat -> record0.getFloatAt(begin)
                IoDouble -> record0.getDoubleAt(begin)
                IoString -> record0.sliceArray(begin until end).decodeToString()
                IoLocalDate -> record0.getLongAt(begin)
                IoInstant -> Instant.fromEpochSeconds(
                    record0.getLongAt(begin),
                    record0.getIntAt(begin + 8)
                )

                IoNothing -> null
            }
            name to value
        }.toMap()
        println(record0Constraints)
    }
}
