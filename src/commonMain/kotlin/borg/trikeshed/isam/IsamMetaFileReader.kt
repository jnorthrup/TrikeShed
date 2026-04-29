package borg.trikeshed.isam

import borg.trikeshed.common.Files
import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.TypeMemento
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import kotlin.math.min

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

the ebnf we can use is:

```
metafile :=  (coords WS names WS .. EOL)*
coords :=  (coord WS)* coord
coord :=  number
names :=  (name WS)* name
name :=  string
TypeMemento :=  (IoType WS)* IoType
IoType :=  IoInstant | IoDouble | IoString | IoInt
```
 * 2. create a class that can create the binary file
 *
 * the binary file format follows this sample
 *
 */
@Deprecated("Use borg.trikeshed.parse.confix.Isam3FileReader for ISAM3 YAML metadata")
class IsamMetaFileReader(val metafileFilename: String) :Usable{

    val recordlen: Int by lazy {
        constraints.last().end
    }
    val constraints: Series<RecordMeta> by lazy {
        open()
        constraints1.toSeries()
    }

   lateinit var constraints1: List<RecordMeta>
    override fun open() {
        //use readBytes and decodeString to read the lines into
//        val lines = buf.readBytes(size).decodeToString().lines().filterNot { it.trim().startsWith("#") }.map(String::trim)
        val lines = Files.readAllLines(metafileFilename).filterNot { it.trim().startsWith('#') }
        //split on \s+
        val coords: Series<String> = CharSeries(lines[0]).trim.splitWs() α CharSeries::asString
        val names: Series<String> = CharSeries(lines[1]).trim.splitWs() α CharSeries::asString
        val types: Series<String> = CharSeries(lines[2]).trim.splitWs() α CharSeries::asString


        this@IsamMetaFileReader.constraints1 = names.toList().zip(types.toList()).mapIndexed { index, (name, type) ->
            val begin = coords[2 * index].toInt()
            val end = coords[2 * index + 1].toInt()
            val ioMemento: IOMemento = IOMemento.valueOf(type)
            //use PlatformCodec to get the decoder and encoder
            val decoder: (ByteArray) -> Any? = ioMemento.createDecoder(end - begin)
            val encoder: (Any?) -> ByteArray = ioMemento.createEncoder(end - begin)
            RecordMeta(name, ioMemento, begin, end, decoder, encoder)
        }
    }

    override fun close() {
         logDebug { "noOp:closing metafile ${this.metafileFilename}" }
    }

    //toString
    override fun toString(): String {
        return "IsamMetaFileReader(metafileFilename='$metafileFilename', recordlen=$recordlen, constraints=$constraints)"
    }

    /** metafile writer function
     * 1. open the metafile descriptor for writing
     * 1. write the file from a collection of record constraints
     * 1. close the file descriptor
     */
    companion object {
        fun write(metafilename: String, recordMetas: Series<ColumnMeta>,varchars:Map<String,Int>): Series<RecordMeta> {
            val lines: MutableList<String> = mutableListOf<String>()

            val result: Series<RecordMeta> = sanitize(recordMetas,varchars)
            lines.add("# format:  coords WS .. EOL names WS .. EOL TypeMememento WS .. [EOL]")
            lines.add("# last coord is the recordlen")
            lines.add(result.view.joinToString(" ") { it.begin.toString() + " " + it.end })
            lines.add(result.view.joinToString(" ") { it.name })
            lines.add(result.view.joinToString(" ") { it.type.name })
            Files.write(metafilename, lines)
            return result
        }

        fun sanitize(recordMetas: Series<ColumnMeta>, varchars: Map<String, Int>): Series<RecordMeta> {
            val result: Series<RecordMeta> = if (recordMetas.view.any { it !is RecordMeta || (min(it.begin, it.end) < 0&&null==it.child) }) {
                var offset = 0
                recordMetas.view.map { (name: String,type: TypeMemento): ColumnMeta ->
                    val type: TypeMemento = type
                    val len: Int =  type.networkSize?: varchars[name]?: throw Exception("no network size for $name")
                    val recordMeta = RecordMeta(
                        name,
                        type as IOMemento,
                        offset,
                        offset + len,
                        type.createDecoder(len),
                        type.createEncoder(len)
                    )
                    offset += len
                    recordMeta
                }.toSeries()
            } else recordMetas as Series<RecordMeta>
            return result
        }

    }
}
