package borg.trikeshed.splat

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.α
import borg.trikeshed.splat.toRowVec
import borg.trikeshed.splat.splatSetOf
import borg.trikeshed.splat.toSplatSet
import borg.trikeshed.splat.toSplatSeries

/**
 * ConfixEntry — a key-value attribute store for Splat metadata.
 * Used for color, motion vector, uncertainty, and other extensible attributes.
 */
interface ConfixEntry {
    /** Get a value by key. */
    fun get(key: String): Any?

    /** Put a value, returning a new ConfixEntry (persistent/immutable). */
    fun put(key: String, value: Any?): ConfixEntry

    /** Remove a key, returning a new ConfixEntry. */
    fun remove(key: String): ConfixEntry

    /** All keys in this entry. */
    val keys: Series<String>

    /** Size of the entry. */
    val size: Int
}

/** Empty ConfixEntry implementation. */
object EmptyConfixEntry : ConfixEntry {
    override fun get(key: String): Any? = null
    override fun put(key: String, value: Any?): ConfixEntry = MapConfixEntry(mapOf(key to value))
    override fun remove(key: String): ConfixEntry = this
    override val keys: Series<String> = 0.j { "" }
    override val size: Int = 0
}

/** Map-backed ConfixEntry implementation. */
data class MapConfixEntry(
    private val map: Map<String, Any?>
) : ConfixEntry {
    override fun get(key: String): Any? = map[key]

    override fun put(key: String, value: Any?): ConfixEntry =
        MapConfixEntry(map + (key to value))

    override fun remove(key: String): ConfixEntry =
        MapConfixEntry(map - key)

    override val keys: Series<String> = map.keys.toList().toSeries<String>()
    override val size: Int = map.size

    companion object {
        fun of(vararg pairs: Pair<String, Any?>): ConfixEntry =
            MapConfixEntry(pairs.toMap())
    }
}

/** Extension functions for ConfixEntry DSL. */
fun confixEntryOf(vararg pairs: Pair<String, Any?>): ConfixEntry =
    MapConfixEntry.of(*pairs)

infix fun String.toConfix(value: Any?): Pair<String, Any?> = this to value

/**
 * Splat — n-dimensional probabilistic splat with Confix-backed attributes.
 *
 * Replaces the empirical Splat<T> = Series<OutcomeVec<T>> with a structured
 * representation that integrates with the algebraic layer (Cursor, Confix).
 */
data class Splat(
    /** Stable identity for idempotent operations. */
    val id: Long,

    /** n-dimensional mean position vector. */
    val position: Series<Double>,

    /** Covariance matrix as Series<Series<Double>> (row-major, symmetric positive-definite). */
    val covariance: Series<Series<Double>>,

    /** Opacity/importance weight α ∈ [0,1]. */
    val opacity: Double,

    /** Extensible attributes via Confix (color, motion vector, uncertainty, etc.). */
    val attributes: ConfixEntry = EmptyConfixEntry,
) {
    /** Dimensionality of this splat. */
    val dim: Int get() = position.size

    /** Validate covariance is square and matches position dimension. */
    init {
        require(covariance.size == dim) { "Covariance rows (${covariance.size}) must match position dim ($dim)" }
        covariance.forEach { row: Series<Double> ->
            require(row.size == dim) { "Covariance columns (${row.size}) must match position dim ($dim)" }
        }
        require(opacity in 0.0..1.0) { "Opacity must be in [0,1], got $opacity" }
    }

    /** Create a copy with updated position (for motion updates). */
    fun withPosition(newPosition: Series<Double>): Splat =
        copy(position = newPosition)

    /** Create a copy with updated covariance. */
    fun withCovariance(newCovariance: Series<Series<Double>>): Splat =
        copy(covariance = newCovariance)

    /** Create a copy with updated opacity. */
    fun withOpacity(newOpacity: Double): Splat =
        copy(opacity = newOpacity)

    /** Create a copy with updated attributes. */
    fun withAttributes(newAttributes: ConfixEntry): Splat =
        copy(attributes = newAttributes)

    /** Update a single attribute key. */
    fun withAttribute(key: String, value: Any?): Splat =
        copy(attributes = attributes.put(key, value))

    /** Remove an attribute key. */
    fun withoutAttribute(key: String): Splat =
        copy(attributes = attributes.remove(key))
}

/** SplatSet — a Cursor of Splat rows (each RowVec carries Splat value + metadata supplier). */
typealias SplatSet = Cursor