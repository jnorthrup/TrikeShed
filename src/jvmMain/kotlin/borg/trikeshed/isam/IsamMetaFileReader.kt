package borg.trikeshed.isam

import borg.trikeshed.common.isam.RecordMeta
import borg.trikeshed.common.isam.meta.IOMemento
import borg.trikeshed.common.isam.meta.IOMemento.Companion.createDecoder
import borg.trikeshed.common.isam.meta.IOMemento.Companion.createEncoder
import java.io.File
import java.nio.file.Files

/**
 * A class that can read the metadata file and create a collection of record constraints
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

    actual fun open() {
        val lines = Files.readAllLines(File(metafileFilename).toPath()).filterNot { it.trim().startsWith("#") }
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
            val decoder: (ByteArray) -> Any? = createDecoder(type, size)
            val encoder: (Any?) -> ByteArray = createEncoder(type, size)
            RecordMeta(name, type, begin, end, decoder, encoder)
        }
    }
}