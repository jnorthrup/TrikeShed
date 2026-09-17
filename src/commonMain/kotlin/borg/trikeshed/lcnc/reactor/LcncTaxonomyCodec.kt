package borg.trikeshed.lcnc.reactor

import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.isam.LcncBlock
import borg.trikeshed.lcnc.isam.LcncDatabase
import borg.trikeshed.lcnc.isam.LcncEntity
import borg.trikeshed.lcnc.isam.LcncPage
import borg.trikeshed.lcnc.isam.LcncWorkspace
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.json.JsonSupport

/**
 * Materializes interchange documents into the one LCNC taxonomy.
 *
 * JSON is accepted as a canonical workspace/database/page/block shape, but
 * arbitrary JSON remains useful: objects become property blocks and arrays
 * become database rows. The original values stay in block content; this
 * codec does not turn an unknown field into a guessed semantic assertion.
 * HTML is reduced to visible, structurally typed blocks without a JVM DOM
 * dependency, so the same ingest contract is available in commonMain.
 */
object LcncTaxonomyCodec {

    fun json(text: String): Series<LcncEntity> = decodeJson(JsonSupport.parseStrict(text), "json", text)

    /** LCNC_NATIVE is the same taxonomy shape with optional entity wrappers. */
    fun native(text: String): Series<LcncEntity> = decodeJson(JsonSupport.parseStrict(text), "native", text)

    fun html(text: String): Series<LcncEntity> {
        val pageId = generated("html", "$", text)
        val blocks = ArrayList<LcncBlock>()
        var title = "HTML Import"
        var loose = StringBuilder()
        var activeTag: String? = null
        var activeType: String? = null
        var active = StringBuilder()
        var ignoredTag: String? = null
        var titleActive = false

        fun normalized(value: String): String = decodeEntities(value)
            .replace(Regex("\\s+"), " ")
            .trim()

        fun flushLoose() {
            val value = normalized(loose.toString())
            if (value.isNotEmpty()) {
                blocks.add(LcncBlock(
                    id = generated("block", "${blocks.size}:loose:$value", text),
                    type = "paragraph",
                    parentId = pageId,
                    content = value,
                ))
            }
            loose = StringBuilder()
        }

        fun flushActive() {
            val value = normalized(active.toString())
            if (value.isNotEmpty()) {
                if (titleActive) {
                    title = value
                } else {
                    blocks.add(LcncBlock(
                        id = generated("block", "${blocks.size}:$activeType:$value", text),
                        type = activeType ?: "paragraph",
                        parentId = pageId,
                        content = value,
                    ))
                }
            }
            active = StringBuilder()
            activeTag = null
            activeType = null
            titleActive = false
        }

        fun blockType(tag: String): String? = when (tag) {
            "p" -> "paragraph"
            "h1", "h2", "h3", "h4", "h5", "h6" -> "heading_${tag.substring(1)}"
            "li" -> "bulleted_list_item"
            "blockquote" -> "quote"
            "pre", "code" -> "code"
            "td", "th" -> "table_cell"
            else -> null
        }

        val tags = Regex("<!--[\\s\\S]*?-->|</?[A-Za-z][^>]*>")
        var cursor = 0
        for (match in tags.findAll(text)) {
            if (ignoredTag == null) {
                val rawText = text.substring(cursor, match.range.first)
                if (activeTag != null) active.append(rawText) else loose.append(rawText)
            }
            val raw = match.value
            if (raw.startsWith("<!--")) {
                cursor = match.range.last + 1
                continue
            }
            val closing = raw.startsWith("</")
            val tag = Regex("</?([A-Za-z][A-Za-z0-9:-]*)").find(raw)?.groupValues?.get(1)?.lowercase()
            if (tag == null) {
                cursor = match.range.last + 1
                continue
            }
            if (ignoredTag != null) {
                if (closing && tag == ignoredTag) ignoredTag = null
                cursor = match.range.last + 1
                continue
            }
            if (!closing && tag in setOf("script", "style", "noscript", "template")) {
                ignoredTag = tag
                cursor = match.range.last + 1
                continue
            }
            if (tag == "br") {
                if (activeTag != null) active.append('\n') else loose.append('\n')
                cursor = match.range.last + 1
                continue
            }
            if (closing) {
                if (activeTag == tag) flushActive()
                cursor = match.range.last + 1
                continue
            }
            val kind = when (tag) {
                "title" -> "title"
                else -> blockType(tag)
            }
            if (kind != null && activeTag == null) {
                flushLoose()
                activeTag = tag
                activeType = if (kind == "title") null else kind
                titleActive = kind == "title"
            }
            cursor = match.range.last + 1
        }
        if (ignoredTag == null) {
            val tail = text.substring(cursor)
            if (activeTag != null) active.append(tail) else loose.append(tail)
        }
        if (activeTag != null) flushActive()
        flushLoose()
        if (title == "HTML Import") {
            title = blocks.firstOrNull { it.type.startsWith("heading_") }?.content?.toString() ?: title
        }
        return 1 j { LcncPage(pageId, title, null, blocks.toSeries()) }
    }

    private fun decodeJson(value: Any?, prefix: String, source: String): Series<LcncEntity> {
        require(value != null) { "${prefix.uppercase()} document must not be null" }
        return when (value) {
            is Map<*, *> -> decodeMap(value, prefix, source, "$")
            is List<*> -> listDatabase(value, prefix, source, "$")
            is Array<*> -> listDatabase(value.toList(), prefix, source, "$")
            else -> 1 j { pageFromValue(value, prefix, source, "$") }
        }
    }

    private fun decodeMap(raw: Map<*, *>, prefix: String, source: String, path: String): Series<LcncEntity> {
        val map = raw.entries.associate { it.key.toString() to it.value }
        val kind = text(map, "kind", "entityType", "type")?.lowercase()
        val wrapped = mapValue(map, "workspace", "database", "page", "block", "entity")
        if (wrapped is Map<*, *>) {
            val wrappedKey = map.keys.firstOrNull { it.equals("workspace", true) || it.equals("database", true) ||
                it.equals("page", true) || it.equals("block", true) || it.equals("entity", true) }
            return decodeMap(wrapped, prefix, source, "$path/${wrappedKey ?: "entity"}")
        }
        return when {
            kind == "workspace" || hasAny(map, "databases") -> 1 j {
                workspace(map, prefix, source, path)
            }
            kind == "database" || hasAny(map, "rows", "records") -> 1 j {
                database(map, prefix, source, path, null)
            }
            kind == "page" -> 1 j { page(map, prefix, source, path, null) }
            kind == "block" -> 1 j { block(map, prefix, source, path, null) }
            hasAny(map, "pages") && !hasAny(map, "blocks", "contentBlocks") -> 1 j {
                database(map, prefix, source, path, null)
            }
            hasAny(map, "blocks", "contentBlocks") -> 1 j { page(map, prefix, source, path, null) }
            hasAny(map, "items", "data") && asList(mapValue(map, "items", "data")) != null ->
                listDatabase(asList(mapValue(map, "items", "data"))!!, prefix, source, "$path/items")
            else -> 1 j { pageFromObject(map, prefix, source, path) }
        }
    }

    private fun workspace(map: Map<String, Any?>, prefix: String, source: String, path: String): LcncWorkspace {
        val id = text(map, "id") ?: generated("workspace", path, source)
        val databases = asList(mapValue(map, "databases"))?.mapIndexed { index, value ->
            val child = asMap(value) ?: mapOf("value" to value)
            database(child, prefix, source, "$path/databases/$index", id)
        } ?: emptyList()
        val pages = asList(mapValue(map, "pages"))?.mapIndexed { index, value ->
            val child = asMap(value) ?: mapOf("value" to value)
            page(child, prefix, source, "$path/pages/$index", id)
        } ?: emptyList()
        return LcncWorkspace(id, text(map, "name", "title") ?: "Imported Workspace", databases.toSeries(), pages.toSeries())
    }

    private fun database(
        map: Map<String, Any?>,
        prefix: String,
        source: String,
        path: String,
        parentId: String?,
    ): LcncDatabase {
        val id = text(map, "id") ?: generated("database", path, source)
        val rows = asList(mapValue(map, "pages", "rows", "records", "items", "data")) ?: emptyList()
        val pages = rows.mapIndexed { index, value ->
            val child = asMap(value) ?: mapOf("value" to value)
            page(child, prefix, source, "$path/pages/$index", id)
        }
        val title = text(map, "title", "name") ?: "Imported Database"
        return LcncDatabase(id, title, parentId ?: text(map, "parentId"), pages.toSeries())
    }

    private fun listDatabase(values: List<Any?>, prefix: String, source: String, path: String): Series<LcncEntity> {
        val databaseId = generated("database", path, source)
        val pages = values.mapIndexed { index, value ->
            val map = asMap(value) ?: mapOf("value" to value)
            page(map, prefix, source, "$path/pages/$index", databaseId)
        }
        return 1 j { LcncDatabase(databaseId, "Imported Database", null, pages.toSeries()) }
    }

    private fun page(
        map: Map<String, Any?>,
        prefix: String,
        source: String,
        path: String,
        parentId: String?,
    ): LcncPage {
        val id = text(map, "id") ?: generated("page", path, source)
        val title = text(map, "title", "name") ?: text(map, "value") ?: id
        val blocksValue = mapValue(map, "blocks", "contentBlocks")
        val blocks = asList(blocksValue)?.mapIndexed { index, value ->
            val child = asMap(value)
            if (child != null) block(child, prefix, source, "$path/blocks/$index", id)
            else LcncBlock(generated("block", "$path/blocks/$index", source), "paragraph", id, content = value)
        } ?: contentBlock(map, prefix, source, path, id)
        return LcncPage(id, title, parentId ?: text(map, "parentId"), blocks.toSeries())
    }

    private fun pageFromObject(map: Map<String, Any?>, prefix: String, source: String, path: String): LcncPage =
        page(map, prefix, source, path, null)

    private fun pageFromValue(value: Any?, prefix: String, source: String, path: String): LcncPage {
        val id = generated("page", path, source)
        val block = LcncBlock(generated("block", path, source), "paragraph", id, content = value)
        return LcncPage(id, value?.toString() ?: "JSON Import", null, 1 j { block })
    }

    private fun contentBlock(map: Map<String, Any?>, prefix: String, source: String, path: String, parentId: String): List<LcncBlock> {
        val contentKey = listOf("content", "text", "value").firstOrNull { map.containsKey(it) }
        if (contentKey != null) {
            return listOf(LcncBlock(generated("block", "$path/$contentKey", source), "paragraph", parentId,
                content = map[contentKey]))
        }
        val structural = setOf("id", "title", "name", "parentId", "kind", "type", "blocks", "contentBlocks")
        return map.entries.filter { it.key !in structural }.map { (key, value) ->
            LcncBlock(
                id = generated("block", "$path/$key", source),
                type = "property",
                parentId = parentId,
                content = mapOf("name" to key, "value" to value),
            )
        }
    }

    private fun block(map: Map<String, Any?>, prefix: String, source: String, path: String, parentId: String?): LcncBlock {
        val id = text(map, "id") ?: generated("block", path, source)
        val type = text(map, "type", "kind") ?: "paragraph"
        val children = asList(mapValue(map, "children"))?.mapIndexed { index, value ->
            val child = asMap(value) ?: mapOf("value" to value)
            block(child, prefix, source, "$path/children/$index", id)
        }
        val content = when {
            map.containsKey("content") -> map["content"]
            map.containsKey("text") -> map["text"]
            map.containsKey("value") -> map["value"]
            else -> null
        }
        return LcncBlock(id, type, parentId ?: text(map, "parentId"), children?.toSeries(), content)
    }

    private fun generated(kind: String, path: String, source: String): String =
        "$kind-${ContentId.of("$source\n$path".encodeToByteArray()).hex.take(16)}"

    private fun hasAny(map: Map<String, Any?>, vararg names: String): Boolean = names.any { name ->
        map.keys.any { it.equals(name, true) }
    }

    private fun mapValue(map: Map<String, Any?>, vararg names: String): Any? = names.firstNotNullOfOrNull { name ->
        map.entries.firstOrNull { it.key.equals(name, true) }?.value
    }

    private fun text(map: Map<String, Any?>, vararg names: String): String? = mapValue(map, *names)?.let {
        it as? String ?: if (it is Number || it is Boolean) it.toString() else null
    }?.takeIf { it.isNotBlank() }

    private fun asMap(value: Any?): Map<String, Any?>? = (value as? Map<*, *>)?.entries?.associate {
        it.key.toString() to it.value
    }

    private fun asList(value: Any?): List<Any?>? = when (value) {
        is List<*> -> value
        is Array<*> -> value.toList()
        else -> null
    }

    private fun decodeEntities(text: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")
        .replace(text) { match ->
            when (val entity = match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                else -> {
                    val code = if (entity.startsWith("#x")) entity.substring(2).toIntOrNull(16)
                    else entity.substring(1).toIntOrNull()
                    code?.toChar()?.toString() ?: match.value
                }
            }
        }
}
