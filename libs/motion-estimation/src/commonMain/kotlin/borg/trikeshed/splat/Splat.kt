package borg.trikeshed.splat

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.α
import borg.trikeshed.lib.view.toIterable

// ── Splat core ────────────────────────────────────────────────────
data class Splat(
    val id: Long, val position: Series<Double>, val covariance: Series<Series<Double>>,
    val opacity: Double, val attributes: SplatAttributes = EmptySplatAttributes,
) {
    val dim: Int get() = position.size
    init {
        require(covariance.size == dim) { "Covariance rows (${covariance.size}) must match position dim ($dim)" }
        for (row in covariance.toIterable()) {
            require(row.size == dim) { "Covariance columns (${row.size}) must match position dim ($dim)" }
        }
        require(opacity in 0.0..1.0) { "Opacity must be in [0,1], got $opacity" }
    }
    fun withPosition(newPosition: Series<Double>): Splat = copy(position = newPosition)
    fun withCovariance(newCovariance: Series<Series<Double>>): Splat = copy(covariance = newCovariance)
    fun withOpacity(newOpacity: Double): Splat = copy(opacity = newOpacity)
    fun withAttributes(newAttributes: SplatAttributes): Splat = copy(attributes = newAttributes)
    fun withAttribute(key: String, value: Any?): Splat = copy(attributes = attributes.with(key, value))
    fun withoutAttribute(key: String): Splat = copy(attributes = attributes.without(key))
    fun applyMotion(delta: Series<Double>, version: Long): SplatEvent = SplatMotionApplied(this.id, delta, version, captureNanos())
}

fun captureNanos(): Long = System.nanoTime()