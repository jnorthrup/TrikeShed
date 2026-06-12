package borg.trikeshed.forge.notion

import kotlinx.serialization.Serializable

/**
 * Database/table vertical slice for the CursorDriven Notion clone.
 *
 * A Notion database is represented as a DATABASE block. Its rows are child
 * DATABASE_ROW blocks, and each cell is stored on the row as `cell.<fieldId>`.
 * This keeps database views cursor-native: querying a table is just projecting
 * visible DATABASE_ROW blocks into a stable indexed cursor view.
 */
object CursorDrivenNotionDatabases {

    fun field(
        name: String,
        type: NotionFieldType = NotionFieldType.TEXT,
        options: List<String> = emptyList(),
        id: NotionDatabaseFieldId = NotionDatabaseFieldId.fromName(name),
    ): NotionDatabaseField = NotionDatabaseField(id, name, type, options)

    fun createDatabase(
        state: CursorNotionState,
        parentId: NotionBlockId = state.rootPageId,
        name: String,
        fields: List<NotionDatabaseField>,
        actorId: String = "local",
    ): NotionDatabaseMutationResult {
        require(fields.isNotEmpty()) { "A Notion database needs at least one field" }
        val withDatabase = CursorDrivenNotion.appendBlock(
            state = state,
            parentId = parentId,
            kind = NotionBlockKind.DATABASE,
            text = name,
            properties = encodeSchema(fields),
            actorId = actorId,
        )
        val databaseId = withDatabase.cursor(actorId).blockId
        return NotionDatabaseMutationResult(withDatabase, databaseId)
    }

    fun addRow(
        state: CursorNotionState,
        databaseId: NotionBlockId,
        title: String,
        cells: Map<NotionDatabaseFieldId, String>,
        actorId: String = "local",
    ): NotionDatabaseMutationResult {
        val database = state.requireBlock(databaseId)
        require(database.kind == NotionBlockKind.DATABASE) { "Block ${databaseId.value} is not a DATABASE" }
        val schema = schemaOf(state, databaseId)
        val knownFieldIds = schema.fields.map { it.id }.toSet()
        val unknown = cells.keys - knownFieldIds
        require(unknown.isEmpty()) { "Unknown database fields: ${unknown.joinToString { it.value }}" }
        val withRow = CursorDrivenNotion.appendBlock(
            state = state,
            parentId = databaseId,
            kind = NotionBlockKind.DATABASE_ROW,
            text = title,
            properties = cells.mapKeys { (fieldId, _) -> cellKey(fieldId) },
            actorId = actorId,
        )
        return NotionDatabaseMutationResult(withRow, withRow.cursor(actorId).blockId)
    }

    fun updateCell(
        state: CursorNotionState,
        rowId: NotionBlockId,
        fieldId: NotionDatabaseFieldId,
        value: String,
        actorId: String = "local",
    ): CursorNotionState {
        val row = state.requireBlock(rowId)
        require(row.kind == NotionBlockKind.DATABASE_ROW) { "Block ${rowId.value} is not a DATABASE_ROW" }
        val updated = row.copy(properties = row.properties + (cellKey(fieldId) to value), updatedAt = now())
        return state
            .replaceDatabaseBlock(updated)
            .recordDatabaseMutation(actorId, "update-database-cell", row.id, mapOf("fieldId" to fieldId.value))
    }

    fun schemaOf(state: CursorNotionState, databaseId: NotionBlockId): NotionDatabaseSchema {
        val database = state.requireBlock(databaseId)
        require(database.kind == NotionBlockKind.DATABASE) { "Block ${databaseId.value} is not a DATABASE" }
        val fieldIds = database.properties[FIELD_ORDER]
            ?.split(',')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?: emptyList()
        val fields = fieldIds.map { rawId ->
            val id = NotionDatabaseFieldId(rawId)
            NotionDatabaseField(
                id = id,
                name = database.properties[fieldNameKey(id)] ?: rawId,
                type = database.properties[fieldTypeKey(id)]?.let(NotionFieldType::valueOf) ?: NotionFieldType.TEXT,
                options = database.properties[fieldOptionsKey(id)]?.split('|')?.filter { it.isNotBlank() } ?: emptyList(),
            )
        }
        return NotionDatabaseSchema(database.id, database.text, fields)
    }

    fun rows(
        state: CursorNotionState,
        databaseId: NotionBlockId,
    ): NotionDatabaseCursorView = query(state, databaseId, NotionDatabaseQuery())

    fun query(
        state: CursorNotionState,
        databaseId: NotionBlockId,
        query: NotionDatabaseQuery,
    ): NotionDatabaseCursorView {
        val database = state.requireBlock(databaseId)
        require(database.kind == NotionBlockKind.DATABASE) { "Block ${databaseId.value} is not a DATABASE" }
        val schema = schemaOf(state, databaseId)
        val projected = database.children
            .mapNotNull { childId -> state.block(childId) }
            .filter { it.kind == NotionBlockKind.DATABASE_ROW }
            .mapIndexed { index, block -> toRow(index, block, schema) }
            .filter { row -> query.filters.all { filter -> matches(row, filter) } }
            .let { rows -> applySorts(rows, query.sorts, schema) }
            .mapIndexed { index, row -> row.copy(ordinal = index) }
        return NotionDatabaseCursorView(projected)
    }

    fun renderMarkdownTable(
        state: CursorNotionState,
        databaseId: NotionBlockId,
        query: NotionDatabaseQuery = NotionDatabaseQuery(),
    ): String {
        val schema = schemaOf(state, databaseId)
        val rows = query(state, databaseId, query).toList()
        val headers = listOf("Name") + schema.fields.map { it.name }
        val separator = headers.map { "---" }
        val body = rows.map { row ->
            listOf(row.title) + schema.fields.map { field -> row.cells[field.id].orEmpty() }
        }
        return (listOf(headers, separator) + body)
            .joinToString("\n") { cells -> cells.joinToString(prefix = "| ", separator = " | ", postfix = " |") { escapePipe(it) } }
            .plus("\n")
    }

    private fun toRow(
        ordinal: Int,
        block: NotionBlock,
        schema: NotionDatabaseSchema,
    ): NotionDatabaseRow = NotionDatabaseRow(
        ordinal = ordinal,
        blockId = block.id,
        title = block.text,
        cells = schema.fields.associate { field -> field.id to block.properties[cellKey(field.id)].orEmpty() },
    )

    private fun matches(row: NotionDatabaseRow, filter: NotionDatabaseFilter): Boolean {
        val value = row.cells[filter.fieldId].orEmpty()
        return when (filter.op) {
            NotionFilterOp.EQUALS -> value == filter.value
            NotionFilterOp.NOT_EQUALS -> value != filter.value
            NotionFilterOp.CONTAINS -> value.contains(filter.value, ignoreCase = true)
            NotionFilterOp.NOT_EMPTY -> value.isNotBlank()
            NotionFilterOp.CHECKED -> value.toBooleanStrictOrNull() == true
            NotionFilterOp.GREATER_THAN -> value.toDoubleOrNull()?.let { it > (filter.value.toDoubleOrNull() ?: Double.NaN) } ?: false
            NotionFilterOp.LESS_THAN -> value.toDoubleOrNull()?.let { it < (filter.value.toDoubleOrNull() ?: Double.NaN) } ?: false
        }
    }

    private fun applySorts(
        rows: List<NotionDatabaseRow>,
        sorts: List<NotionDatabaseSort>,
        schema: NotionDatabaseSchema,
    ): List<NotionDatabaseRow> {
        var sorted = rows
        for (sort in sorts.asReversed()) {
            val field = schema.fields.firstOrNull { it.id == sort.fieldId }
            val comparator = compareBy<NotionDatabaseRow> { row -> comparableValue(row.cells[sort.fieldId].orEmpty(), field?.type) }
            sorted = if (sort.ascending) sorted.sortedWith(comparator) else sorted.sortedWith(comparator.reversed())
        }
        return sorted
    }

    private fun comparableValue(value: String, type: NotionFieldType?): Comparable<*> = when (type) {
        NotionFieldType.NUMBER -> value.toDoubleOrNull() ?: Double.NaN
        NotionFieldType.CHECKBOX -> value.toBooleanStrictOrNull() ?: false
        else -> value.lowercase()
    }

    private fun encodeSchema(fields: List<NotionDatabaseField>): Map<String, String> = buildMap {
        put(FIELD_ORDER, fields.joinToString(",") { it.id.value })
        fields.forEach { field ->
            put(fieldNameKey(field.id), field.name)
            put(fieldTypeKey(field.id), field.type.name)
            if (field.options.isNotEmpty()) put(fieldOptionsKey(field.id), field.options.joinToString("|"))
        }
    }

    private fun escapePipe(value: String): String = value.replace("|", "\\|")

    private fun cellKey(id: NotionDatabaseFieldId): String = "cell.${id.value}"
    private fun fieldNameKey(id: NotionDatabaseFieldId): String = "field.${id.value}.name"
    private fun fieldTypeKey(id: NotionDatabaseFieldId): String = "field.${id.value}.type"
    private fun fieldOptionsKey(id: NotionDatabaseFieldId): String = "field.${id.value}.options"
    private fun now(): Long = System.currentTimeMillis()

    private const val FIELD_ORDER = "database.fieldOrder"
}

@Serializable
@JvmInline
value class NotionDatabaseFieldId(val value: String) {
    companion object {
        fun fromName(name: String): NotionDatabaseFieldId = NotionDatabaseFieldId(
            name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "field" }
        )
    }
}

@Serializable
enum class NotionFieldType {
    TEXT,
    NUMBER,
    CHECKBOX,
    SELECT,
    MULTI_SELECT,
    DATE,
    PERSON,
    URL,
    STATUS,
}

@Serializable
data class NotionDatabaseField(
    val id: NotionDatabaseFieldId,
    val name: String,
    val type: NotionFieldType,
    val options: List<String> = emptyList(),
)

@Serializable
data class NotionDatabaseSchema(
    val databaseId: NotionBlockId,
    val name: String,
    val fields: List<NotionDatabaseField>,
)

data class NotionDatabaseMutationResult(
    val state: CursorNotionState,
    val blockId: NotionBlockId,
)

data class NotionDatabaseRow(
    val ordinal: Int,
    val blockId: NotionBlockId,
    val title: String,
    val cells: Map<NotionDatabaseFieldId, String>,
)

data class NotionDatabaseQuery(
    val filters: List<NotionDatabaseFilter> = emptyList(),
    val sorts: List<NotionDatabaseSort> = emptyList(),
)

data class NotionDatabaseFilter(
    val fieldId: NotionDatabaseFieldId,
    val op: NotionFilterOp,
    val value: String = "",
)

data class NotionDatabaseSort(
    val fieldId: NotionDatabaseFieldId,
    val ascending: Boolean = true,
)

enum class NotionFilterOp {
    EQUALS,
    NOT_EQUALS,
    CONTAINS,
    NOT_EMPTY,
    CHECKED,
    GREATER_THAN,
    LESS_THAN,
}

class NotionDatabaseCursorView internal constructor(private val rows: List<NotionDatabaseRow>) {
    val size: Int get() = rows.size

    operator fun get(index: Int): NotionDatabaseRow = rows[index]

    fun toList(): List<NotionDatabaseRow> = rows.toList()
}

private fun CursorNotionState.replaceDatabaseBlock(block: NotionBlock): CursorNotionState =
    copy(blocks = blocks + (block.id.value to block))

private fun CursorNotionState.recordDatabaseMutation(
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
