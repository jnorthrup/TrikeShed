package borg.trikeshed.isam

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.PlatformCodec
import borg.trikeshed.lib.*
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

    val recordlen: Int by lazy {
        constraints.last().end
    }
    val constraints: Series<RecordMeta> by lazy {
        open()
        constraints1.toSeries()
    }
    var opened = true

    private lateinit var constraints1: List<RecordMeta>
    actual fun open() {
        if (!opened) return
        //open
        val fd = open(metafileFilename, O_RDONLY)
        memScoped {
            //read the entire file into
            val stat = alloc<stat>()
            fstat(fd, stat.ptr)
            val size = stat.st_size.convert<Int>()
            val buf = allocArray<ByteVar>(size)
            read(fd, buf, size.convert<size_t>())
            //close
            close(fd)
            //use readBytes and decodeString to read the lines into
            val lines =
                buf.readBytes(size).decodeToString().lines().filterNot { it.trim().startsWith("#") }.map(String::trim)
            //split on \s+
            val coords = lines[0].split("\\s+".toRegex())
            val names = lines[1].split("\\s+".toRegex())
            val types = lines[2].split("\\s+".toRegex())


            this@IsamMetaFileReader.constraints1 = names.zip(types).mapIndexed { index, (name, type) ->
                val begin = coords[2 * index].toInt()
                val end = coords[2 * index + 1].toInt()
                val ioMemento = IOMemento.valueOf(type)
                //use PlatformCodec to get the decoder and encoder
                val decoder = PlatformCodec.createDecoder(ioMemento, end - begin)
                val encoder = PlatformCodec.createEncoder(ioMemento, end - begin)
                RecordMeta(name, ioMemento, begin, end, decoder, encoder)
            }
        }
    }

    //toString
    actual override fun toString(): String {
        return "IsamMetaFileReader(metafileFilename='$metafileFilename', recordlen=$recordlen, constraints=$constraints)"
    }


}