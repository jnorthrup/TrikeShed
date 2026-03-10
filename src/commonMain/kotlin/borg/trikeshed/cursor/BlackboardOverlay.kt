package borg.trikeshed.cursor

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series

/**
 * Role enumeration for blackboard overlay cells.
 * Describes the epistemic or operational role of a value in the blackboard.
 */
enum class OverlayRole {
    /** Raw input data, unprocessed */
    OBSERVATION,

    /** Derived or computed value */
    DERIVED,

    /** Aggregated or reduced value */
    AGGREGATE,

    /** Hypothesis or prediction */
    HYPOTHESIS,

    /** Ground truth or reference value */
    GROUND_TRUTH,

    /** Control or configuration parameter */
    CONTROL,

    /** Metadata about other values */
    METADATA,

    /** Provenance or audit trail entry */
    PROVENANCE
}

/**
 * Provenance record for blackboard overlay.
 * Tracks the origin and transformation history of a value.
 */
data class Provenance(
    /** Source identifier (e.g., dataset name, file path, stream ID) */
    val source: String,

    /** Timestamp of origin (epoch milliseconds) */
    val timestamp: Long,

    /** Transformation chain (ordered list of operation descriptions) */
    val transformations: List<String> = emptyList(),

    /** Optional creator/owner identifier */
    val creator: String? = null
) {
    /**
     * Append a transformation step to the provenance chain.
     */
    fun withTransformation(step: String): Provenance =
        copy(transformations = transformations + step)

    /**
     * Create a derived provenance from this one with an additional transformation.
     */
    fun derive(transformation: String): Provenance =
        copy(transformations = transformations + transformation)
}

/**
 * Evidence record for blackboard overlay.
 * Captures confidence metrics and supporting evidence for a value.
 */
data class Evidence(
    /** Confidence score in range [0.0, 1.0] */
    val confidence: Double = 1.0,

    /** Optional error margin or uncertainty measure */
    val errorMargin: Double? = null,

    /** Sample size or support count */
    val supportCount: Int? = null,

    /** Optional evidence notes or annotations */
    val notes: List<String> = emptyList()
) {
    init {
        require(confidence in 0.0..1.0) {
            "confidence must be in range [0.0, 1.0], got $confidence"
        }
        errorMargin?.let { require(it >= 0) { "Error margin must be non-negative, got $it" } }
        supportCount?.let { require(it >= 0) { "Support count must be non-negative, got $it" } }
    }

    /**
     * Combine two evidence records by averaging confidence.
     */
    fun combine(other: Evidence): Evidence {
        val combinedConfidence = (confidence + other.confidence) / 2.0
        val combinedSupport = (supportCount ?: 0) + (other.supportCount ?: 0)
        return copy(
            confidence = combinedConfidence,
            supportCount = combinedSupport,
            notes = notes + other.notes
        )
    }
}

/**
 * Dependency handle for blackboard overlay.
 * References other cells, columns, or external resources that this value depends on.
 */
sealed class DependencyHandle {
    /** Reference to a cell within the same cursor */
    data class CellRef(val row: Int, val column: Int) : DependencyHandle()

    /** Reference to a column in the same cursor */
    data class ColumnRef(val column: Int) : DependencyHandle()

    /** Reference to a cell in another cursor */
    data class ExternalCellRef(val cursorId: String, val row: Int, val column: Int) : DependencyHandle()

    /** Reference to an external resource (e.g., file, database, stream) */
    data class ExternalResource(val uri: String, val selector: String? = null) : DependencyHandle()

    /** Composite dependency (multiple handles) */
    data class Composite(val handles: List<DependencyHandle>) : DependencyHandle()
}

/**
 * Blackboard overlay for a single cell value.
 * Wraps a value with epistemic and operational metadata.
 */
data class CellOverlay<T>(
    /** The underlying value */
    val value: T,

    /** Role of this cell in the blackboard */
    val role: OverlayRole = OverlayRole.OBSERVATION,

    /** Provenance information */
    val provenance: Provenance? = null,

    /** Evidence/confidence metrics */
    val evidence: Evidence? = null,

    /** Dependencies on other values */
    val dependencies: List<DependencyHandle> = emptyList()
) {
    /**
     * Map the value to a new type, preserving overlay metadata.
     */
    fun <R> map(transform: (T) -> R): CellOverlay<R> =
        CellOverlay(
            value = transform(value),
            role = role,
            provenance = provenance,
            evidence = evidence,
            dependencies = dependencies
        )

    /**
     * Create a derived overlay with updated role and provenance.
     */
    fun derive(
        newRole: OverlayRole = OverlayRole.DERIVED,
        transformation: String? = null
    ): CellOverlay<T> {
        val newProvenance = provenance?.let {
            var p = it
            if (transformation != null) {
                p = p.derive("role:${role.name}")
                p = p.derive(transformation)
            }
            p
        }
        return copy(role = newRole, provenance = newProvenance)
    }

    /**
     * Update confidence level.
     */
    fun withConfidence(confidence: Double): CellOverlay<T> {
        val newEvidence = evidence?.copy(confidence = confidence) ?: Evidence(confidence = confidence)
        return copy(evidence = newEvidence)
    }

    /**
     * Add a dependency.
     */
    fun withDependency(handle: DependencyHandle): CellOverlay<T> =
        copy(dependencies = dependencies + handle)
}

/**
 * Column-level overlay metadata.
 * Provides overlay information at the column level rather than per-cell.
 */
data class ColumnOverlay(
    /** Column name */
    val name: String,

    /** Default role for cells in this column */
    val defaultRole: OverlayRole = OverlayRole.OBSERVATION,

    /** Column-level provenance (applies to all cells unless overridden) */
    val provenance: Provenance? = null,

    /** Column-level evidence (applies to all cells unless overridden) */
    val evidence: Evidence? = null,

    /** Schema or type constraints */
    val constraints: List<String> = emptyList(),

    /** Semantic description or documentation */
    val description: String? = null
) {
    /**
     * Create a cell overlay from this column overlay for a specific value.
     */
    fun <T> toCellOverlay(value: T, row: Int): CellOverlay<T> =
        CellOverlay(
            value = value,
            role = defaultRole,
            provenance = provenance,
            evidence = evidence
        )

    /**
     * Add a constraint to the column.
     */
    fun withConstraint(constraint: String): ColumnOverlay =
        copy(constraints = constraints + constraint)

    /**
     * Update the description.
     */
    fun withDescription(desc: String): ColumnOverlay =
        copy(description = desc)
}

/**
 * Blackboard context for a cursor.
 * Provides a container for column overlays and cursor-level metadata.
 */
data class BlackboardContext(
    /** Unique identifier for this blackboard instance */
    val id: String,

    /** Column overlays indexed by column index */
    val columnOverlays: Map<Int, ColumnOverlay> = emptyMap(),

    /** Cursor-level provenance */
    val provenance: Provenance? = null,

    /** Cursor-level metadata tags */
    val tags: Map<String, String> = emptyMap()
) {
    /**
     * Get the overlay for a specific column.
     */
    fun getColumnOverlay(columnIndex: Int): ColumnOverlay? = columnOverlays[columnIndex]

    /**
     * Get the effective role for a cell (column default or cell-specific).
     */
    fun getEffectiveRole(columnIndex: Int, cellRole: OverlayRole?): OverlayRole =
        cellRole ?: columnOverlays[columnIndex]?.defaultRole ?: OverlayRole.OBSERVATION

    /**
     * Get the effective evidence for a cell (cell overrides column).
     */
    fun getEffectiveEvidence(columnIndex: Int, cellEvidence: Evidence?): Evidence? =
        cellEvidence ?: columnOverlays[columnIndex]?.evidence

    /**
     * Add or update a column overlay.
     */
    fun withColumnOverlay(index: Int, overlay: ColumnOverlay): BlackboardContext =
        copy(columnOverlays = columnOverlays + (index to overlay))

    /**
     * Add a tag to the cursor.
     */
    fun withTag(key: String, value: String): BlackboardContext =
        copy(tags = tags + (key to value))
}

/**
 * Helper to create a provenance record.
 */
fun provenance(
    source: String,
    timestamp: Long = currentTimeMillis(),
    transformations: List<String> = emptyList(),
    creator: String? = null,
    block: ProvenanceBuilder.() -> Unit = {}
): Provenance {
    val builder = ProvenanceBuilder(source, timestamp, transformations.toMutableList(), creator)
    builder.block()
    return builder.build()
}

/**
 * Builder for provenance records.
 */
class ProvenanceBuilder(
    private var source: String,
    private var timestamp: Long,
    private var transformations: MutableList<String>,
    private var creator: String?
) {
    fun source(source: String) { this.source = source }
    fun timestamp(timestamp: Long) { this.timestamp = timestamp }
    fun transform(step: String) { transformations.add(step) }
    fun creator(creator: String) { this.creator = creator }

    fun build(): Provenance = Provenance(source, timestamp, transformations, creator)
}

/**
 * Helper to create an evidence record.
 */
fun evidence(
    confidence: Double = 1.0,
    errorMargin: Double? = null,
    supportCount: Int? = null,
    notes: List<String> = emptyList()
): Evidence = Evidence(confidence, errorMargin, supportCount, notes)

/**
 * Get current time in milliseconds (platform-specific).
 */
expect fun currentTimeMillis(): Long

/**
 * Create a cell overlay with a simple DSL.
 */
fun <T> cellOverlay(
    value: T,
    role: OverlayRole = OverlayRole.OBSERVATION,
    provenance: Provenance? = null,
    evidence: Evidence? = null,
    dependencies: List<DependencyHandle> = emptyList()
): CellOverlay<T> = CellOverlay(value, role, provenance, evidence, dependencies)

/**
 * Create a column overlay with a simple DSL.
 */
fun columnOverlay(
    name: String,
    defaultRole: OverlayRole = OverlayRole.OBSERVATION,
    provenance: Provenance? = null,
    evidence: Evidence? = null,
    constraints: List<String> = emptyList(),
    description: String? = null
): ColumnOverlay = ColumnOverlay(name, defaultRole, provenance, evidence, constraints, description)

/**
 * Create a blackboard context.
 */
fun blackboardContext(
    id: String,
    columnOverlays: Map<Int, ColumnOverlay> = emptyMap(),
    provenance: Provenance? = null,
    tags: Map<String, String> = emptyMap()
): BlackboardContext = BlackboardContext(id, columnOverlays, provenance, tags)

// ============================================================================
// Extension functions for Cursor and RowVec overlay access
// ============================================================================
// Note: Complex Cursor/RowVec extension functions require deeper understanding
// of the TrikeShed DSL patterns. The core overlay types (CellOverlay, ColumnOverlay,
// BlackboardContext, etc.) are fully functional and can be used directly.

/**
 * Combine two blackboard contexts.
 */
fun combineContexts(
    a: BlackboardContext,
    b: BlackboardContext,
    offsetA: Int = 0
): BlackboardContext {
    val combinedOverlays = a.columnOverlays.mapKeys { (k, _) -> k + offsetA } +
        b.columnOverlays.mapKeys { (k, _) -> k + offsetA + a.columnOverlays.size }

    return BlackboardContext(
        id = "${a.id}+${b.id}",
        columnOverlays = combinedOverlays,
        provenance = a.provenance ?: b.provenance,
        tags = a.tags + b.tags
    )
}

/**
 * Helper to create a dependency handle for a cell.
 */
fun cellRef(row: Int, column: Int): DependencyHandle.CellRef =
    DependencyHandle.CellRef(row, column)

/**
 * Helper to create a dependency handle for a column.
 */
fun columnRef(column: Int): DependencyHandle.ColumnRef =
    DependencyHandle.ColumnRef(column)

/**
 * Helper to create an external dependency handle.
 */
fun externalResource(uri: String, selector: String? = null): DependencyHandle.ExternalResource =
    DependencyHandle.ExternalResource(uri, selector)

/**
 * Helper to create a composite dependency handle.
 */
fun compositeDependency(vararg handles: DependencyHandle): DependencyHandle.Composite =
    DependencyHandle.Composite(handles.toList())
