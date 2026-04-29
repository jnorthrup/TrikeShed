package borg.trikeshed.isam

import borg.trikeshed.common.Files
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.meta
import borg.trikeshed.cursor.name
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeries
import borg.trikeshed.lib.get
import borg.trikeshed.lib.getOrNull
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import borg.trikeshed.parse.yaml.parse as parseYaml

private val ISAM3_RESERVED_TOP_LEVEL = setOf("isam", "views", "defaults")
private val ISAM3_RESERVED_GROUP_KEYS = setOf("flags", "coords")

class Isam3Layout(
    val version: Int,
    val partitions: Series<Isam3Partition>,
    val views: Series<Isam3View>,
) {
    fun partition(name: String): Isam3Partition? {
        for (partition in partitions.view) {
            if (partition.name == name) return partition
        }
        return null
    }

    fun file(name: String): Isam3File? {
        for (partition in partitions.view) {
            for (file in partition.files.view) {
                if (file.name == name) return file
            }
        }
        return null
    }

    fun view(name: String): Isam3View? = views.view.firstOrNull { it.name == name }

    fun defaultViewName(): String = views.getOrNull(0)?.name ?: "default"

    fun logicalNames(viewName: String? = null): Series<String> {
        val names = when (val resolvedView = when (viewName) {
            null -> views.getOrNull(0)
            else -> view(viewName) ?: error("Unknown ISAM3 view: $viewName")
        }) {
            null -> allPlacements().toList().map { it.name }.distinct()
            else -> resolvedView.columns.toList()
        }
        return names.toList().distinct().toSeries()
    }

    fun logicalMeta(viewName: String? = null): Series<ColumnMeta> {
        val resolved = resolvedColumns(viewName)
        return resolved.size j { index: Int -> resolved[index].meta }
    }

    fun recordMeta(viewName: String? = null): Series<RecordMeta> {
        val resolved = resolvedColumns(viewName)
        return resolved.size j { index: Int -> resolved[index].meta }
    }

    fun resolvedColumns(viewName: String? = null): Series<Isam3ResolvedColumn> {
        val names = logicalNames(viewName)
        if (names.size == 0) return emptySeries()
        return names.size j { index: Int ->
            val columnName = names[index]
            resolveColumn(columnName)
        }
    }

    fun allPlacements(): Series<Isam3Placement> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<Isam3Placement>()
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

    fun render(): String = buildString {
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

    private fun resolveColumn(columnName: String): Isam3ResolvedColumn {
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

    private fun renderGroup(group: Isam3Group, indent: String): String {
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
        fun parse(text: String): Isam3Layout = fromMap(parseYaml(text) as? Map<*, *> ?: error("ISAM layout root must be a map"))

        fun read(file: String): Isam3Layout = parse(Files.readString(file))

        fun fromConstraints(
            partitionName: String,
            constraints: Series<RecordMeta>,
            viewColumns: Series<String> = constraints.size j { index: Int -> constraints[index].name },
        ): Isam3Layout = fromCursor(partitionName, constraints, viewColumns)

        fun fromCursor(
            partitionName: String,
            constraints: Series<RecordMeta>,
            viewColumns: Series<String> = constraints.size j { index: Int -> constraints[index].name },
        ): Isam3Layout {
            val groupsByCluster = linkedMapOf<String, MutableList<RecordMeta>>()
            constraints.view.forEach { meta ->
                val cluster = clusterName(meta)
                groupsByCluster.getOrPut(cluster) { mutableListOf() }.add(meta)
            }

            val files = groupsByCluster.entries.map { (clusterName, metas) ->
                val groupByType = linkedMapOf<IOMemento, MutableList<RecordMeta>>()
                metas.forEach { meta ->
                    groupByType.getOrPut(meta.type) { mutableListOf() }.add(meta)
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
                val columns = mutableListOf<String>()
                val seen = linkedSetOf<String>()
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

        private fun parsePartition(name: String, raw: Any?): Isam3Partition {
            val map = raw.asMap("partition $name")
            val flags = parseFlags(map["flags"] ?: map["coords"], "partition $name flags")
            val files = map.entries
                .filter { (key, _) -> key !in ISAM3_RESERVED_GROUP_KEYS }
                .map { (fileName, fileRaw) -> parseFile(fileName, fileRaw) }
                .toSeries()
            return Isam3Partition(name, flags, files)
        }

        private fun parseFile(name: String, raw: Any?): Isam3File {
            val map = raw.asMap("file $name")
            val flags = parseFlags(map["flags"] ?: map["coords"], "file $name flags")
            var fileOffset = 0
            val groups = mutableListOf<Isam3Group>()
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

        private fun parseGroup(typeName: String, raw: Any?, context: String, fileOffset: Int): ParsedGroup {
            val type = IOMemento.valueOf(typeName)
            val placements = when (raw) {
                is List<*> -> parsePlacementList(type, raw, context, fileOffset)
                is Map<*, *> -> parsePlacementMap(type, raw.entries.associate { it.key.toString() to it.value }, context)
                else -> error("$context.$typeName must be a list or map, got ${typeNameOf(raw)}")
            }
            val spanEnd = placements.view.maxOfOrNull { it.end } ?: fileOffset
            return ParsedGroup(Isam3Group(type, placements), spanEnd)
        }

        private fun parsePlacementList(type: IOMemento, raw: List<*>, context: String, fileOffset: Int): Series<Isam3Placement> {
            if (raw.isEmpty()) return emptySeries()
            if (raw.size == 2 && raw[0] is Number && raw[1] is String) {
                val width = (raw[0] as Number).toInt()
                val name = raw[1].asString("$context ${type.name}")
                requireWidth(type, width, name, context)
                return 1 j { _: Int -> Isam3Placement(name, fileOffset, width) }
            }
            if (raw.all { it is String }) {
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
                    require(list.size == 2 && list[0] is Number && list[1] is String) {
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

        private fun parsePlacementMap(type: IOMemento, raw: Map<String, Any?>, context: String): Series<Isam3Placement> {
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

        private fun parseColumnNames(raw: Any?, context: String): Series<String> = when (raw) {
            is List<*> -> raw.map { it.asString(context) }.toSeries()
            is String -> 1 j { _: Int -> raw.asString(context) }
            else -> error("$context must be a list of strings")
        }

        private fun parseFlags(raw: Any?, context: String): Series<String> = when (raw) {
            null -> emptySeries()
            is List<*> -> raw.map { it.asString(context) }.toSeries()
            is String -> 1 j { _: Int -> raw.asString(context) }
            else -> error("$context must be a list of strings")
        }

        private fun widthFor(type: IOMemento, name: String, explicit: Int?, context: String): Int {
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

        private fun requireWidth(type: IOMemento, width: Int, name: String, context: String) {
            val fixed = type.networkSize
            if (fixed != null) {
                require(width >= fixed) {
                    "$context.${type.name} width for $name too small: $width < $fixed"
                }
            } else {
                require(width > 0) { "$context.${type.name} width for $name must be > 0" }
            }
        }

        private fun clusterName(meta: RecordMeta): String {
            val lower = meta.name.lowercase()
            return when {
                "time" in lower -> "time"
                "trade" in lower -> "trades"
                "volume" in lower || "quote_asset" in lower -> "volume"
                meta.name in setOf("Open", "High", "Low", "Close") -> "price"
                meta.type == IOMemento.IoInstant -> "time"
                meta.type == IOMemento.IoInt -> "trades"
                meta.type == IOMemento.IoDouble -> "price"
                meta.type == IOMemento.IoString -> "text"
                else -> meta.type.name.lowercase()
            }
        }

        private fun typeNameOf(value: Any?): String = value?.let { it::class.simpleName ?: it::class.toString() } ?: "null"

        private fun Any?.asMap(context: String): Map<String, Any?> = asMapOrNull(context) ?: error("$context must be a map")

        private fun Any?.asMapOrNull(context: String): Map<String, Any?>? = when (this) {
            null -> null
            is Map<*, *> -> entries.associate { it.key.toString() to it.value }
            else -> null
        }

        private fun Any?.asString(context: String): String = when (this) {
            is String -> this
            else -> error("$context must be a string, got ${typeNameOf(this)}")
        }

        private fun Any?.asInt(context: String): Int = when (this) {
            is Int -> this
            is Long -> this.toInt()
            is Short -> this.toInt()
            is Byte -> this.toInt()
            is Double -> this.toInt()
            is Float -> this.toInt()
            is String -> toIntOrNull() ?: error("$context must be an Int, got $this")
            else -> error("$context must be an Int, got ${typeNameOf(this)}")
        }
    }
}

private data class ParsedGroup(
    val group: Isam3Group,
    val spanEnd: Int,
)

class Isam3Partition(
    val name: String,
    val flags: Series<String>,
    val files: Series<Isam3File>,
)

class Isam3File(
    val name: String,
    val flags: Series<String>,
    val groups: Series<Isam3Group>,
    val rowWidth: Int,
)

class Isam3Group(
    val type: IOMemento,
    val placements: Series<Isam3Placement>,
)

data class Isam3Placement(
    val name: String,
    val begin: Int,
    val width: Int,
) {
    val end: Int get() = begin + width
}

class Isam3View(
    val name: String,
    val columns: Series<String>,
)

data class Isam3ResolvedColumn(
    val partition: String,
    val file: String,
    val type: IOMemento,
    val meta: RecordMeta,
    val begin: Int,
    val end: Int,
)

private fun renderInlineList(values: List<String>): String =
    values.joinToString(", ", prefix = "[", postfix = "]") { yamlScalar(it) }

private fun yamlScalar(value: String): String =
    if (value.matches(Regex("^[A-Za-z0-9_.:/+-]+$"))) value else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
