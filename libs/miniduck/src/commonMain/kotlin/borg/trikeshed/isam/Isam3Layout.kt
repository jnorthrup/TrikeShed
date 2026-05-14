package borg.trikeshed.isam

import borg.trikeshed.userspace.nio.file.Files
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeries
import borg.trikeshed.lib.get
import borg.trikeshed.lib.getOrNull
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import borg.trikeshed.lib.size
import borg.trikeshed.parse.yaml.parse as parseYaml
import java.util.LinkedList

private val ISAM3_RESERVED_TOP_LEVEL = setOf("isam", "views", "defaults")
private val ISAM3_RESERVED_GROUP_KEYS = setOf("flags", "coords")

class Isam3Layout(
    val version: Int,
    val partitions: Series<Isam3Partition>,
    val views: Series<Isam3View>,
) {
    fun partition(name: CharSequence): Isam3Partition? {
        for (partition in partitions.view) {
            if (partition.name == name) return partition
        }
        return null
    }

    fun file(name: CharSequence): Isam3File? {
        for (partition in partitions.view) {
            for (file in partition.files.view) {
                if (file.name == name) return file
            }
        }
        return null
    }

    fun view(name: CharSequence): Isam3View? = views.view.firstOrNull { it.name == name }

    fun defaultViewName(): CharSequence = views.getOrNull(0)?.name ?: "default"

    fun logicalNames(viewName: CharSequence? = null): Series<CharSequence> {
        val names = when (val resolvedView = when (viewName) {
            null -> views.getOrNull(0)
            else -> view(viewName) ?: error("Unknown ISAM3 view: $viewName")
        }) {
            null -> allPlacements().toList().map { it.name }.distinct()
            else -> resolvedView.columns.toList()
        }
        return names.toList().distinct().toSeries()
    }

    fun logicalMeta(viewName: CharSequence? = null): Series<ColumnMeta> {
        val resolved = resolvedColumns(viewName)
        return resolved.size j { index: Int -> resolved[index].meta }
    }

    fun recordMeta(viewName: CharSequence? = null): Series<RecordMeta> {
        val resolved = resolvedColumns(viewName)
        return resolved.size j { index: Int -> resolved[index].meta }
    }

    fun resolvedColumns(viewName: CharSequence? = null): Series<Isam3ResolvedColumn> {
        val names = logicalNames(viewName)
        if (names.size == 0) return emptySeries()
        return names.size j { index: Int ->
            val columnName = names[index]
            resolveColumn(columnName)
        }
    }

    fun allPlacements(): Series<Isam3Placement> {
        val seen = linkedSetOf<CharSequence>()
        val out = LinkedList<Isam3Placement>()
        partitions.view.forEach { partition ->
            partition.files.view.forEach { file ->
                file.groups.view.forEach { group ->
                    group.placements.view.forEach { placement ->
                        if (seen.add(placement.name)) out.add(placement)
                    }
                }
            }
        }
        return out.toSeries()
    }

    fun render(): CharSequence = buildString {
        appendLine("isam: 3")
        if (views.size > 0) {
            appendLine("views:")
            for (view in views.view) {
                append("  ")
                append(view.name)
                append(": ")
                append(renderInlineList(view.columns.toList()))
                appendLine()
            }
        }
        for (partition in partitions.view) {
            appendLine("${partition.name}:")
                if (partition.flags.size > 0) {
                    append("  flags: ")
                    append(renderInlineList(partition.flags.toList()))
                    appendLine()
                }
            for (file in partition.files.view) {
                appendLine("  ${file.name}:")
                if (file.flags.size > 0) {
                    append("    flags: ")
                    append(renderInlineList(file.flags.toList()))
                    appendLine()
                }
                for (group in file.groups.view) {
                    appendLine(renderGroup(group, indent = "    "))
                }
            }
        }
    }

    private fun resolveColumn(columnName: CharSequence): Isam3ResolvedColumn {
        partitions.view.forEach { partition ->
            partition.files.view.forEach { file ->
                file.groups.view.forEach { group ->
                    group.placements.view.forEach { placement ->
                        if (placement.name == columnName) {
                            val begin = placement.begin
                            val end = placement.end
                            return Isam3ResolvedColumn(
                                partition = partition.name,
                                file = file.name,
                                type = group.type,
                                meta = RecordMeta(placement.name, group.type, begin, end),
                                begin = begin,
                                end = end,
                            )
                        }
                    }
                }
            }
        }
        error("Missing column $columnName in ISAM layout")
    }

    private fun renderGroup(group: Isam3Group, indent: CharSequence): CharSequence {
        val placements = group.placements.toList()
        val packed = isPacked(group.type, placements)
        return if (packed) {
            if (group.type.networkSize == null) {
                val rendered = if (placements.size == 1) {
                    "[${placements[0].width}, ${yamlScalar(placements[0].name)}]"
                } else {
                    placements.joinToString(", ", prefix = "[", postfix = "]") { "[${it.width}, ${yamlScalar(it.name)}]" }
                }
                "${indent}${group.type.name}: $rendered"
            } else {
                "${indent}${group.type.name}: ${renderInlineList(placements.map { it.name })}"
            }
        } else {
            buildString {
                appendLine("${indent}${group.type.name}:")
                for (placement in placements) {
                    if (placement.width == group.type.networkSize) {
                        appendLine("${indent}  ${yamlScalar(placement.name)}: ${placement.begin}")
                    } else {
                        appendLine("${indent}  ${yamlScalar(placement.name)}: [${placement.begin}, ${placement.width}]")
                    }
                }
            }.trimEnd()
        }
    }

    private fun isPacked(type: IOMemento, placements: List<Isam3Placement>): Boolean {
        var offset = 0
        for (placement in placements) {
            if (placement.begin != offset) return false
            offset += placement.width
        }
        return placements.isNotEmpty() && (type.networkSize != null || placements.size == 1 || placements.all { it.width > 0 })
    }

    companion object {
        fun parse(text: CharSequence): Isam3Layout = fromMap(parseYaml(text) as? Map<*, *> ?: error("ISAM layout root must be a map"))

        fun read(file: CharSequence): Isam3Layout = parse(Files.readString(file).toString())

        fun fromConstraints(
            partitionName: CharSequence,
            constraints: Series<RecordMeta>,
            viewColumns: Series<CharSequence> = constraints.size j { index: Int -> constraints[index].name },
        ): Isam3Layout = fromCursor(partitionName, constraints, viewColumns)

        fun fromCursor(
            partitionName: CharSequence,
            constraints: Series<RecordMeta>,
            viewColumns: Series<CharSequence> = constraints.size j { index: Int -> constraints[index].name },
        ): Isam3Layout {
            val groupsByCluster = linkedMapOf<CharSequence, LinkedList<RecordMeta>>()
            constraints.view.forEach { meta ->
                val cluster = clusterName(meta)
                groupsByCluster.getOrPut(cluster) { LinkedList() }.add(meta)
            }

            val files = groupsByCluster.entries.map { (clusterName, metas) ->
                val groupByType = linkedMapOf<IOMemento, LinkedList<RecordMeta>>()
                metas.forEach { meta ->
                    groupByType.getOrPut(meta.type) { LinkedList() }.add(meta)
                }

                var offset = 0
                val groups = groupByType.entries.map { (type, typedMetas) ->
                    val placements = typedMetas.map { meta ->
                        val width = meta.end - meta.begin
                        val placement = Isam3Placement(meta.name, offset, width)
                        offset += width
                        placement
                    }.toSeries()
                    Isam3Group(type, placements)
                }.toSeries()

                var rowWidth = 0
                for (group in groups.view) {
                    for (placement in group.placements.view) {
                        rowWidth += placement.width
                    }
                }
                Isam3File(clusterName, emptySeries(), groups, rowWidth)
            }.toSeries()

            return Isam3Layout(
                version = 3,
                partitions = 1 j { _: Int -> Isam3Partition(partitionName, emptySeries(), files) },
                views = 1 j { _: Int -> Isam3View(partitionName, viewColumns) },
            )
        }

        private fun fromMap(root: Map<*, *>): Isam3Layout {
            val map = root.entries.associate { it.key.toString() to it.value }
            val version = map["isam"].asInt("isam")
            require(version == 3) { "Unsupported ISAM layout version: $version" }

            val viewSeries = parseViews(map["views"])

            val partitions = map.entries
                .filter { (key, _) -> key !in ISAM3_RESERVED_TOP_LEVEL }
                .map { (partitionName, partitionValue) -> parsePartition(partitionName, partitionValue) }
                .toSeries()

            val resolvedViews = if (viewSeries.size > 0) viewSeries else 1 j { _: Int ->
                val columns = LinkedList<CharSequence>()
                val seen = linkedSetOf<CharSequence>()
                for (partition in partitions.view) {
                    for (file in partition.files.view) {
                        for (group in file.groups.view) {
                            for (placement in group.placements.view) {
                                if (seen.add(placement.name)) columns.add(placement.name)
                            }
                        }
                    }
                }
                Isam3View(
                    name = partitions.getOrNull(0)?.name ?: "default",
                    columns = columns.toSeries(),
                )
            }

            return Isam3Layout(version, partitions, resolvedViews)
        }

        private fun parseViews(value: Any?): Series<Isam3View> {
            val map = value.asMapOrNull("views") ?: return emptySeries()
            return map.entries.map { (viewName, cols) ->
                Isam3View(viewName, parseColumnNames(cols, "views.$viewName"))
            }.toSeries()
        }

        private fun parsePartition(name: CharSequence, raw: Any?): Isam3Partition {
            val map = raw.asMap("partition $name")
            val flags = parseFlags(map["flags"] ?: map["coords"], "partition $name flags")
            val files = map.entries
                .filter { (key, _) -> key !in ISAM3_RESERVED_GROUP_KEYS }
                .map { (fileName, fileRaw) -> parseFile(fileName, fileRaw) }
                .toSeries()
            return Isam3Partition(name, flags, files)
        }

        private fun parseFile(name: CharSequence, raw: Any?): Isam3File {
            val map = raw.asMap("file $name")
            val flags = parseFlags(map["flags"] ?: map["coords"], "file $name flags")
            var fileOffset = 0
            val groups = LinkedList<Isam3Group>()
            for ((groupKey, groupRaw) in map.entries) {
                if (groupKey in ISAM3_RESERVED_GROUP_KEYS) continue
                val parsed = parseGroup(groupKey, groupRaw, "file $name", fileOffset)
                groups += parsed.group
                fileOffset = maxOf(fileOffset, parsed.spanEnd)
            }
            var rowWidth = 0
            for (group in groups) {
                for (placement in group.placements.view) {
                    rowWidth = maxOf(rowWidth, placement.end)
                }
            }
            return Isam3File(name, flags, groups.toSeries(), rowWidth)
        }

        private fun parseGroup(typeName: CharSequence, raw: Any?, context: CharSequence, fileOffset: Int): ParsedGroup {
            val type = IOMemento.valueOf(typeName.toString())
            val placements = when (raw) {
                is List<*> -> parsePlacementList(type, raw, context, fileOffset)
                is Map<*, *> -> parsePlacementMap(type, raw.entries.associate { it.key.toString() to it.value }, context)
                else -> error("$context.$typeName must be a list or map, got ${typeNameOf(raw)}")
            }
            val spanEnd = placements.view.maxOfOrNull { it.end } ?: fileOffset
            return ParsedGroup(Isam3Group(type, placements), spanEnd)
        }

        private fun parsePlacementList(type: IOMemento, raw: List<*>, context: CharSequence, fileOffset: Int): Series<Isam3Placement> {
            if (raw.isEmpty()) return emptySeries()
            if (raw.size == 2 && raw[0] is Number && raw[1] is CharSequence) {
                val width = (raw[0] as Number).toInt()
                val name = raw[1].asString("$context ${type.name}")
                requireWidth(type, width, name, context)
                return 1 j { _: Int -> Isam3Placement(name, fileOffset, width) }
            }
            if (raw.all { it is CharSequence }) {
                var offset = fileOffset
                return raw.map { value ->
                    val name = value.asString("$context ${type.name}")
                    val width = widthFor(type, name, null, context)
                    val placement = Isam3Placement(name, offset, width)
                    offset += width
                    placement
                }.toSeries()
            }
            if (raw.all { it is List<*> }) {
                var offset = fileOffset
                return raw.map { spec ->
                    val list = spec as List<*>
                    require(list.size == 2 && list[0] is Number && list[1] is CharSequence) {
                        "$context.${type.name} list entries must be [width, name]"
                    }
                    val width = (list[0] as Number).toInt()
                    val name = list[1].asString("$context ${type.name}")
                    requireWidth(type, width, name, context)
                    val placement = Isam3Placement(name, offset, width)
                    offset += width
                    placement
                }.toSeries()
            }
            error("$context.${type.name} list form must be column names or [width, name] pairs")
        }

        private fun parsePlacementMap(type: IOMemento, raw: Map<CharSequence, Any?>, context: CharSequence): Series<Isam3Placement> {
            return raw.entries.map { (name, beginRaw) ->
                val begin = when (beginRaw) {
                    is List<*> -> {
                        require(beginRaw.size in 1..2 && beginRaw[0] is Number) {
                            "$context.${type.name} width form must be [begin] or [begin, width]"
                        }
                        (beginRaw[0] as Number).toInt()
                    }
                    is Number -> beginRaw.toInt()
                    else -> error("$context.${type.name} coordinate for $name must be an Int or [Int, Int]")
                }
                val width = when (beginRaw) {
                    is List<*> -> if (beginRaw.size == 2) (beginRaw[1] as Number).toInt() else widthFor(type, name, null, context)
                    else -> widthFor(type, name, null, context)
                }
                requireWidth(type, width, name, context)
                Isam3Placement(name, begin, width)
            }.toSeries()
        }

        private fun parseColumnNames(raw: Any?, context: CharSequence): Series<CharSequence> = when (raw) {
            is List<*> -> raw.map { it.asString(context) }.toSeries()
            is CharSequence -> 1 j { _: Int -> raw.asString(context) }
            else -> error("$context must be a list of strings")
        }

        private fun parseFlags(raw: Any?, context: CharSequence): Series<CharSequence> = when (raw) {
            null -> emptySeries()
            is List<*> -> raw.map { it.asString(context) }.toSeries()
            is CharSequence -> 1 j { _: Int -> raw.asString(context) }
            else -> error("$context must be a list of strings")
        }

        private fun widthFor(type: IOMemento, name: CharSequence, explicit: Int?, context: CharSequence): Int {
            val fixed = type.networkSize
            return when {
                fixed != null -> {
                    val width = explicit ?: fixed
                    requireWidth(type, width, name, context)
                    width
                }
                explicit != null -> {
                    require(explicit > 0) { "$context.${type.name} width for $name must be > 0" }
                    explicit
                }
                else -> error("$context.${type.name} requires explicit len for $name")
            }
        }

        private fun requireWidth(type: IOMemento, width: Int, name: CharSequence, context: CharSequence) {
            val fixed = type.networkSize
            if (fixed != null) {
                require(width >= fixed) {
                    "$context.${type.name} width for $name too small: $width < $fixed"
                }
            } else {
                require(width > 0) { "$context.${type.name} width for $name must be > 0" }
            }
        }

        private fun clusterName(meta: RecordMeta): CharSequence {
            val lower = meta.name.toString().lowercase()
            return when {
                "time" in lower -> "time"
                lower in PRICE_COLUMNS -> "price"
                "trade" in lower -> "trades"
                "volume" in lower || "quote_asset" in lower -> "volume"
                meta.type == IOMemento.IoInstant -> "time"
                meta.type == IOMemento.IoInt -> "trades"
                meta.type == IOMemento.IoDouble -> "price"
                meta.type == IOMemento.IoString -> "text"
                else -> meta.type.name.lowercase()
            }
        }

        private val PRICE_COLUMNS: Set<CharSequence> = setOf("open", "high", "low", "close")

        private fun typeNameOf(value: Any?): CharSequence = value?.let { it::class.simpleName ?: it::class.toString() } ?: "null"

        private fun Any?.asMap(context: CharSequence): Map<CharSequence, Any?> = asMapOrNull(context) ?: error("$context must be a map")

        private fun Any?.asMapOrNull(context: CharSequence): Map<CharSequence, Any?>? = when (this) {
            null -> null
            is Map<*, *> -> entries.associate { it.key.toString() to it.value }
            else -> null
        }

        private fun Any?.asString(context: CharSequence): CharSequence = when (this) {
            is CharSequence -> this
            else -> error("$context must be a string, got ${typeNameOf(this)}")
        }

        private fun Any?.asInt(context: CharSequence): Int = when (this) {
            is Int -> this
            is Long -> this.toInt()
            is Short -> this.toInt()
            is Byte -> this.toInt()
            is Double -> this.toInt()
            is Float -> this.toInt()
            is CharSequence -> toString().toIntOrNull() ?: error("$context must be an Int, got $this")
            else -> error("$context must be an Int, got ${typeNameOf(this)}")
        }
    }
}

private data class ParsedGroup(
    val group: Isam3Group,
    val spanEnd: Int,
)

class Isam3Partition(
    val name: CharSequence,
    val flags: Series<CharSequence>,
    val files: Series<Isam3File>,
)

class Isam3File(
    val name: CharSequence,
    val flags: Series<CharSequence>,
    val groups: Series<Isam3Group>,
    val rowWidth: Int,
)

class Isam3Group(
    val type: IOMemento,
    val placements: Series<Isam3Placement>,
)

data class Isam3Placement(
    val name: CharSequence,
    val begin: Int,
    val width: Int,
) {
    val end: Int get() = begin + width
}

class Isam3View(
    val name: CharSequence,
    val columns: Series<CharSequence>,
)

data class Isam3ResolvedColumn(
    val partition: CharSequence,
    val file: CharSequence,
