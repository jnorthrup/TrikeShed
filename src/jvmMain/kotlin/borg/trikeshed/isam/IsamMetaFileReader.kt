package borg.trikeshed.isam

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.PlatformCodec.createDecoder
import borg.trikeshed.isam.meta.PlatformCodec.createEncoder
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

    /** reader function for the metadata file
     * 1. open the metafile descriptor for reading
     * 1. mmap the file into memory
     * 1. close the file descriptor
     * 1. parse the file into a collection of record constraints
     * 1. update recordlen
     */
    actual fun open() {
        val lines = Files.readAllLines(File(metafileFilename).toPath()).filterNot { it.trim().startsWith("#") }
        val coords = lines[0].split("\\s+".toRegex())
        val names =  lines[1].split("\\s+".toRegex())
        val types =  lines[2].split("\\s+".toRegex())
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
 actual companion object {
     /** metafile writer function
      * 1. open the metafile descriptor for writing
      * 1. write the file from a collection of record constraints
      * 1. close the file descriptor
      */
     actual fun write(metafilename:String,recordMetas: List<RecordMeta>) {
         val lines = mutableListOf<String>()
         lines.add("# format:  coords WS .. EOL names WS .. EOL TypeMememento WS ..")
         lines.add("# last coord is the recordlen")
         lines.add(recordMetas.joinToString(" ") { it.begin.toString() + " " + it.end })
         lines.add(recordMetas.joinToString(" ") { it.name })
         lines.add(recordMetas.joinToString(" ") { it.type.name })
         lines.add("# recordlen: ${recordMetas.last().end}")
         File(metafilename).writeText(lines.joinToString("\n"))
     }
 }
}