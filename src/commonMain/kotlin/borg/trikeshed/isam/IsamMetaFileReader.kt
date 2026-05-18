package borg.trikeshed.isam

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.TypeMemento
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlin.math.min

/**
 * 1. create a class that can read the metadata file and create a collection of record constraints
 *
 * the isam metafile format follows this sample
 *
```
# format:  coords WS .. EOL names WS .. EOL TypeMememento WS .. [EOL groups]
# last coord is the recordlen
0 12 12 24 24 32 32 40 40 48 48 56 56 64 64 72 72 76 76 84 84 92
Open_time Close_time Open High Low Close Volume Quote_asset_volume Number_of_trades Taker_buy_base_asset_volume Taker_buy_quote_asset_volume
IoInstant IoInstant IoDouble IoDouble IoDouble IoDouble IoDouble IoDouble IoInt IoDouble IoDouble
# groups: sparse mixed grouping with ranges and comma-separated lists
# examples: "0:0 1-4:2 5:0 7-12:reserved" or "0,5:0 1-4,6:2 7-12:reserved"
0:0 1-4:2 5:0 7-12:reserved
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
groups := (groupSpec WS)* groupSpec
groupSpec := colList ':' groupName
colList := (colSpec ',')* colSpec
colSpec := colIdx | colIdx '-' colIdx
groupName := number | 'reserved'
```
 * 2. create a class that can create the binary file
 *
 * the binary file format follows this sample
 *
 */
 class IsamMetaFileReader(val metafileFilename: String, private val fileOps: FileOperations) :Usable{

    val recordlen: Int by lazy {
        constraints.last().end
    }
    val constraints: Series<RecordMeta> by lazy {
        open()
        constraints1.toSeries()
    }

   lateinit var constraints1: List<RecordMeta>
    override fun open() {
        val lines = fileOps.readAllLines(metafileFilename).filterNot { it.trim().startsWith('#') }
        val coords: Series<String> = CharSeries(lines[0]).trim.splitWs() α CharSeries::asString
        val names: Series<String> = CharSeries(lines[1]).trim.splitWs() α CharSeries::asString
        val types: Series<String> = CharSeries(lines[2]).trim.splitWs() α CharSeries::asString

        val namesList = names.toList()
        val groupsLine = if (lines.size > 3) lines[3].trim() else ""
        val groupIds = if (groupsLine.isNotEmpty()) {
            parseGroupsLine(groupsLine, namesList)
        } else {
            IntArray(namesList.size) { 0 }
        }

        this@IsamMetaFileReader.constraints1 = namesList.zip(types.toList()).mapIndexed { index, (name, type) ->
            val begin = coords[2 * index].toInt()
            val end = coords[2 * index + 1].toInt()
            val groupId = groupIds[index]
            val ioMemento: IOMemento = IOMemento.valueOf(type)
            val decoder: (ByteArray) -> Any? = ioMemento.createDecoder(end - begin)
            val encoder: (Any?) -> ByteArray = ioMemento.createEncoder(end - begin)
            RecordMeta(name, ioMemento, begin, end, decoder, encoder, groupId = groupId)
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
        fun parseGroupsLine(line: String, colNames: List<String>): IntArray {
            val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val colToGroupStr = mutableMapOf<String, String>()
            val mentionedGroups = mutableListOf<String>()

            for (token in tokens) {
                val parts = token.split(':')
                val groupVal = if (parts.size > 1) parts[1] else "0"

                // Parse column specs: "0,5" or "0-5" or "0" or single token
                val colSpecs = parts[0].split(',')
                for (spec in colSpecs) {
                    if (spec.contains('-')) {
                        // Range: "0-5" means columns 0,1,2,3,4,5
                        val (start, end) = spec.split('-').let { it[0].toInt() to it[1].toInt() }
                        for (colIdx in start..end) {
                            colToGroupStr[colIdx.toString()] = groupVal
                        }
                    } else {
                        // Single column
                        colToGroupStr[spec] = groupVal
                    }
                }

                if (groupVal !in mentionedGroups) {
                    mentionedGroups.add(groupVal)
                }
            }

            val groupToId = mentionedGroups.mapIndexed { index, name -> name to index }.toMap().toMutableMap()
            val implicitGroupId = mentionedGroups.size

            return IntArray(colNames.size) { colIdx ->
                val name = colNames[colIdx]
                val strIdx = colIdx.toString()
                val groupName = colToGroupStr[name] ?: colToGroupStr[strIdx]
                if (groupName != null) {
                    groupToId[groupName]!!
                } else {
                    implicitGroupId
                }
            }
        }

        fun write(metafilename: String, recordMetas: Series<ColumnMeta>, varchars: Map<String,Int>, fileOps: FileOperations): Series<RecordMeta> {
            val lines: MutableList<String> = mutableListOf<String>()

            val result: Series<RecordMeta> = sanitize(recordMetas,varchars)
            lines.add("# format:  coords WS .. EOL names WS .. EOL TypeMememento WS .. [EOL]")
            lines.add("# last coord is the recordlen")
            lines.add(result.view.joinToString(" ") { it.begin.toString() + " " + it.end })
            lines.add(result.view.joinToString(" ") { it.name })
            lines.add(result.view.joinToString(" ") { it.type.name })

            if (result.view.any { it.groupId != 0 }) {
                val maxGroupId = result.view.maxOf { it.groupId }

                // Group column indices by their groupId (excluding LAST/maxGroupId)
                val groupsById = mutableMapOf<Int, MutableList<Int>>()
                result.view.forEachIndexed { index, recordMeta ->
                    if (recordMeta.groupId != maxGroupId) {
                        groupsById.getOrPut(recordMeta.groupId) { mutableListOf() }.add(index)
                    }
                }

                // Convert column indices to compact range format
                val groupTokens = mutableListOf<String>()
                groupsById.forEach { (groupId, colIndices) ->
                    val groupName = when (groupId) {
                        1 -> "reserved"
                        else -> groupId.toString()
                    }

                    // Convert list of indices to ranges: [0,1,2,5,6] → "0-2 5-6"
                    val ranges = mutableListOf<String>()
                    var start = colIndices[0]
                    var prev = colIndices[0]

                    for (idx in colIndices.drop(1)) {
                        if (idx == prev + 1) {
                            // Consecutive
                            prev = idx
                        } else {
                            // Gap - emit range
                            ranges.add(if (start == prev) "$start" else "$start-$prev")
                            start = idx
                            prev = idx
                        }
                    }
                    // Emit final range
                    ranges.add(if (start == prev) "$start" else "$start-$prev")

                    // Join ranges with commas, append group suffix
                    groupTokens.add("${ranges.joinToString(",")}:$groupName")
                }

                if (groupTokens.isNotEmpty()) {
                    lines.add(groupTokens.joinToString(" "))
                }
            }

            fileOps.write(metafilename, lines)
            return result
        }

        fun sanitize(recordMetas: Series<ColumnMeta>, varchars: Map<String, Int>): Series<RecordMeta> {
            val result: Series<RecordMeta> = if (recordMetas.view.any { it !is RecordMeta || (min(it.begin, it.end) < 0&&null==it.child) }) {
                var offset = 0
                recordMetas.view.map { (name: String,type: TypeMemento): ColumnMeta ->
                    val type: TypeMemento = type
                    val len: Int =  type.networkSize?: varchars[name]?: throw Exception("no network size for $name")
                    // Assign groupId based on field name: volume fields -> 1 (reserved), others -> 0 (default)
                    val lowerName = name.lowercase()
                    val groupId = when {
                        "volume" in lowerName || "quote_asset" in lowerName -> 1
                        else -> (type as? RecordMeta)?.groupId ?: 0
                    }
                    val recordMeta = RecordMeta(
                        name,
                        type as IOMemento,
                        offset,
                        offset + len,
                        type.createDecoder(len),
                        type.createEncoder(len),
                        groupId = groupId
                    )
                    offset += len
                    recordMeta
                }.toSeries()
            } else recordMetas as Series<RecordMeta>
            return result
        }
    }
}
