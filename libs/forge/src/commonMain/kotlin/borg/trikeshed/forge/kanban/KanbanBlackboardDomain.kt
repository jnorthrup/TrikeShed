
@file:Suppress("unused")

package borg.trikeshed.forge.kanban

import borg.trikeshed.forge.platform.platformUtils
import kotlinx.serialization.Serializable

/**
 * Kanban as a Blackboard domain -- minimal self-contained version.
 * Inlines the overlay types from trikeshed.cursor.BlackboardOverlay
 * so forge doesn't need a dependency on the trikeshed core modules.
 */

// --- Overlay types (inlined from trikeshed.cursor.BlackboardOverlay) ---

enum class OverlayRole {
    OBSERVATION, DERIVED, AGGREGATE, HYPOTHESIS,
    GROUND_TRUTH, CONTROL, METADATA, PROVENANCE
}

@Serializable
data class Provenance(
    val source: String,
    val timestamp: Long,
    val transformations: List<String> = emptyList(),
    val creator: String? = null,
) {
    fun withTransformation(step: String): Provenance = copy(transformations = transformations + step)
    fun derive(transformation: String): Provenance = copy(transformations = transformations + transformation)
}

@Serializable
data class Evidence(
    val confidence: Double = 1.0,
    val errorMargin: Double? = null,
    val supportCount: Int? = null,
    val notes: List<String> = emptyList(),
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be in [0.0, 1.0], got $confidence" }
        errorMargin?.let { require(it >= 0) { "Error margin must be non-negative, got $it" } }
        supportCount?.let { require(it >= 0) { "Support count must be non-negative, got $it" } }
    }
    fun combine(other: Evidence): Evidence {
        return copy(
            confidence = (confidence + other.confidence) / 2.0,
            supportCount = (supportCount ?: 0) + (other.supportCount ?: 0),
            notes = notes + other.notes,
        )
    }
}

@Serializable
sealed class DependencyHandle {
    data class CellRef(val row: Int, val column: Int) : DependencyHandle()
    data class ColumnRef(val column: Int) : DependencyHandle()
    data class ExternalCellRef(val cursorId: String, val row: Int, val column: Int) : DependencyHandle()
    data class ExternalResource(val uri: String, val selector: String? = null) : DependencyHandle()
    data class Composite(val handles: List<DependencyHandle>) : DependencyHandle()
}

@Serializable
data class CellOverlay<T>(
    val value: T,
    val role: OverlayRole = OverlayRole.OBSERVATION,
    val provenance: Provenance? = null,
    val evidence: Evidence? = null,
    val dependencies: List<DependencyHandle> = emptyList(),
) {
    fun <R> map(transform: (T) -> R): CellOverlay<R> = CellOverlay(
        value = transform(value), role = role, provenance = provenance,
        evidence = evidence, dependencies = dependencies,
    )
    fun derive(newRole: OverlayRole = OverlayRole.DERIVED, transformation: String? = null): CellOverlay<T> {
        val newProvenance = provenance?.let {
            var p = it
            transformation?.let { p = p.derive("role:${role.name}"); p = p.derive(it) }
            p
        }
        return copy(role = newRole, provenance = newProvenance)
    }
    fun withConfidence(confidence: Double): CellOverlay<T> {
        val newEvidence = evidence?.copy(confidence = confidence) ?: Evidence(confidence = confidence)
        return copy(evidence = newEvidence)
    }
    fun withDependency(handle: DependencyHandle): CellOverlay<T> = copy(dependencies = dependencies + handle)
}

@Serializable
data class ColumnOverlay(
    val name: String,
    val defaultRole: OverlayRole = OverlayRole.OBSERVATION,
    val provenance: Provenance? = null,
    val evidence: Evidence? = null,
    val constraints: List<String> = emptyList(),
    val description: String? = null,
) {
    fun <T> toCellOverlay(value: T, row: Int): CellOverlay<T> = CellOverlay(
        value = value, role = defaultRole, provenance = provenance, evidence = evidence
    )
    fun withConstraint(constraint: String): ColumnOverlay = copy(constraints = constraints + constraint)
    fun withDescription(desc: String): ColumnOverlay = copy(description = desc)
}

@Serializable
data class BlackboardContext(
    val id: String,
    val columnOverlays: Map<Int, ColumnOverlay> = emptyMap(),
    val provenance: Provenance? = null,
    val tags: Map<String, String> = emptyMap(),
) {
    fun getColumnOverlay(columnIndex: Int): ColumnOverlay? = columnOverlays[columnIndex]
    fun getEffectiveRole(columnIndex: Int, cellRole: OverlayRole?): OverlayRole =
        cellRole ?: columnOverlays[columnIndex]?.defaultRole ?: OverlayRole.OBSERVATION
    fun getEffectiveEvidence(columnIndex: Int, cellEvidence: Evidence?): Evidence? =
        cellEvidence ?: columnOverlays[columnIndex]?.evidence
    fun withColumnOverlay(index: Int, overlay: ColumnOverlay): BlackboardContext =
        copy(columnOverlays = columnOverlays + (index to overlay))
    fun withTag(key: String, value: String): BlackboardContext = copy(tags = tags + (key to value))
}

fun provenance(
    source: String,
    timestamp: Long = platformUtils.currentTimeMillis(),
    transformations: List<String> = emptyList(),
    creator: String? = null,
): Provenance = Provenance(source, timestamp, transformations, creator)

fun evidence(
    confidence: Double = 1.0,
    errorMargin: Double? = null,
    supportCount: Int? = null,
    notes: List<String> = emptyList(),
): Evidence = Evidence(confidence, errorMargin, supportCount, notes)

fun <T> cellOverlay(
    value: T,
    role: OverlayRole = OverlayRole.OBSERVATION,
    provenance: Provenance? = null,
    evidence: Evidence? = null,
    dependencies: List<DependencyHandle> = emptyList(),
): CellOverlay<T> = CellOverlay(value, role, provenance, evidence, dependencies)

fun columnOverlay(
    name: String,
    defaultRole: OverlayRole = OverlayRole.OBSERVATION,
    provenance: Provenance? = null,
    evidence: Evidence? = null,
    constraints: List<String> = emptyList(),
    description: String? = null,
): ColumnOverlay = ColumnOverlay(name, defaultRole, provenance, evidence, constraints, description)

fun blackboardContext(
    id: String,
    columnOverlays: Map<Int, ColumnOverlay> = emptyMap(),
    provenance: Provenance? = null,
    tags: Map<String, String> = emptyMap(),
): BlackboardContext = BlackboardContext(id, columnOverlays, provenance, tags)

fun cellRef(row: Int, column: Int): DependencyHandle.CellRef = DependencyHandle.CellRef(row, column)
fun columnRef(column: Int): DependencyHandle.ColumnRef = DependencyHandle.ColumnRef(column)
fun externalResource(uri: String, selector: String? = null): DependencyHandle.ExternalResource =
    DependencyHandle.ExternalResource(uri, selector)
fun compositeDependency(vararg handles: DependencyHandle): DependencyHandle.Composite =
    DependencyHandle.Composite(handles.toList())

// --- Kanban domain taxonomy ---

object KanbanBlackboardDomain {
    object Cols {
        const val ID = 0
        const val TITLE = 1
        const val COLUMN = 2
        const val ASSIGNEE = 3
        const val PRIORITY = 4
        const val DEPS = 5
        const val EVIDENCE = 6
        const val PROVENANCE = 7
    }

    val columnSchema: Map<Int, ColumnOverlay> = mapOf(
        Cols.ID to columnOverlay(name = "id", defaultRole = OverlayRole.CONTROL, description = "Card identity (stable handle)"),
        Cols.TITLE to columnOverlay(name = "title", defaultRole = OverlayRole.OBSERVATION, description = "Human-readable card title"),
        Cols.COLUMN to columnOverlay(name = "column", defaultRole = OverlayRole.CONTROL, constraints = listOf("IntEnum: TODO=0, DOING=1, DONE=2, BLOCKED=3"), description = "Board column as integer enum"),
        Cols.ASSIGNEE to columnOverlay(name = "assignee", defaultRole = OverlayRole.METADATA, description = "Agent or profile assigned"),
        Cols.PRIORITY to columnOverlay(name = "priority", defaultRole = OverlayRole.CONTROL, description = "Dispatch priority weight"),
        Cols.DEPS to columnOverlay(name = "deps", defaultRole = OverlayRole.PROVENANCE, description = "Blocking dependencies"),
        Cols.EVIDENCE to columnOverlay(name = "evidence", defaultRole = OverlayRole.HYPOTHESIS, description = "Confidence metrics from runs"),
        Cols.PROVENANCE to columnOverlay(name = "provenance", defaultRole = OverlayRole.PROVENANCE, description = "Audit trail"),
    )

    fun cardToContext(card: BoardCard): BlackboardContext {
        val now = platformUtils.currentTimeMillis()
        return blackboardContext(
            id = "kanban:${card.id}",
            columnOverlays = columnSchema,
            provenance = provenance(source = "kanban:${card.column.name.lowercase()}", timestamp = now, creator = card.assignee),
            tags = mapOf("domain" to "kanban", "column" to card.column.name.lowercase(), "logHandle" to card.logHandle()),
        )
    }

    fun cardToRow(card: BoardCard): List<CellOverlay<*>> {
        val now = platformUtils.currentTimeMillis()
        val prov = provenance(source = "kanban:${card.id}", timestamp = now)
        val ev = when (card.column) {
            BoardColumn.DONE -> evidence(confidence = 1.0, supportCount = 1)
            BoardColumn.BLOCKED -> evidence(confidence = 0.0, supportCount = 0)
            else -> null
        }
        return listOf(
            CellOverlay(card.id, OverlayRole.CONTROL, prov),
            CellOverlay(card.title, OverlayRole.OBSERVATION, prov),
            CellOverlay(card.column.ordinalValue, OverlayRole.CONTROL, prov, ),
            CellOverlay(card.assignee ?: "", OverlayRole.METADATA, prov),
            CellOverlay(card.priority, OverlayRole.CONTROL, prov),
            CellOverlay(card.dependencies, OverlayRole.PROVENANCE, prov),
            CellOverlay(ev, OverlayRole.HYPOTHESIS, prov),
            CellOverlay(card.tags, OverlayRole.PROVENANCE, prov),
        )
    }

    fun boardToContexts(cards: List<BoardCard>): List<BlackboardContext> = cards.α { cardToContext(it) }
    fun boardToRows(cards: List<BoardCard>): List<List<CellOverlay<*>>> = cards.α { cardToRow(it) }

    fun dependencyHandles(cards: List<BoardCard>): List<Join<String, String>> {
        val idIndex = cards.α { it.id }.mapIndexed { i, id -> id to i }.toMap()
        val handles = mutableListOf<Join<String, String>>()
        cards.view.forEach { card ->
            card.dependencies.view.forEach { dep ->
                if (idIndex.contains(dep)) handles.add(dep to card.id)
            }
        }
        return handles
    }
}

interface BlackboardDomain {
    val domainId: String
    val columnSchema: Map<Int, ColumnOverlay>
    fun toContext(card: BoardCard): BlackboardContext
    fun toRow(card: BoardCard): List<CellOverlay<*>>
}

object KanbanDomain : BlackboardDomain {
    override val domainId = "kanban"
    override val columnSchema = KanbanBlackboardDomain.columnSchema
    override fun toContext(card: BoardCard) = KanbanBlackboardDomain.cardToContext(card)
    override fun toRow(card: BoardCard) = KanbanBlackboardDomain.cardToRow(card)
}
