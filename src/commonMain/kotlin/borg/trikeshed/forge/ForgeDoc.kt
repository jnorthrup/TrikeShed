package borg.trikeshed.forge

import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.kanban.KanbanBoard
import borg.trikeshed.kanban.KanbanBoardId
import borg.trikeshed.kanban.KanbanCard
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.KanbanColumn
import borg.trikeshed.kanban.KanbanColumnId
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
    fun block(id: ForgeBlockId): ForgeBlock? = blocks[id.value]
    fun requireBlock(id: ForgeBlockId): ForgeBlock =
        block(id) ?: error("Missing block: ${id.value}")
}

// ─── Editor: pure state transitions ──────────────────────────────────────────

object ForgeDoc {

    fun empty(title: String = "Untitled"): ForgeDocument {
        val pageId = ForgeBlockId.generate()
        val firstChildId = ForgeBlockId.generate()
        val page = ForgeBlock(
            id = pageId,
            kind = ForgeBlockKind.PAGE,
            text = title,
            parentId = null,
            children = listOf(firstChildId),
        )
        val firstChild = ForgeBlock(
            id = firstChildId,
            kind = ForgeBlockKind.TEXT,
            text = "",
            parentId = pageId,
        )
        return ForgeDocument(
            rootPageId = pageId,
            blocks = mapOf(pageId.value to page, firstChildId.value to firstChild),
            cursor = ForgeCursor(pageId, firstChildId, 0),
        )
    }

    fun appendBlock(
        doc: ForgeDocument,
        parentId: ForgeBlockId,
        kind: ForgeBlockKind,
        text: String,
        properties: Map<String, String> = emptyMap(),
    ): ForgeDocument {
        val parent = doc.requireBlock(parentId)
        val block = ForgeBlock(
            id = ForgeBlockId.generate(),
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
        val lines = mutableListOf<String>()
        if (page.text.isNotBlank()) lines += "# ${page.text}"
        page.children.forEach { childId -> renderBlock(doc, childId, 0, lines) }
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    private fun renderBlock(doc: ForgeDocument, blockId: ForgeBlockId, depth: Int, lines: MutableList<String>) {
        val block = doc.block(blockId) ?: return
        val indent = "  ".repeat(depth)
        when (block.kind) {
            ForgeBlockKind.PAGE -> { if (block.text.isNotBlank()) lines += "${indent}# ${block.text}" }
            ForgeBlockKind.TEXT -> lines += "$indent${block.text}"
            ForgeBlockKind.HEADING_1 -> lines += "${indent}# ${block.text}"
            ForgeBlockKind.HEADING_2 -> lines += "${indent}## ${block.text}"
            ForgeBlockKind.HEADING_3 -> lines += "${indent}### ${block.text}"
            ForgeBlockKind.BULLET -> lines += "$indent- ${block.text}"
            ForgeBlockKind.NUMBERED -> lines += "${indent}1. ${block.text}"
            ForgeBlockKind.TODO -> {
                val checked = if (block.properties["checked"] == "true") "x" else " "
                lines += "$indent- [$checked] ${block.text}"
            }
            ForgeBlockKind.QUOTE -> lines += "$indent> ${block.text}"
            ForgeBlockKind.CODE -> {
                val lang = block.properties["language"].orEmpty()
                lines += "$indent```$lang"
                lines += block.text.lines().joinToString("\n") { "$indent$it" }
                lines += "$indent```"
            }
            ForgeBlockKind.DIVIDER -> lines += "$indent---"
            ForgeBlockKind.CALLOUT -> lines += "$indent> ${block.properties["icon"] ?: "💡"} ${block.text}"
            ForgeBlockKind.DATABASE -> lines += "$indent| ${block.text.ifBlank { "Database" }} |"
            ForgeBlockKind.DATABASE_ROW -> lines += "$indent- ${block.text} ${block.properties.entries.joinToString(" ") { "${it.key}=${it.value}" }}".trimEnd()
            ForgeBlockKind.TABLE -> lines += "$indent| ${block.text.ifBlank { "Table" }} |"
            ForgeBlockKind.TABLE_ROW -> lines += "$indent| ${block.text} |"
        }
        block.children.forEach { child -> renderBlock(doc, child, depth + 1, lines) }
    }
}

// ─── Kanban projection: Forge → KanbanBoard ─────────────────────────────────

/**
 * Project a Forge document into a KanbanBoard.
 *
 * Each heading becomes a card. Bullet/TODO under a heading become that card's
 * sub-tasks. The KanbanBoard is a first-class client of the Forge surface.
 */
fun ForgeDocument.toKanbanBoard(): KanbanBoard {
    val page = requireBlock(rootPageId)
    val backlog = KanbanColumnId("col-backlog")
    val inprog = KanbanColumnId("col-inprogress")
    val done = KanbanColumnId("col-done")

    val cards = mutableListOf<KanbanCard>()
    var order = 0

    fun walk(blockId: ForgeBlockId) {
        val block = block(blockId) ?: return
        when (block.kind) {
            ForgeBlockKind.HEADING_1, ForgeBlockKind.HEADING_2, ForgeBlockKind.HEADING_3 -> {
                val status = block.properties["kanban.status"] ?: "backlog"
                val columnId = when (status) {
                    "in-progress" -> inprog
                    "done" -> done
                    else -> backlog
                }
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
                cards.add(KanbanCard(
                    id = KanbanCardId(block.id.value),
                    title = block.text,
                    description = childTexts.joinToString("\n"),
                    columnId = columnId,
                    order = order++,
                    priority = priority,
                    assignee = block.properties["kanban.assignee"],
                    tags = block.properties["kanban.tags"]?.split(",")?.map { it.trim() }?.toSet().orEmpty(),
                ))
            }
            else -> {}
        }
        block.children.forEach { walk(it) }
    }

    page.children.forEach { walk(it) }

    return KanbanBoard(
        id = KanbanBoardId(rootPageId.value),
        name = page.text.ifBlank { "Untitled" },
        columns = listOf(
            KanbanColumn(backlog, "Backlog", 0),
            KanbanColumn(inprog, "In Progress", 1, wipLimit = 3),
            KanbanColumn(done, "Done", 2),
        ),
        cards = cards,
    )
}

/**
 * Project a KanbanBoard back into a Forge document.
 *
 * Each card becomes a heading with kanban.* properties. This is the two-way
 * bridge: Forge ↔ Kanban.
 */
fun KanbanBoard.toForgeDocument(): ForgeDocument {
    var doc = ForgeDoc.empty(title = name)
    val pageId = doc.rootPageId

    for (card in cards.sortedBy { it.order }) {
        val status = when (card.columnId.value) {
            "col-inprogress" -> "in-progress"
            "col-done" -> "done"
            else -> "backlog"
        }
        val priority = when (card.priority) {
            CardPriority.CRITICAL -> "critical"
            CardPriority.HIGH -> "high"
            CardPriority.LOW -> "low"
            else -> "medium"
        }
        val props = buildMap {
            put("kanban.status", status)
            put("kanban.priority", priority)
            if (card.assignee != null) put("kanban.assignee", card.assignee)
            if (card.tags.isNotEmpty()) put("kanban.tags", card.tags.joinToString(","))
        }
        doc = ForgeDoc.appendBlock(doc, pageId, ForgeBlockKind.HEADING_2, card.title, props)

        if (card.description.isNotBlank()) {
            val headingBlockId = doc.cursor.blockId
            card.description.lines().filter { it.isNotBlank() }.forEach { line ->
                val text = line.removePrefix("- ").removePrefix("* ")
                doc = ForgeDoc.appendBlock(doc, headingBlockId, ForgeBlockKind.BULLET, text)
                // Move cursor back to page level for next heading
                doc = doc.copy(cursor = doc.cursor.copy(blockId = pageId))
            }
        }
    }

    return doc
}
