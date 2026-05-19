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
 * the isam metafile format follows this _sample_
 *
```
# format:  coords WS .. EOL names WS .. EOL TypeMememento WS .. [EOL groups]
# last coord is the recordlen
0 12 12 24 24 32 32 40 40 48 48 56 56 64 64 72 72 76 76 84 84 92
Open_time Close_time Open High Low Close Volume Quote_asset_volume Number_of_trades Taker_buy_base_asset_volume Taker_buy_quote_asset_volume
IoInstant IoInstant IoDouble IoDouble IoDouble IoDouble IoDouble IoDouble IoInt IoDouble IoDouble
0:0 1-4,6-7:2 15,16:varchars
```

the ebnf we can use is:


the ebnf  is:
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
groupName := number | [A-z]+[A-z0-9_-@]*
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
        val groupSeries: Series<Join<Int, String>> = if (groupsLine.isNotEmpty()) {
            parseGroupsLine(groupsLine, namesList.size)
        } else {
            Series(namesList.size) { idx -> idx j "0" }
        }

        this@IsamMetaFileReader.constraints1 = namesList.zip(types.toList()).mapIndexed { index, (name, type) ->
            val begin = coords[2 * index].toInt()
            val end = coords[2 * index + 1].toInt()
            val (groupId, groupName) = groupSeries[index]
            val ioMemento: IOMemento = IOMemento.valueOf(type)
            val decoder: (ByteArray) -> Any? = ioMemento.createDecoder(end - begin)
            val encoder: (Any?) -> ByteArray = ioMemento.createEncoder(end - begin)
            RecordMeta(name, ioMemento, begin, end, decoder, encoder, groupId = groupId, groupName = groupName)
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
        /**
         * Parse the groups line per EBNF:
         *   groups    := (groupSpec WS)* groupSpec
         *   groupSpec := colList ':' groupName
         *   colList   := (colSpec ',')* colSpec
         *   colSpec   := colIdx | colIdx '-' colIdx
         *   groupName := number | [A-Za-z][A-Za-z0-9_\-@]*
         *
         * Returns Series<Join<Int,String>> indexed by column position: colIdx j groupName.
         * Columns not mentioned in the groups line are assigned the implicit group name
         * (implicitId.toString()), which maps to the default <stem>.bin file.
         */
        fun parseGroupsLine(line: String, colCount: Int): Series<Join<Int, String>> {
            val colToGroupName = mutableMapOf<Int, String>()
            val mentionedGroups = mutableListOf<String>()

            val cs = CharSeries(line).trim
            val tokens: Series<CharSeries> = cs.splitWs()
            for (i in 0 until tokens.size) {
                val token = tokens[i]
                val colonIdx = token.lastIndexOf(':')
                if (colonIdx < 0) continue
                val colListCs  = token.clone().lim(token.pos + colonIdx)
                val groupName  = token.clone().pos(token.pos + colonIdx + 1).asString()
                if (groupName !in mentionedGroups) mentionedGroups.add(groupName)
                // expand colList: comma-separated colSpecs
                val colSpecs: Series<CharSeries> = colListCs / ','
                for (j in 0 until colSpecs.size) {
                    val spec = colSpecs[j].trim
                    val dashIdx = spec.indexOf('-')
                    if (dashIdx > 0) {
                        val lo = spec.clone().lim(spec.pos + dashIdx).asString().toInt()
                        val hi = spec.clone().pos(spec.pos + dashIdx + 1).asString().toInt()
                        for (k in lo..hi) colToGroupName[k] = groupName
                    } else {
                        colToGroupName[spec.asString().toInt()] = groupName
                    }
                }
            }

            val implicitName = mentionedGroups.size.toString()
            return Series(colCount) { idx -> idx j (colToGroupName[idx] ?: implicitName) }
        }

        fun write(metafilename: String, recordMetas: Series<ColumnMeta>, varchars: Map<String,Int>, fileOps: FileOperations): Series<RecordMeta> {
            val lines: MutableList<String> = mutableListOf<String>()

            val result: Series<RecordMeta> = sanitize(recordMetas,varchars)
            lines.add("# format:  coords WS .. EOL names WS .. EOL TypeMememento WS .. [EOL]")
            lines.add("# last coord is the recordlen")
            lines.add(result.view.joinToString(" ") { it.begin.toString() + " " + it.end })
            lines.add(result.view.joinToString(" ") { it.name })
            lines.add(result.view.joinToString(" ") { it.type.name })

            // Emit groups line whenever more than one distinct groupId is present.
            // 0 is no more special than any other groupId — the implicit group is simply maxGroupId.
            val distinctGroups = result.view.map { it.groupId }.toSet()
            if (distinctGroups.size > 1) {
                val maxGroupId = distinctGroups.max()

                // Build groupName -> sorted list of column indices (excluding implicit/max group).
                // Preserve first-appearance order — groups have no inherent sort value.
                val byGroup = linkedMapOf<String, MutableList<Int>>()
                result.view.forEachIndexed { idx, rm ->
                    if (rm.groupId != maxGroupId)
                        byGroup.getOrPut(rm.groupName) { mutableListOf() }.add(idx)
                }

                val groupTokens = byGroup.entries.map { (gname, cols) ->
                    "${buildColList(cols)}:$gname"
                }
                if (groupTokens.isNotEmpty()) lines.add(groupTokens.joinToString(" "))
            }

            fileOps.write(metafilename, lines)
            return result
        }

        /**
         * Convert a sorted list of column indices into compact colList notation.
         * e.g. [0,1,2,5,7,8] -> "0-2,5,7-8"
         * This is the inverse of the range-expansion in parseGroupsLine.
         */
        private fun buildColList(cols: List<Int>): String {
            if (cols.isEmpty()) return ""
            val sorted = cols.sorted()
            val sb = StringBuilder()
            var start = sorted[0]; var prev = sorted[0]
            fun flush() {
                if (sb.isNotEmpty()) sb.append(',')
                if (start == prev) sb.append(start) else sb.append("$start-$prev")
            }
            for (i in 1 until sorted.size) {
                if (sorted[i] == prev + 1) { prev = sorted[i] }
                else { flush(); start = sorted[i]; prev = sorted[i] }
            }
            flush()
            return sb.toString()
        }

        fun sanitize(recordMetas: Series<ColumnMeta>, varchars: Map<String, Int>): Series<RecordMeta> {
            val result: Series<RecordMeta> = if (recordMetas.view.any { it !is RecordMeta || (min(it.begin, it.end) < 0&&null==it.child) }) {
                var offset = 0
                recordMetas.view.map { (name: String,type: TypeMemento): ColumnMeta ->
                    val type: TypeMemento = type
                    val len: Int =  type.networkSize?: varchars[name]?: throw Exception("no network size for $name")
                    val groupId   = (type as? RecordMeta)?.groupId   ?: 0
                    val groupName = (type as? RecordMeta)?.groupName ?: groupId.toString()
                    val recordMeta = RecordMeta(
                        name,
                        type as IOMemento,
                        offset,
                        offset + len,
                        type.createDecoder(len),
                        type.createEncoder(len),
                        groupId = groupId,
                        groupName = groupName,
                    )
                    offset += len
                    recordMeta
                }.toSeries()
            } else recordMetas as Series<RecordMeta>
            return result
        }

    }
}
