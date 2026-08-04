package borg.trikeshed.forge

import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.kanban.KanbanBoard
import borg.trikeshed.kanban.KanbanBoardId
import borg.trikeshed.kanban.KanbanCard
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.KanbanColumn
import borg.trikeshed.kanban.KanbanColumnId
import borg.trikeshed.kanban.moveCard
import kotlinx.serialization.Serializable
import kotlin.random.Random

// ─── Block IDs ───────────────────────────────────────────────────────────────

@Serializable
data class ForgeBlockId(val value: String) {
    companion object {
        fun generate(): ForgeBlockId = ForgeBlockId("blk-${Random.nextLong().toString(36)}")
    }
}

// ─── Block kinds ─────────────────────────────────────────────────────────────

@Serializable
enum class ForgeBlockKind {
    PAGE, TEXT, HEADING_1, HEADING_2, HEADING_3,
    BULLET, NUMBERED, TODO, QUOTE, CODE,
    DIVIDER, CALLOUT, DATABASE, DATABASE_ROW,
    TABLE, TABLE_ROW,
}

// ─── Block model ─────────────────────────────────────────────────────────────

@Serializable
data class ForgeBlock(
    val id: ForgeBlockId,
    val kind: ForgeBlockKind,
    val text: String,
    val parentId: ForgeBlockId?,
    val children: List<ForgeBlockId> = emptyList(),
    val properties: Map<String, String> = emptyMap(),
)

// ─── Cursor ──────────────────────────────────────────────────────────────────

@Serializable
data class ForgeCursor(
    val pageId: ForgeBlockId,
    val blockId: ForgeBlockId,
    val textOffset: Int = 0,
)

// ─── Document state ──────────────────────────────────────────────────────────

@Serializable
data class ForgeDocument(
    val rootPageId: ForgeBlockId,
    val blocks: Map<String, ForgeBlock>,
    val cursor: ForgeCursor,
) {
    fun appendBlock(kind: ForgeBlockKind, text: String, properties: Map<String, String> = emptyMap()): ForgeDocument {
        return ForgeDoc.appendBlock(this, cursor.blockId, kind, text, properties)
    }

    fun updateText(blockId: ForgeBlockId, text: String): ForgeDocument {
        return ForgeDoc.updateText(this, blockId, text)
    }

    fun deleteBlock(blockId: ForgeBlockId): ForgeDocument {
        return ForgeDoc.deleteBlock(this, blockId)
    }

    fun moveCard(cardId: borg.trikeshed.kanban.KanbanCardId, toColumnId: borg.trikeshed.kanban.KanbanColumnId): ForgeDocument {
        val board = this.toKanbanBoard()
        val updated = board.moveCard(cardId, toColumnId)
        return updated.toForgeDocument()
    }

    fun toMarkdown(): String {
        return ForgeDoc.renderMarkdown(this)
    }

    companion object {
        fun empty(): ForgeDocument = ForgeDocument(ForgeBlockId("empty"), emptyMap(), ForgeCursor(ForgeBlockId("empty"), ForgeBlockId("empty")))
    }
    fun block(id: ForgeBlockId): ForgeBlock? = blocks[id.value]
    fun requireBlock(id: ForgeBlockId): ForgeBlock =
        block(id) ?: error("Missing block: ${id.value}")
}

// ─── Editor: pure state transitions ──────────────────────────────────────────

object ForgeDoc {

    fun page(rootPageId: ForgeBlockId, title: String): ForgeDocument {
        val page = ForgeBlock(
            id = rootPageId,
            kind = ForgeBlockKind.PAGE,
            text = title,
            parentId = null,
            children = emptyList(),
        )
        return ForgeDocument(
            rootPageId = rootPageId,
            blocks = mapOf(rootPageId.value to page),
            cursor = ForgeCursor(rootPageId, rootPageId, 0),
        )
    }

    fun empty(title: String = "Untitled"): ForgeDocument {
        val pageId = ForgeBlockId.generate()
        val doc = page(pageId, title)
        val firstChildId = ForgeBlockId.generate()
        val firstChild = ForgeBlock(
            id = firstChildId,
            kind = ForgeBlockKind.TEXT,
            text = "",
            parentId = pageId,
        )
        val page = doc.requireBlock(pageId).copy(children = listOf(firstChildId))
        return doc.copy(
            blocks = doc.blocks + (pageId.value to page) + (firstChildId.value to firstChild),
            cursor = ForgeCursor(pageId, firstChildId, 0),
        )
    }

    fun appendBlock(
        doc: ForgeDocument,
        parentId: ForgeBlockId,
        kind: ForgeBlockKind,
        text: String,
        properties: Map<String, String> = emptyMap(),
    ): ForgeDocument = appendBlockWithId(doc, parentId, ForgeBlockId.generate(), kind, text, properties)

    /**
     * Append a block with an explicit [id]. The projection boundary (e.g.
     * `KanbanBoard.toForgeDocument`) uses this to preserve source ids so the
     * round-trip stays identity-stable.
     */
    fun appendBlockWithId(
        doc: ForgeDocument,
        parentId: ForgeBlockId,
        blockId: ForgeBlockId,
        kind: ForgeBlockKind,
        text: String,
        properties: Map<String, String> = emptyMap(),
    ): ForgeDocument {
        check(!(blockId in doc.blocks.values.map { it.id })) { "blockId collision: $blockId" }
        val parent = doc.requireBlock(parentId)
        val block = ForgeBlock(
            id = blockId,
            kind = kind,
            text = text,
            parentId = parent.id,
            properties = properties,
        )
        val updatedParent = parent.copy(children = parent.children + block.id)
        return doc.copy(
            blocks = doc.blocks + (block.id.value to block) + (updatedParent.id.value to updatedParent),
            cursor = doc.cursor.copy(blockId = block.id, textOffset = text.length),
        )
    }

    fun insertBlockAfter(
        doc: ForgeDocument,
        afterBlockId: ForgeBlockId,
        kind: ForgeBlockKind,
        text: String,
        properties: Map<String, String> = emptyMap(),
    ): ForgeDocument {
        val target = doc.requireBlock(afterBlockId)
        val parentId = target.parentId ?: target.id
        val parent = doc.requireBlock(parentId)
        val block = ForgeBlock(
            id = ForgeBlockId.generate(),
            kind = kind,
            text = text,
            parentId = parent.id,
            properties = properties,
        )
        val insertAt = parent.children.indexOf(afterBlockId).let {
            if (it < 0) parent.children.size else it + 1
        }
        val updatedChildren = parent.children.toMutableList().also { it.add(insertAt, block.id) }
        val updatedParent = parent.copy(children = updatedChildren)
        return doc.copy(
            blocks = doc.blocks + (block.id.value to block) + (updatedParent.id.value to updatedParent),
            cursor = doc.cursor.copy(blockId = block.id, textOffset = text.length),
        )
    }

    fun updateText(doc: ForgeDocument, blockId: ForgeBlockId, text: String): ForgeDocument {
        val block = doc.requireBlock(blockId)
        val updated = block.copy(text = text)
        return doc.copy(
            blocks = doc.blocks + (blockId.value to updated),
            cursor = doc.cursor.copy(blockId = blockId, textOffset = text.length),
        )
    }

    fun deleteBlock(doc: ForgeDocument, blockId: ForgeBlockId): ForgeDocument {
        val block = doc.requireBlock(blockId)
        if (blockId == doc.rootPageId) return doc
        val parentId = block.parentId ?: return doc
        val parent = doc.requireBlock(parentId)
        val idx = parent.children.indexOf(blockId)
        val newFocus = parent.children.getOrNull(idx - 1) ?: parentId
        val updatedParent = parent.copy(children = parent.children.filterNot { it == blockId })
        return doc.copy(
            blocks = doc.blocks + (updatedParent.id.value to updatedParent) - blockId.value,
            cursor = doc.cursor.copy(blockId = newFocus),
        )
    }

    fun setProperty(doc: ForgeDocument, blockId: ForgeBlockId, key: String, value: String): ForgeDocument {
        val block = doc.requireBlock(blockId)
        val updated = block.copy(properties = block.properties + (key to value))
        return doc.copy(blocks = doc.blocks + (blockId.value to updated))
    }

    fun renderMarkdown(doc: ForgeDocument, pageId: ForgeBlockId = doc.rootPageId): String {
        val page = doc.requireBlock(pageId)
        val result = buildString {
            if (page.text.isNotBlank()) {
                append("# ").append(page.text).append("\n")
            }
            page.children.forEach { childId -> renderBlock(doc, childId, 0, this) }
        }
        return result.trimEnd() + "\n"
    }

    private fun renderBlock(doc: ForgeDocument, blockId: ForgeBlockId, depth: Int, out: StringBuilder) {
        val block = doc.block(blockId) ?: return
        val indent = "  ".repeat(depth)
        when (block.kind) {
            ForgeBlockKind.PAGE -> { if (block.text.isNotBlank()) out.append(indent).append("# ").append(block.text).append("\n") }
            ForgeBlockKind.TEXT -> out.append(indent).append(block.text).append("\n")
            ForgeBlockKind.HEADING_1 -> out.append(indent).append("# ").append(block.text).append("\n")
            ForgeBlockKind.HEADING_2 -> out.append(indent).append("## ").append(block.text).append("\n")
            ForgeBlockKind.HEADING_3 -> out.append(indent).append("### ").append(block.text).append("\n")
            ForgeBlockKind.BULLET -> out.append(indent).append("- ").append(block.text).append("\n")
            ForgeBlockKind.NUMBERED -> out.append(indent).append("1. ").append(block.text).append("\n")
            ForgeBlockKind.TODO -> {
                val checked = if (block.properties["checked"] == "true") "x" else " "
                out.append(indent).append("- [").append(checked).append("] ").append(block.text).append("\n")
            }
            ForgeBlockKind.QUOTE -> out.append(indent).append("> ").append(block.text).append("\n")
            ForgeBlockKind.CODE -> {
                val lang = block.properties["language"].orEmpty()
                out.append(indent).append("```").append(lang).append("\n")
                block.text.lines().forEach { out.append(indent).append(it).append("\n") }
                out.append(indent).append("```").append("\n")
            }
            ForgeBlockKind.DIVIDER -> out.append(indent).append("---").append("\n")
            ForgeBlockKind.CALLOUT -> out.append(indent).append("> ").append(block.properties["icon"] ?: "💡").append(" ").append(block.text).append("\n")
            ForgeBlockKind.DATABASE -> out.append(indent).append("| ").append(block.text.ifBlank { "Database" }).append(" |").append("\n")
            ForgeBlockKind.DATABASE_ROW -> out.append("$indent- ${block.text} ${block.properties.entries.joinToString(" ") { "${it.key}=${it.value}" }}".trimEnd()).append("\n")
            ForgeBlockKind.TABLE -> out.append(indent).append("| ").append(block.text.ifBlank { "Table" }).append(" |").append("\n")
            ForgeBlockKind.TABLE_ROW -> out.append(indent).append("| ").append(block.text).append(" |").append("\n")
        }
        block.children.forEach { child -> renderBlock(doc, child, depth + 1, out) }
    }
}

// ─── Kanban projection: Forge → KanbanBoard ─────────────────────────────────

/**
 * Project a Forge document into a KanbanBoard.
 *
 * Each heading becomes a card. Columns are derived from the page block's
 * `kanban.columns` property (a JSON array of `{id,name,order,wipLimit?}`)
 * when present; otherwise the schema falls back to the canonical
 * `backlog / in-progress / done` triple. This makes the projection
 * identity-stable for arbitrary column sets and lets `MoveCard` round-trip
 * custom columns like `col-b` without losing them.
 */
fun ForgeDocument.toKanbanBoard(): KanbanBoard {
    val page = requireBlock(rootPageId)

    // Parse `kanban.columns` if present so the column-set survives round-trip.
    val customColumns = parseKanbanColumnsProperty(page.properties["kanban.columns"])

    val backlog = KanbanColumnId("col-backlog")
    val inprog = KanbanColumnId("col-inprogress")
    val done = KanbanColumnId("col-done")
    // When customColumns is empty we use the canonical mapping directly.
    // When non-empty we honour explicit kanban.column.id, otherwise fall
    // back to the canonical mapping IF the column exists in the custom
    // set; otherwise map to the first custom column so the projection
    // never silently drops a card.
    val BlockColumnId: (String) -> KanbanColumnId = { status ->
        val canonical = when (status) {
            "in-progress" -> inprog
            "done" -> done
            else -> backlog
        }
        when {
            customColumns.isEmpty() -> canonical
            customColumns.any { it.id == canonical.value } -> canonical
            else -> KanbanColumnId(customColumns.first().id)
        }
    }

    val cards = mutableListOf<KanbanCard>()
    var order = 0

    fun walk(blockId: ForgeBlockId) {
        val block = block(blockId) ?: return
        when (block.kind) {
            ForgeBlockKind.HEADING_1, ForgeBlockKind.HEADING_2, ForgeBlockKind.HEADING_3 -> {
                val status = block.properties["kanban.status"] ?: "backlog"
                // Honour explicit `kanban.column.id` if present; fall back to
                // the kanban-status→column heuristic for legacy documents.
                val columnId = block.properties["kanban.column.id"]
                    ?.let { id -> customColumns.firstOrNull { it.id == id }?.let { KanbanColumnId(it.id) } }
                    ?: BlockColumnId(status)
                val priority = when (block.properties["kanban.priority"]) {
                    "critical" -> CardPriority.CRITICAL
                    "high" -> CardPriority.HIGH
                    "low" -> CardPriority.LOW
                    else -> CardPriority.MEDIUM
                }
                val childTexts = block.children.mapNotNull { cid ->
                    block(cid)?.takeIf { it.kind == ForgeBlockKind.BULLET || it.kind == ForgeBlockKind.TODO }
                        ?.let { "- ${it.text}" }
                }
                cards.add(
                    KanbanCard(
                        id = KanbanCardId(block.id.value),
                        title = block.text,
                        description = childTexts.joinToString("\n"),
                        columnId = columnId,
                        order = order++,
                        priority = priority,
                        assignee = block.properties["kanban.assignee"],
                        tags = block.properties["kanban.tags"]?.split(",")?.map { it.trim() }?.toSet().orEmpty(),
                    )
                )
            }
            else -> {}
        }
        block.children.forEach { walk(it) }
    }

    page.children.forEach { walk(it) }

    val columns: List<KanbanColumn> = if (customColumns.isNotEmpty()) {
        customColumns.map { c ->
            KanbanColumn(
                id = KanbanColumnId(c.id),
                name = c.name,
                order = c.order,
                wipLimit = c.wipLimit,
            )
        }
    } else {
        listOf(
            KanbanColumn(backlog, "Backlog", 0),
            KanbanColumn(inprog, "In Progress", 1, wipLimit = 3),
            KanbanColumn(done, "Done", 2),
        )
    }

    return KanbanBoard(
        id = KanbanBoardId(rootPageId.value),
        name = page.text.ifBlank { "Untitled" },
        columns = columns,
        cards = cards,
    )
}

/** A column as encoded in the `kanban.columns` page property. */
private data class KanbanColumnRef(
    val id: String,
    val name: String,
    val order: Int,
    val wipLimit: Int? = null,
) {
    companion object {
        val ID_REGEX = Regex("\"id\"\\s*:\\s*(\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"|([0-9]+))")
        val NAME_REGEX = Regex("\"name\"\\s*:\\s*(\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"|([0-9]+))")
        val ORDER_REGEX = Regex("\"order\"\\s*:\\s*(\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"|([0-9]+))")
        val WIP_LIMIT_REGEX = Regex("\"wipLimit\"\\s*:\\s*(\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"|([0-9]+))")
    }
}

/**
 * Lightweight parser for `kanban.columns` JSON arrays.
 *
 * Format: `[{"id":"col-…","name":"…","order":N,"wipLimit":N?}, ...]`.
 * The parser is forgiving on whitespace and skips malformed entries rather
 * than throwing — projection freshness matters more than strict parsing.
 */
private fun parseKanbanColumnsProperty(raw: String?): List<KanbanColumnRef> {
    if (raw.isNullOrBlank()) return emptyList()
    val trimmed = raw.trim()
    if (!trimmed.startsWith('[') || !trimmed.endsWith(']')) return emptyList()
    val body = StringSlice(trimmed, 1, trimmed.length - 1)
    if (body.isBlank()) return emptyList()
    val out = mutableListOf<KanbanColumnRef>()
    var depth = 0
    var start = -1
    body.forEachIndexed { i, ch ->
        when (ch) {
            '{' -> {
                if (depth == 0) start = i
                depth++
            }
            '}' -> {
                depth--
                if (depth == 0 && start >= 0) {
                    val entry = StringSlice(body, start, i + 1)
                    parseKanbanColumnObject(entry)?.let { out += it }
                    start = -1
                }
            }
        }
    }
    return out
}



private fun parseKanbanColumnObject(entry: CharSequence): KanbanColumnRef? {
    fun field(regex: Regex): String? {
        val m = regex.find(entry) ?: return null
        return m.groupValues[2].ifEmpty { m.groupValues[3] }
    }
    val id = field(regex = KanbanColumnRef.ID_REGEX) ?: return null
    val name = field(regex = KanbanColumnRef.NAME_REGEX) ?: id
    val order = field(regex = KanbanColumnRef.ORDER_REGEX)?.toIntOrNull() ?: 0
    val wipLimit = field(regex = KanbanColumnRef.WIP_LIMIT_REGEX)?.toIntOrNull()
    return KanbanColumnRef(name = name, id = id, order = order, wipLimit = wipLimit)
}

/** Stable JSON serialiser for `kanban.columns` page-block property. */
private fun encodeKanbanColumns(columns: List<KanbanColumn>): String =
    columns.joinToString(prefix = "[", postfix = "]") { c ->
        buildString {
            append("{\"id\":\"").append(escapeJson(c.id.value))
            append("\",\"name\":\"").append(escapeJson(c.name))
            append("\",\"order\":").append(c.order)
            if (c.wipLimit != null) append(",\"wipLimit\":").append(c.wipLimit)
            append("}")
        }
    }

private fun escapeJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * Project a KanbanBoard back into a Forge document.
 *
 * Each card becomes a heading with kanban.* properties. Source card ids are
 * preserved as the heading-block ids so the round-trip stays
 * identity-stable. This is the two-way bridge: Forge ↔ Kanban.
 */
fun KanbanBoard.toForgeDocument(rootPageId: ForgeBlockId = ForgeBlockId(id.value)): ForgeDocument {
    val pageId = rootPageId
    val initial = ForgeDoc.page(pageId, title = name)

    // Encode column set as a `kanban.columns` page-block property so the
    // list survives the round-trip (otherwise `toKanbanBoard()` falls back
    // to the canonical backlog/inprogress/done triple).
    val withColumns = columns.takeIf { it.isNotEmpty() }?.let { cols ->
        ForgeDoc.setProperty(initial, pageId, "kanban.columns", encodeKanbanColumns(cols))
    } ?: initial

    // Fold cards in stable order; each step appends the heading with the
    // card's source id, then optionally appends child bullets carrying
    // stable ids per line.
    val finalDoc = cards.sortedBy { it.order }.fold(withColumns) { acc, card ->
        val headingId = ForgeBlockId(card.id.value)
        val withHeading = ForgeDoc.appendBlockWithId(
            doc = acc,
            parentId = pageId,
            blockId = headingId,
            kind = ForgeBlockKind.HEADING_2,
            text = card.title,
            properties = card.toProperties(),
        )
        if (card.description.isBlank()) {
            withHeading
        } else {
            // Stable child ids derived from the card id so round-trip is
            // idempotent.
            card.description
                .lines()
                .filter { it.isNotBlank() }
                .mapIndexed { lineIdx, line ->
                    ForgeBlockId(card.id.value + "-l" + lineIdx) to
                        line.removePrefix("- ").removePrefix("* ")
                }
                .fold(withHeading) { innerAcc, (bulletId, text) ->
                    ForgeDoc.appendBlockWithId(
                        doc = innerAcc,
                        parentId = headingId,
                        blockId = bulletId,
                        kind = ForgeBlockKind.BULLET,
                        text = text,
                    )
                }
        }
    }
    return finalDoc
}

private fun KanbanCard.toProperties(): Map<String, String> = buildMap {
    val status = when (columnId.value) {
        "col-inprogress" -> "in-progress"
        "col-done" -> "done"
        else -> "backlog"
    }
    val priority = when (priority) {
        CardPriority.CRITICAL -> "critical"
        CardPriority.HIGH -> "high"
        CardPriority.LOW -> "low"
        else -> "medium"
    }
    put("kanban.status", status)
    put("kanban.priority", priority)
    if (assignee != null) put("kanban.assignee", assignee!!)
    if (tags.isNotEmpty()) put("kanban.tags", tags.joinToString(","))
}





private class StringSlice(
    private val source: CharSequence,
    private val startIndex: Int,
    private val endIndex: Int
) : CharSequence {
    override val length: Int get() = endIndex - startIndex
    override fun get(index: Int): Char = source[startIndex + index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        StringSlice(source, this.startIndex + startIndex, this.startIndex + endIndex)
    override fun toString(): String = source.substring(startIndex, endIndex)
}
