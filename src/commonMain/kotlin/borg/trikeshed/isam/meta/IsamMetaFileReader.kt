package borg.trikeshed.isam.meta

import borg.trikeshed.common.Files
import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.TypeMemento
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlin.math.min

/**
 * 1. create a class that can read the metadata file and create a collection of record constraints
 *
 * the isam metafile format follows this sample
 *
 * ```
 * # format:  coords WS .. EOL names WS .. EOL TypeMememento WS ..
 * # last coord is the recordlen
 * 0 12 12 24 24 32 32 40 40 48 48 56 56 64 64 72 72 76 76 84 84 92
 * Open_time Close_time Open High Low Close Volume Quote_asset_volume Number_of_trades Taker_buy_base_asset_volume Taker_buy_quote_asset_volume
 * IoInstant IoInstant IoDouble IoDouble IoDouble IoDouble IoDouble IoDouble IoInt IoDouble IoDouble
 * ```
 *
 * the ebnf we can use is:
 *
 * ```
 * metafile :=  (coords WS names WS .. EOL)*
 * coords :=  (coord WS)* coord
 * coord :=  number
 * names :=  (name WS)* name
 * name :=  string
 * TypeMemento :=  (IoType WS)* IoType
 * IoType :=  IoInstant | IoDouble | IoString | IoInt
 * ```
 *  2. create a class that can create the binary file
 *
 * the binary file format follows this sample
 *
 *
 */
class IsamMetaFileReader(
    val metafileFilename: String,
    private val fileOps: FileOperations? = null
) : Usable {

    val recordlen: Int by lazy {
        constraints.last().end
    }
    val constraints: Series<RecordMeta> by lazy {
        open()
        constraints1.toSeries()
    }

    private lateinit var constraints1: List<RecordMeta>

    override fun open() {
        val lines = if (fileOps != null) {
            fileOps.readAllLines(metafileFilename)
        } else {
            Files.readAllLines(metafileFilename)
        }.filterNot { it.trim().startsWith('#') }

        val coords: Series<String> = CharSeries(lines[0]).trim.splitWs() α CharSeries::asString
        val names: Series<String> = CharSeries(lines[1]).trim.splitWs() α CharSeries::asString
        val types: Series<String> = CharSeries(lines[2]).trim.splitWs() α CharSeries::asString

        val namesList = names.toList()
        val groupsLine = if (lines.size > 3) lines[3].trim() else ""
        val groupSeries: Series<Join<Int, String>> = if (groupsLine.isNotEmpty()) {
            parseGroupsLine(groupsLine, namesList.size)
        } else {
            namesList.size j { idx: Int -> idx j "0" }
        }

        this@IsamMetaFileReader.constraints1 = namesList.zip(types.toList()).mapIndexed { index, (name, type) ->
            val begin = coords[2 * index].toInt()
            val end = coords[2 * index + 1].toInt()
            val (groupId, groupName) = groupSeries[index]
            val ioMemento: IOMemento = IOMemento.valueOf(type)
            val decoder = ioMemento.createDecoder(end - begin)
            val encoder = ioMemento.createEncoder(end - begin)
            val recordMeta = RecordMeta(name, ioMemento, begin, end, decoder, encoder)
            recordMeta.groupId = groupId
            recordMeta.groupName = groupName
            recordMeta
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
        fun parseGroupsLine(line: String, colCount: Int): Series<Join<Int, String>> {
            val colToGroupName = mutableMapOf<Int, String>()
            val mentionedGroups = mutableListOf<String>()

            val cs = CharSeries(line).trim
            val tokens: Series<CharSeries> = cs.splitWs()
            for (token in tokens) {
                val colonIdx = token.asString().lastIndexOf(':')
                if (colonIdx < 0) continue
                val colListCs  = token.clone().lim(token.pos + colonIdx)
                val groupName  = token.clone().pos(token.pos + colonIdx + 1).asString()
                if (groupName !in mentionedGroups) mentionedGroups.add(groupName)
                val colSpecs: Series<CharSeries> = (CharSeries(colListCs.asString()) / ',') α { CharSeries(it) }
                for (spec in colSpecs) {
                    val specs = spec.trim
                    val dashIdx = specs.asString().indexOf('-')
                    if (dashIdx > 0) {
                        val lo = specs.clone().lim(specs.pos + dashIdx).asString().toInt()
                        val hi = specs.clone().pos(specs.pos + dashIdx + 1).asString().toInt()
                        for (k in lo..hi) colToGroupName[k] = groupName
                    } else {
                        colToGroupName[specs.asString().toInt()] = groupName
                    }
                }
            }

            val implicitName = mentionedGroups.size.toString()
            val implicitGroupId = mentionedGroups.size

            return colCount j { idx: Int ->
                val gname = colToGroupName[idx] ?: implicitName
                val gid = if (gname == implicitName) {
                    implicitGroupId
                } else {
                    val pos = mentionedGroups.indexOf(gname)
                    if (pos >= 0) pos else implicitGroupId
                }
                gid j gname
            }
        }

        fun write(
            metafilename: String,
            recordMetas: Series<ColumnMeta>,
            varchars: Map<String, Int>,
            fileOps: FileOperations? = null
        ): Series<RecordMeta> {
            val lines = mutableListOf<String>()

            val result = sanitize(recordMetas, varchars)
            lines.add("# format:  coords WS .. EOL names WS .. EOL TypeMememento WS .. [EOL]")
            lines.add("# last coord is the recordlen")
            lines.add(result.view.joinToString(" ") { it.begin.toString() + " " + it.end })
            lines.add(result.view.joinToString(" ") { it.name })
            lines.add(result.view.joinToString(" ") { it.type.name })

            val distinctGroups = result.view.map { it.groupId }.toSet()
            if (distinctGroups.size > 1) {
                val maxGroupId = distinctGroups.max()

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

            if (fileOps != null) {
                fileOps.write(metafilename, lines)
            } else {
                Files.write(metafilename, lines)
            }
            return result
        }

        private fun buildColList(cols: List<Int>): String {
            if (cols.isEmpty()) return ""
            val sorted = cols.sorted()
            val sb = StringBuilder()
            var start = sorted[0]
            var prev = sorted[0]
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
            val result = if (recordMetas.view.any { it !is RecordMeta || (min(it.begin, it.end) < 0 && it.child == null) }) {
                var offset = 0
                recordMetas.view.map { columnMeta: ColumnMeta ->
                    val name = columnMeta.name.toString()
                    val type = columnMeta.type
                    val len = type.networkSize ?: varchars[name] ?: throw Exception("no network size for $name")
                    val groupId = (columnMeta as? RecordMeta)?.groupId ?: 0
                    val groupName = (columnMeta as? RecordMeta)?.groupName ?: groupId.toString()
                    val recordMeta = RecordMeta(
                        name = name,
                        type = type as IOMemento,
                        begin = offset,
                        end = offset + len,
                        decoder = type.createDecoder(len),
                        encoder = type.createEncoder(len),
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
