package borg.trikeshed.forge.notion

import borg.trikeshed.forge.ForgeFile
import borg.trikeshed.forge.ForgeFileId
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * First cut of the CursorDriven Notion clone core.
 *
 * The model is intentionally small and algebraic: the document is a block store,
 * a cursor is the only editing address, and all edits are pure state transitions
 * from a command + active cursor into a new immutable state.
 */
object CursorDrivenNotion {

    fun empty(title: String = "Untitled", actorId: String = "local"): CursorNotionState {
        val page = NotionBlock(
            id = NotionBlockId.generate(),
            kind = NotionBlockKind.PAGE,
            text = title,
            parentId = null,
        )
        val firstParagraph = NotionBlock(
            id = NotionBlockId.generate(),
            kind = NotionBlockKind.TEXT,
            text = "",
            parentId = page.id,
        )
        val rootedPage = page.copy(children = listOf(firstParagraph.id))
        val cursor = NotionCursor(
            pageId = rootedPage.id,
            blockId = firstParagraph.id,
            textOffset = 0,
        )
        return CursorNotionState(
            rootPageId = rootedPage.id,
            blocks = mapOf(
                rootedPage.id.value to rootedPage,
                firstParagraph.id.value to firstParagraph,
            ),
            cursors = mapOf(actorId to cursor),
            history = listOf(
                NotionMutation(
                    actorId = actorId,
                    operation = "create-page",
                    blockId = rootedPage.id,
                    payload = mapOf("title" to title),
                )
            )
        )
    }

    fun dispatch(
        state: CursorNotionState,
        command: NotionCommand,
        actorId: String = "local",
    ): CursorNotionState = when (command) {
        is NotionCommand.AppendBlock -> appendBlock(
            state = state,
            parentId = command.parentId ?: state.cursor(actorId).blockId,
            kind = command.kind,
            text = command.text,
            properties = command.properties,
            actorId = actorId,
        )
        is NotionCommand.InsertBlockAfter -> insertBlockAfter(
            state = state,
            afterBlockId = command.afterBlockId ?: state.cursor(actorId).blockId,
            kind = command.kind,
            text = command.text,
            properties = command.properties,
            actorId = actorId,
        )
        is NotionCommand.UpdateFocusedText -> updateFocusedText(
            state = state,
            text = command.text,
            actorId = actorId,
        )
        is NotionCommand.SetFocusedProperty -> setFocusedProperty(
            state = state,
            key = command.key,
            value = command.value,
            actorId = actorId,
        )
        is NotionCommand.ToggleFocusedTodo -> toggleFocusedTodo(state, actorId)
        is NotionCommand.Move -> moveCursor(state, command.direction, actorId)
        is NotionCommand.DeleteFocused -> deleteFocused(state, actorId)
        is NotionCommand.IndentFocused -> indentFocused(state, actorId)
        is NotionCommand.OutdentFocused -> outdentFocused(state, actorId)
    }

    fun appendBlock(
        state: CursorNotionState,
        parentId: NotionBlockId,
        kind: NotionBlockKind,
        text: String,
        properties: Map<String, String> = emptyMap(),
        actorId: String = "local",
    ): CursorNotionState {
        val parent = state.requireBlock(parentId)
        val block = NotionBlock(
            id = NotionBlockId.generate(),
            kind = kind,
            text = text,
            parentId = parent.id,
            properties = properties,
        )
        val updatedParent = parent.copy(children = parent.children + block.id)
        return state
            .replace(updatedParent)
            .replace(block)
            .withCursor(actorId, state.cursor(actorId).copy(blockId = block.id, textOffset = text.length))
            .record(actorId, "append-block", block.id, mapOf("parentId" to parent.id.value, "kind" to kind.name))
    }

    fun insertBlockAfter(
        state: CursorNotionState,
        afterBlockId: NotionBlockId,
        kind: NotionBlockKind,
        text: String,
        properties: Map<String, String> = emptyMap(),
        actorId: String = "local",
    ): CursorNotionState {
        val target = state.requireBlock(afterBlockId)
        val parentId = target.parentId ?: target.id
        val parent = state.requireBlock(parentId)
        val block = NotionBlock(
            id = NotionBlockId.generate(),
            kind = kind,
            text = text,
            parentId = parent.id,
            properties = properties,
        )
        val insertAt = parent.children.indexOf(afterBlockId).let { index ->
            if (index < 0) parent.children.size else index + 1
        }
        val updatedChildren = parent.children.toMutableList().also { it.add(insertAt, block.id) }
        return state
            .replace(parent.copy(children = updatedChildren))
            .replace(block)
            .withCursor(actorId, state.cursor(actorId).copy(blockId = block.id, textOffset = text.length))
            .record(actorId, "insert-block-after", block.id, mapOf("afterBlockId" to afterBlockId.value, "kind" to kind.name))
    }

    fun updateFocusedText(
        state: CursorNotionState,
        text: String,
        actorId: String = "local",
    ): CursorNotionState {
        val cursor = state.cursor(actorId)
        val block = state.requireBlock(cursor.blockId)
        return state
            .replace(block.copy(text = text, updatedAt = now()))
            .withCursor(actorId, cursor.copy(textOffset = text.length))
            .record(actorId, "update-text", block.id, mapOf("length" to text.length.toString()))
    }

    fun setFocusedProperty(
        state: CursorNotionState,
        key: String,
        value: String,
        actorId: String = "local",
    ): CursorNotionState {
        val cursor = state.cursor(actorId)
        val block = state.requireBlock(cursor.blockId)
        return state
            .replace(block.copy(properties = block.properties + (key to value), updatedAt = now()))
            .record(actorId, "set-property", block.id, mapOf(key to value))
    }

    fun toggleFocusedTodo(state: CursorNotionState, actorId: String = "local"): CursorNotionState {
        val cursor = state.cursor(actorId)
        val block = state.requireBlock(cursor.blockId)
        val checked = block.properties["checked"]?.toBooleanStrictOrNull() ?: false
        val todoBlock = block.copy(
            kind = NotionBlockKind.TODO,
            properties = block.properties + ("checked" to (!checked).toString()),
            updatedAt = now(),
        )
        return state
            .replace(todoBlock)
            .record(actorId, "toggle-todo", block.id, mapOf("checked" to (!checked).toString()))
    }

    fun moveCursor(
        state: CursorNotionState,
        direction: NotionCursorMove,
        actorId: String = "local",
    ): CursorNotionState {
        val cursor = state.cursor(actorId)
        val rows = cursorRows(state, cursor.pageId).toList()
        val currentIndex = rows.indexOfFirst { it.blockId == cursor.blockId }
        val nextBlockId = when (direction) {
            NotionCursorMove.NEXT -> rows.getOrNull(currentIndex + 1)?.blockId
            NotionCursorMove.PREVIOUS -> rows.getOrNull(currentIndex - 1)?.blockId
            NotionCursorMove.PARENT -> state.block(cursor.blockId)?.parentId
            NotionCursorMove.FIRST_CHILD -> state.block(cursor.blockId)?.children?.firstOrNull()
            NotionCursorMove.LAST_CHILD -> state.block(cursor.blockId)?.children?.lastOrNull()
        } ?: return state
        val target = state.requireBlock(nextBlockId)
        return state
            .withCursor(actorId, cursor.copy(blockId = target.id, textOffset = target.text.length))
            .record(actorId, "move-cursor", target.id, mapOf("direction" to direction.name))
    }

    fun deleteFocused(state: CursorNotionState, actorId: String = "local"): CursorNotionState {
        val cursor = state.cursor(actorId)
        if (cursor.blockId == cursor.pageId) return state
        val focused = state.requireBlock(cursor.blockId)
        val parent = focused.parentId?.let { state.block(it) } ?: return state
        val focusIndex = parent.children.indexOf(focused.id)
        val newFocus = parent.children.getOrNull(focusIndex - 1) ?: parent.id
        val deletedIds = collectSubtreeIds(state, focused.id).map { it.value }.toSet()
        val updatedParent = parent.copy(children = parent.children.filterNot { it == focused.id }, updatedAt = now())
        return state.copy(blocks = state.blocks - deletedIds)
            .replace(updatedParent)
            .withCursor(actorId, cursor.copy(blockId = newFocus, textOffset = state.block(newFocus)?.text?.length ?: 0))
            .record(actorId, "delete-block", focused.id, mapOf("deletedCount" to deletedIds.size.toString()))
    }

    fun indentFocused(state: CursorNotionState, actorId: String = "local"): CursorNotionState {
        val cursor = state.cursor(actorId)
        val focused = state.requireBlock(cursor.blockId)
        val parent = focused.parentId?.let { state.block(it) } ?: return state
        val index = parent.children.indexOf(focused.id)
        if (index <= 0) return state
        val newParentId = parent.children[index - 1]
        val newParent = state.requireBlock(newParentId)
        val updatedOldParent = parent.copy(children = parent.children.filterNot { it == focused.id }, updatedAt = now())
        val updatedNewParent = newParent.copy(children = newParent.children + focused.id, updatedAt = now())
        val updatedFocused = focused.copy(parentId = updatedNewParent.id, updatedAt = now())
        return state
            .replace(updatedOldParent)
            .replace(updatedNewParent)
            .replace(updatedFocused)
            .record(actorId, "indent-block", focused.id, mapOf("parentId" to updatedNewParent.id.value))
    }

    fun outdentFocused(state: CursorNotionState, actorId: String = "local"): CursorNotionState {
        val cursor = state.cursor(actorId)
        val focused = state.requireBlock(cursor.blockId)
        val parent = focused.parentId?.let { state.block(it) } ?: return state
        val grandparent = parent.parentId?.let { state.block(it) } ?: return state
        val parentIndex = grandparent.children.indexOf(parent.id)
        if (parentIndex < 0) return state
        val updatedParent = parent.copy(children = parent.children.filterNot { it == focused.id }, updatedAt = now())
        val updatedGrandChildren = grandparent.children.toMutableList().also { it.add(parentIndex + 1, focused.id) }
        val updatedGrandparent = grandparent.copy(children = updatedGrandChildren, updatedAt = now())
        val updatedFocused = focused.copy(parentId = grandparent.id, updatedAt = now())
        return state
            .replace(updatedParent)
            .replace(updatedGrandparent)
            .replace(updatedFocused)
            .record(actorId, "outdent-block", focused.id, mapOf("parentId" to updatedGrandparent.id.value))
    }

    fun cursorRows(state: CursorNotionState, pageId: NotionBlockId = state.rootPageId): NotionCursorView {
        val rows = mutableListOf<NotionCursorRow>()
        fun visit(blockId: NotionBlockId, depth: Int, path: List<NotionBlockId>) {
            val block = state.block(blockId) ?: return
            rows += NotionCursorRow(
                ordinal = rows.size,
                depth = depth,
                blockId = block.id,
                parentId = block.parentId,
                kind = block.kind,
                text = block.text,
                path = path + block.id,
                properties = block.properties,
            )
            block.children.forEach { child -> visit(child, depth + 1, path + block.id) }
        }
        visit(pageId, 0, emptyList())
        return NotionCursorView(rows)
    }

    fun renderMarkdown(state: CursorNotionState, pageId: NotionBlockId = state.rootPageId): String {
        val page = state.requireBlock(pageId)
        val lines = mutableListOf<String>()
        lines += "# ${page.text.ifBlank { "Untitled" }}"
        page.children.forEach { childId -> renderBlock(state, childId, 0, lines) }
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    fun asForgeMarkdownFile(
        state: CursorNotionState,
        pageId: NotionBlockId = state.rootPageId,
        path: String? = null,
    ): ForgeFile {
        val page = state.requireBlock(pageId)
        val filePath = path ?: "notion/${slug(page.text.ifBlank { "untitled" })}.md"
        return ForgeFile(
            id = ForgeFileId.fromPath(filePath),
            path = filePath,
            content = renderMarkdown(state, pageId),
            mimeType = "text/markdown",
            metadata = mapOf(
                "forge.kind" to "cursor-driven-notion-page",
                "notion.pageId" to pageId.value,
            )
        )
    }

    private fun renderBlock(
        state: CursorNotionState,
        blockId: NotionBlockId,
        depth: Int,
        lines: MutableList<String>,
    ) {
        val block = state.block(blockId) ?: return
        val indent = "  ".repeat(depth)
        when (block.kind) {
            NotionBlockKind.PAGE -> {
                if (block.text.isNotBlank()) lines += "${indent}# ${block.text}"
            }
            NotionBlockKind.TEXT -> lines += "$indent${block.text}"
            NotionBlockKind.HEADING_1 -> lines += "${indent}# ${block.text}"
            NotionBlockKind.HEADING_2 -> lines += "${indent}## ${block.text}"
            NotionBlockKind.HEADING_3 -> lines += "${indent}### ${block.text}"
            NotionBlockKind.BULLET -> lines += "$indent- ${block.text}"
            NotionBlockKind.NUMBERED -> lines += "${indent}1. ${block.text}"
            NotionBlockKind.TODO -> {
                val checked = if (block.properties["checked"] == "true") "x" else " "
                lines += "$indent- [$checked] ${block.text}"
            }
            NotionBlockKind.QUOTE -> lines += "$indent> ${block.text}"
            NotionBlockKind.CODE -> {
                val language = block.properties["language"].orEmpty()
                lines += "$indent```$language"
                lines += block.text.lines().joinToString("\n") { "$indent$it" }
                lines += "$indent```"
            }
            NotionBlockKind.DIVIDER -> lines += "$indent---"
            NotionBlockKind.CALLOUT -> lines += "$indent> ${block.properties["icon"] ?: "💡"} ${block.text}"
            NotionBlockKind.DATABASE -> lines += "$indent| ${block.text.ifBlank { "Database" }} |"
            NotionBlockKind.DATABASE_ROW -> lines += "$indent- ${block.text} ${block.properties.entries.joinToString(" ") { "${it.key}=${it.value}" }}".trimEnd()
            NotionBlockKind.TABLE -> lines += "$indent| ${block.text.ifBlank { "Table" }} |"
            NotionBlockKind.TABLE_ROW -> lines += "$indent| ${block.text} |"
        }
        block.children.forEach { child -> renderBlock(state, child, depth + 1, lines) }
    }

    private fun collectSubtreeIds(state: CursorNotionState, root: NotionBlockId): List<NotionBlockId> {
        val block = state.block(root) ?: return emptyList()
        return listOf(root) + block.children.flatMap { collectSubtreeIds(state, it) }
    }

    private fun slug(text: String): String = text
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "untitled" }

    private fun now(): Long = System.currentTimeMillis()
}

@Serializable
@JvmInline
value class NotionBlockId(val value: String) {
    companion object {
        fun generate(): NotionBlockId = NotionBlockId("notion-block-${UUID.randomUUID()}")
    }
}

@Serializable
enum class NotionBlockKind {
    PAGE,
    TEXT,
    HEADING_1,
    HEADING_2,
    HEADING_3,
    BULLET,
    NUMBERED,
    TODO,
    QUOTE,
    CODE,
    DIVIDER,
    CALLOUT,
    DATABASE,
    DATABASE_ROW,
    TABLE,
    TABLE_ROW,
}

@Serializable
data class NotionBlock(
    val id: NotionBlockId,
    val kind: NotionBlockKind,
    val text: String,
    val parentId: NotionBlockId?,
    val children: List<NotionBlockId> = emptyList(),
    val properties: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class NotionCursor(
    val pageId: NotionBlockId,
    val blockId: NotionBlockId,
    val textOffset: Int = 0,
    val selectionAnchor: NotionBlockId? = null,
)

@Serializable
data class NotionMutation(
    val actorId: String,
    val operation: String,
    val blockId: NotionBlockId?,
    val payload: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class CursorNotionState(
    val rootPageId: NotionBlockId,
    val blocks: Map<String, NotionBlock>,
    val cursors: Map<String, NotionCursor> = emptyMap(),
    val history: List<NotionMutation> = emptyList(),
) {
    fun block(id: NotionBlockId): NotionBlock? = blocks[id.value]

    fun requireBlock(id: NotionBlockId): NotionBlock =
        block(id) ?: error("Missing Notion block: ${id.value}")

    fun cursor(actorId: String = "local"): NotionCursor =
        cursors[actorId] ?: NotionCursor(pageId = rootPageId, blockId = rootPageId, textOffset = 0)
}

sealed interface NotionCommand {
    data class AppendBlock(
        val parentId: NotionBlockId? = null,
        val kind: NotionBlockKind,
        val text: String,
        val properties: Map<String, String> = emptyMap(),
    ) : NotionCommand

    data class InsertBlockAfter(
        val afterBlockId: NotionBlockId? = null,
        val kind: NotionBlockKind,
        val text: String,
        val properties: Map<String, String> = emptyMap(),
    ) : NotionCommand

    data class UpdateFocusedText(val text: String) : NotionCommand
    data class SetFocusedProperty(val key: String, val value: String) : NotionCommand
    data object ToggleFocusedTodo : NotionCommand
    data class Move(val direction: NotionCursorMove) : NotionCommand
    data object DeleteFocused : NotionCommand
    data object IndentFocused : NotionCommand
    data object OutdentFocused : NotionCommand
}

enum class NotionCursorMove {
    NEXT,
    PREVIOUS,
    PARENT,
    FIRST_CHILD,
    LAST_CHILD,
}

data class NotionCursorRow(
    val ordinal: Int,
    val depth: Int,
    val blockId: NotionBlockId,
    val parentId: NotionBlockId?,
    val kind: NotionBlockKind,
    val text: String,
    val path: List<NotionBlockId>,
    val properties: Map<String, String>,
)

class NotionCursorView internal constructor(private val rows: List<NotionCursorRow>) {
    val size: Int get() = rows.size

    operator fun get(index: Int): NotionCursorRow = rows[index]

    fun toList(): List<NotionCursorRow> = rows.toList()

    fun indexOf(blockId: NotionBlockId): Int = rows.indexOfFirst { it.blockId == blockId }
}

private fun CursorNotionState.replace(block: NotionBlock): CursorNotionState =
    copy(blocks = blocks + (block.id.value to block.copy(updatedAt = System.currentTimeMillis())))

private fun CursorNotionState.withCursor(actorId: String, cursor: NotionCursor): CursorNotionState =
    copy(cursors = cursors + (actorId to cursor))

private fun CursorNotionState.record(
    actorId: String,
    operation: String,
    blockId: NotionBlockId?,
    payload: Map<String, String> = emptyMap(),
): CursorNotionState = copy(
    history = history + NotionMutation(
        actorId = actorId,
        operation = operation,
        blockId = blockId,
        payload = payload,
    )
)
