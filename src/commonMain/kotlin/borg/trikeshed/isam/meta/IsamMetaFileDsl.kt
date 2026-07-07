@file:Suppress("unused")

package borg.trikeshed.isam.meta

import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.TypeMemento
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlin.math.min

data class MetafileConfig(
    val metafileFilename: String,
    val fileOps: FileOperations,
)

// ---------------------------------------------------------------------------
// Reified inline builder for reading metafile
// ---------------------------------------------------------------------------

fun readMetafile(
    config: MetafileConfig,
    block: MetafileReader.() -> Unit,
): MetafileReader = MetafileReader(config).apply(block)

class MetafileReader(val config: MetafileConfig) : Usable {
    override fun open() { readMetafileInternal() }
    override fun close() = logDebug { "closing metafile ${config.metafileFilename}" }

    fun readMetafileInternal(): MetafileResult {
        val lines = config.fileOps.readAllLines(config.metafileFilename).filterNot { it.trim().startsWith('#') }
        val coords = CharSeries(lines[0]).trim.splitWs() α CharSeries::asString
        val names = CharSeries(lines[1]).trim.splitWs() α CharSeries::asString
        val types = CharSeries(lines[2]).trim.splitWs() α CharSeries::asString

        val namesList = names.toList()
        val groupsLine = if (lines.size > 3) lines[3].trim() else ""
        val groupSeries = if (groupsLine.isNotEmpty()) parseGroupsLine(groupsLine, namesList.size)
            else namesList.size j { idx: Int -> idx j "0" }

        val constraints = namesList.zip(types.toList()).mapIndexed { index, (name, type) ->
            val begin = coords[2 * index].toInt()
            val end = coords[2 * index + 1].toInt()
            val (groupId, groupName) = groupSeries[index]
            val ioMemento = IOMemento.valueOf(type)
            RecordMeta(
                name = name,
                type = ioMemento,
                begin = begin,
                end = end,
                decoder = ioMemento.createDecoder(end - begin),
                encoder = ioMemento.createEncoder(end - begin),
                groupId = groupId,
                groupName = groupName,
            )
        }.toSeries()

        val recordlen = constraints.view.last().end
        return MetafileResult(config.metafileFilename, recordlen, constraints)
    }

    companion object {
        fun parseGroupsLine(line: String, colCount: Int): Series<Join<Int, String>> = parseGroupsLineInternal(line, colCount)

        private fun parseGroupsLineInternal(line: String, colCount: Int): Series<Join<Int, String>> {
            val colToGroupName = mutableMapOf<Int, String>()
            val mentionedGroups = mutableListOf<String>()
            val cs = CharSeries(line).trim
            val tokens: Series<CharSeries> = cs.splitWs()
            for (token in tokens) {
                val colonIdx = token.asString().lastIndexOf(':')
                if (colonIdx < 0) continue
                val colListCs = token.clone().lim(token.pos + colonIdx)
                val groupName = token.clone().pos(token.pos + colonIdx + 1).asString()
                if (groupName !in mentionedGroups) mentionedGroups.add(groupName)
                val colSpecs: Series<CharSeries> = (colListCs / ',') α { CharSeries(it) }
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
            return colCount j { idx: Int -> idx j (colToGroupName[idx] ?: implicitName) }
        }
    }
}

data class MetafileResult(
    val metafileFilename: String,
    val recordlen: Int,
    val constraints: Series<RecordMeta>,
)

// ---------------------------------------------------------------------------
// Reified inline builder for writing metafile
// ---------------------------------------------------------------------------

fun writeMetafile(
    config: MetafileWriteConfig,
    block: MetafileWriter.() -> Unit,
): MetafileResult = MetafileWriter(config).apply(block).build()

data class MetafileWriteConfig(
    val metafilename: String,
    val recordMetas: Series<ColumnMeta>,
    val varchars: Map<String, Int>,
    val fileOps: FileOperations,
)

class MetafileWriter(val config: MetafileWriteConfig) {
    fun build(): MetafileResult {
        val result = sanitize(config.recordMetas, config.varchars)
        val lines = mutableListOf<String>()
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

        config.fileOps.write(config.metafilename, lines)
        return MetafileResult(config.metafilename, result.last().end, result)
    }

    companion object {
        fun sanitize(recordMetas: Series<ColumnMeta>, varchars: Map<String, Int>): Series<RecordMeta> {
            return if (recordMetas.view.any { it !is RecordMeta || (min(it.begin, it.end) < 0 && it.child == null) }) {
                var offset = 0
                recordMetas.view.map { col: ColumnMeta ->
                    val name = col.name.toString()
                    val type = col.type
                    val len = type.networkSize ?: varchars[name] ?: throw Exception("no network size for $name")
                    val groupId = (col as? RecordMeta)?.groupId ?: 0
                    val groupName = (col as? RecordMeta)?.groupName ?: groupId.toString()
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
        }

        fun buildColList(cols: List<Int>): String {
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
    }
}

// ---------------------------------------------------------------------------
// RecordMeta extended constructor with groupId/groupName
// ---------------------------------------------------------------------------

fun RecordMeta(
    name: String,
    type: IOMemento,
    begin: Int = -1,
    end: Int = -1,
    decoder: (ByteArray) -> Any? = type.createDecoder(end - begin),
    encoder: (Any?) -> ByteArray = type.createEncoder(end - begin),
    groupId: Int = 0,
    groupName: String = "0",
    child: ColumnMeta? = null,
): RecordMeta = RecordMeta(name, type, begin, end, decoder, encoder, child).also {
    it.groupId = groupId
    it.groupName = groupName
}

// ---------------------------------------------------------------------------
// Reified inline column specs
// ---------------------------------------------------------------------------

fun <T> columnSpec(
    name: String,
    ioMemento: IOMemento,
    length: Int,
    group: Int = 0,
    groupName: String = "0",
): ColumnMeta = ColumnMeta(
    name = name,
    type = ioMemento,
    child = meta {
        this.groupId = group
        this.groupName = groupName
    }
)

// ---------------------------------------------------------------------------
// RecordMeta metadata marker
// ---------------------------------------------------------------------------

class Meta<T>(var groupId: Int = 0, var groupName: String = "0") : ColumnMeta by ColumnMeta("meta", IOMemento.IoString, null)

fun meta(block: Meta<*>.() -> Unit): Meta<Any> = Meta<Any>().apply(block)
