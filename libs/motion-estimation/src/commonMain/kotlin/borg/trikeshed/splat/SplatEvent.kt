package borg.trikeshed.splat

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.α

// ── ConfixReifiable ───────────────────────────────────────────────
//
// Entities that can be reified into multiple flavors:
//   COMPACT_BINARY  — ring pool path (arena-backed)
//   COLUMNAR        — blackboard path
//   CLASSFILE       — provenance path

/** ReificationFlavor — destination determines optimal representation. */
enum class ReificationFlavor {
    COMPACT_BINARY,  // ring pool path (arena-backed, cache-friendly)
    COLUMNAR,        // blackboard path (query-friendly, indexed)
    CLASSFILE        // provenance path (metadata-rich, attachable)
}

/** ConfixReifiable — entities that can be reified into multiple flavors. */
interface ConfixReifiable {
    fun reify(flavor: ReificationFlavor): Any
}

// ── SplatEvent ───────────────────────────────────────────────────
//
// Events emitted by the splat system. All events are ConfixReifiable
// and carry a timestamp for ordering and provenance.
// Uses Series transforms exclusively — no hot flows or byte arrays.

/** Event type enumeration for routing and filtering. */
enum class SplatEventType { CREATED, UPDATED, CULLED, MOTION_APPLIED }

/** Sealed hierarchy of events emitted by the splat system. */
sealed class SplatEvent : ConfixReifiable {
    abstract val timestampNanos: Long
    abstract val eventType: SplatEventType
    abstract val splatId: Long?
    abstract fun withTimestamp(ts: Long): SplatEvent
}

/** Event indicating a new splat was created. */
data class SplatCreated(
    val splat: Splat,
    override val timestampNanos: Long
) : SplatEvent() {
    override val eventType = SplatEventType.CREATED
    override val splatId: Long? = splat.id
    override fun withTimestamp(ts: Long) = copy(timestampNanos = ts)
    
    override fun reify(flavor: ReificationFlavor): Any = when (flavor) {
        ReificationFlavor.COMPACT_BINARY -> CompactBinarySplatEvent.fromCreated(this)
        ReificationFlavor.COLUMNAR -> ColumnarSplatEvent.fromCreated(this)
        ReificationFlavor.CLASSFILE -> ClassfileSplatEvent.fromCreated(this)
    }
}

/** Event indicating a splat was updated. */
data class SplatUpdated(
    override val splatId: Long,
    val changes: ConfixEntry,
    override val timestampNanos: Long
) : SplatEvent() {
    override val eventType = SplatEventType.UPDATED
    
    override fun withTimestamp(ts: Long) = copy(timestampNanos = ts)
    
    override fun reify(flavor: ReificationFlavor): Any = when (flavor) {
        ReificationFlavor.COMPACT_BINARY -> CompactBinarySplatEvent.fromUpdated(this)
        ReificationFlavor.COLUMNAR -> ColumnarSplatEvent.fromUpdated(this)
        ReificationFlavor.CLASSFILE -> ClassfileSplatEvent.fromUpdated(this)
    }
}

/** Event indicating a splat was culled (removed). */
data class SplatCulled(
    override val splatId: Long,
    val reason: String,
    override val timestampNanos: Long
) : SplatEvent() {
    override val eventType = SplatEventType.CULLED
    
    override fun withTimestamp(ts: Long) = copy(timestampNanos = ts)
    
    override fun reify(flavor: ReificationFlavor): Any = when (flavor) {
        ReificationFlavor.COMPACT_BINARY -> CompactBinarySplatEvent.fromCulled(this)
        ReificationFlavor.COLUMNAR -> ColumnarSplatEvent.fromCulled(this)
        ReificationFlavor.CLASSFILE -> ClassfileSplatEvent.fromCulled(this)
    }
}

/** Event indicating motion was applied to a splat. */
data class SplatMotionApplied(
    override val splatId: Long,
    val delta: Series<Double>,
    val version: Long,
    override val timestampNanos: Long
) : SplatEvent() {
    override val eventType = SplatEventType.MOTION_APPLIED
    
    override fun withTimestamp(ts: Long) = copy(timestampNanos = ts)
    
    override fun reify(flavor: ReificationFlavor): Any = when (flavor) {
        ReificationFlavor.COMPACT_BINARY -> CompactBinarySplatEvent.fromMotionApplied(this)
        ReificationFlavor.COLUMNAR -> ColumnarSplatEvent.fromMotionApplied(this)
        ReificationFlavor.CLASSFILE -> ClassfileSplatEvent.fromMotionApplied(this)
    }
}

// ── Reification Flavors using Series transforms ──────────────────
//
// All representations use Series α (map) and size j { } pattern.
// No hot flows, no byte arrays — pure algebraic transforms.

/**
 * Compact binary representation using Series transforms.
 * All data encoded as Series<Double> for the numeric parts.
 */
data class CompactBinarySplatEvent(
    val eventTypeCode: Int,         // 0=Created, 1=Updated, 2=Culled, 3=MotionApplied
    val splatId: Long,
    val timestampNanos: Long,
    val dim: Int,                   // dimensionality
    val numericData: Series<Double>, // flattened: position + covariance + opacity
    val stringData: Series<String>   // for non-numeric payload (reasons, keys, etc.)
) {
    companion object {
        const val CREATED_CODE = 0
        const val UPDATED_CODE = 1
        const val CULLED_CODE = 2
        const val MOTION_APPLIED_CODE = 3
        
        /** From SplatCreated using pure Series transforms. */
        fun fromCreated(event: SplatCreated): CompactBinarySplatEvent {
            val splat = event.splat
            val dim = splat.dim
            
            // Flatten position, covariance (row-major), and opacity into numericData
            // Using α transform for covariance flattening
            val covFlat: Series<Double> = splat.covariance α { row: Series<Double> -> 
                row α { it }  // flatten inner Series
            }
            
            // Concatenate: position + covariance + opacity
            val numericData: Series<Double> = 
                (dim + dim * dim + 1) j { idx: Int ->
                    when {
                        idx < dim -> splat.position[idx]                                    // position
                        idx < dim + dim * dim -> covFlat[idx - dim]                         // covariance
                        else -> splat.opacity                                               // opacity
                    }
                }
            
            return CompactBinarySplatEvent(
                CREATED_CODE, splat.id, event.timestampNanos, dim, numericData, 
                dim j { "" }  // empty string series for Created
            )
        }
        
        /** From SplatUpdated using pure Series transforms. */
        fun fromUpdated(event: SplatUpdated): CompactBinarySplatEvent {
            val changeKeys = event.changes.keys
            val changeValues: Series<String> = changeKeys α { key: String ->
                event.changes.get(key)?.toString() ?: ""
            }
            return CompactBinarySplatEvent(
                UPDATED_CODE, event.splatId, event.timestampNanos, 
                event.changes.size,  // dim = number of changes
                changeValues α { 0.0 },  // numeric placeholder
                changeKeys α { key: String -> key } + changeValues
            )
        }
        
        /** From SplatCulled using pure Series transforms. */
        fun fromCulled(event: SplatCulled): CompactBinarySplatEvent {
            return CompactBinarySplatEvent(
                CULLED_CODE, event.splatId, event.timestampNanos,
                0,  // dim = 0 for culled
                0.j { 0.0 },  // empty numeric
                1.j { event.reason }  // single string: reason
            )
        }
        
        /** From SplatMotionApplied using pure Series transforms. */
        fun fromMotionApplied(event: SplatMotionApplied): CompactBinarySplatEvent {
            val dim = event.delta.size
            // Flatten delta and append version as last element
            val numericData: Series<Double> = (dim + 1) j { idx: Int ->
                if (idx < dim) event.delta[idx] else event.version.toDouble()
            }
            return CompactBinarySplatEvent(
                MOTION_APPLIED_CODE, event.splatId, event.timestampNanos,
                dim, numericData, 0.j { "" }
            )
        }
    }
}

/**
 * Columnar representation using Series transforms.
 * Attributes stored as Series for query-friendly access.
 */
data class ColumnarSplatEvent(
    val eventType: SplatEventType,
    val splatId: Long?,
    val timestampNanos: Long,
    val dim: Int,
    val numericAttrs: Series<Double>,   // position, covariance, opacity, etc.
    val stringAttrs: Series<String>     // keys, reasons, etc.
) {
    companion object {
        fun fromCreated(event: SplatCreated): ColumnarSplatEvent {
            val splat = event.splat
            val dim = splat.dim
            
            // Flatten covariance row-major
            val covFlat: Series<Double> = splat.covariance α { row: Series<Double> -> row α { it } }
            
            // Build numeric attrs: position + covariance + opacity
            val numericAttrs: Series<Double> = (dim + dim * dim + 1) j { idx: Int ->
                when {
                    idx < dim -> splat.position[idx]
                    idx < dim + dim * dim -> covFlat[idx - dim]
                    else -> splat.opacity
                }
            }
            
            // String attrs: dim tag
            val stringAttrs: Series<String> = 1.j { "dim" } + dim.j { "" }
            
            return ColumnarSplatEvent(SplatEventType.CREATED, splat.id, event.timestampNanos, dim, numericAttrs, stringAttrs)
        }
        
        fun fromUpdated(event: SplatUpdated): ColumnarSplatEvent {
            val changeKeys = event.changes.keys
            val changeCount = event.changes.size
            // String attrs: keys interleaved with values
            val stringAttrs: Series<String> = changeKeys α { k -> k } + (changeKeys α { event.changes.get(it)?.toString() ?: "" })
            return ColumnarSplatEvent(SplatEventType.UPDATED, event.splatId, event.timestampNanos, changeCount, changeCount.j { 0.0 }, stringAttrs)
        }
        
        fun fromCulled(event: SplatCulled): ColumnarSplatEvent {
            return ColumnarSplatEvent(SplatEventType.CULLED, event.splatId, event.timestampNanos, 0, 0.j { 0.0 }, 1.j { event.reason })
        }
        
        fun fromMotionApplied(event: SplatMotionApplied): ColumnarSplatEvent {
            val dim = event.delta.size
            val numericAttrs: Series<Double> = event.delta α { it } + 1.j { event.version.toDouble() }
            return ColumnarSplatEvent(SplatEventType.MOTION_APPLIED, event.splatId, event.timestampNanos, dim, numericAttrs, 0.j { "" })
        }
    }
}

/**
 * Classfile representation using Series transforms.
 * Provenance stored as Series for transformation chain tracking.
 */
data class ClassfileSplatEvent(
    val eventType: SplatEventType,
    val splatId: Long?,
    val timestampNanos: Long,
    val provenance: Series<String>,  // source, module, transformation, creator
    val payload: Series<Any?>         // splat data or changes as Series
) {
    companion object {
        fun fromCreated(event: SplatCreated): ClassfileSplatEvent {
            val splat = event.splat
            // Provenance: source, module, transformation, creator (nullable)
            val provenance: Series<String> = 4.j { i: Int ->
                when (i) {
                    0 -> "motion-estimation"
                    1 -> "splat"
                    2 -> "SplatCreated"
                    else -> ""
                }
            }
            // Payload as Series: id, position (flattened), covariance (flattened), opacity
            val dim = splat.dim
            val covFlat: Series<Double> = splat.covariance α { row: Series<Double> -> row α { it } }
            val payloadPos: Series<Double> = splat.position α { it }
            val payloadCov: Series<Double> = covFlat α { it }
            val payload: Series<Any?> = (1 + dim + dim * dim + 1) j { idx: Int ->
                when {
                    idx == 0 -> splat.id.toDouble()
                    idx <= dim -> payloadPos[idx - 1]
                    idx <= dim + dim * dim -> payloadCov[idx - dim - 1]
                    else -> splat.opacity
                }
            }
            return ClassfileSplatEvent(SplatEventType.CREATED, splat.id, event.timestampNanos, provenance, payload)
        }
        
        fun fromUpdated(event: SplatUpdated): ClassfileSplatEvent {
            val provenance: Series<String> = 4.j { i: Int ->
                when (i) {
                    0 -> "motion-estimation"
                    1 -> "splat"
                    2 -> "SplatUpdated"
                    else -> ""
                }
            }
            val changeKeys = event.changes.keys
            val payload: Series<Any?> = changeKeys α { k -> event.changes.get(k) }
            return ClassfileSplatEvent(SplatEventType.UPDATED, event.splatId, event.timestampNanos, provenance, payload)
        }
        
        fun fromCulled(event: SplatCulled): ClassfileSplatEvent {
            val provenance: Series<String> = 4.j { i: Int ->
                when (i) {
                    0 -> "motion-estimation"
                    1 -> "splat"
                    2 -> "SplatCulled"
                    else -> ""
                }
            }
            val payload: Series<Any?> = 1.j { event.reason as Any? }
            return ClassfileSplatEvent(SplatEventType.CULLED, event.splatId, event.timestampNanos, provenance, payload)
        }
        
        fun fromMotionApplied(event: SplatMotionApplied): ClassfileSplatEvent {
            val provenance: Series<String> = 4.j { i: Int ->
                when (i) {
                    0 -> "motion-estimation"
                    1 -> "splat"
                    2 -> "SplatMotionApplied"
                    else -> ""
                }
            }
            val deltaPayload: Series<Double> = event.delta α { it }
            val versionPayload: Series<Any?> = 1.j { event.version as Any? }
            val payload: Series<Any?> = deltaPayload α { it as Any? } + versionPayload
            return ClassfileSplatEvent(SplatEventType.MOTION_APPLIED, event.splatId, event.timestampNanos, provenance, payload)
        }
    }
}

// ── Timestamp capture utility ────────────────────────────────────

/** Capture current time in nanoseconds. Platform-specific implementation. */
expect fun captureNanos(): Long