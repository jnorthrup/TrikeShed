package borg.trikeshed.splat

import borg.trikeshed.lib.Series

// ── Reification ──────────────────────────────────────────────────
enum class ReificationFlavor { COMPACT_BINARY, COLUMNAR, CLASSFILE }
interface ConfixReifiable { fun reify(flavor: ReificationFlavor): Any }

// ── SplatEvent hierarchy ──────────────────────────────────────────
enum class SplatEventType { CREATED, UPDATED, CULLED, MOTION_APPLIED, EIGEN_UPDATE, LOSS_VAL }

sealed class SplatEvent : ConfixReifiable {
    abstract val timestampNanos: Long
    abstract val eventType: SplatEventType
    abstract fun withTimestamp(ts: Long): SplatEvent
}

data class SplatCreated(
    val splat: Splat,
    override val timestampNanos: Long
) : SplatEvent() {
    override val eventType = SplatEventType.CREATED
    val splatId: Long? = splat.id
    override fun withTimestamp(ts: Long) = copy(timestampNanos = ts)
    override fun reify(flavor: ReificationFlavor): Any = TODO()
}

data class SplatUpdated(
    val splatId: Long,
    val changes: SplatAttributes,
    override val timestampNanos: Long
) : SplatEvent() {
    override val eventType = SplatEventType.UPDATED
    override fun withTimestamp(ts: Long) = copy(timestampNanos = ts)
    override fun reify(flavor: ReificationFlavor): Any = TODO()
}

data class SplatCulled(
    val splatId: Long,
    val reason: String,
    override val timestampNanos: Long
) : SplatEvent() {
    override val eventType = SplatEventType.CULLED
    override fun withTimestamp(ts: Long) = copy(timestampNanos = ts)
    override fun reify(flavor: ReificationFlavor): Any = TODO()
}

data class SplatMotionApplied(
    val splatId: Long,
    val delta: Series<Double>,
    val version: Long,
    override val timestampNanos: Long
) : SplatEvent() {
    override val eventType = SplatEventType.MOTION_APPLIED
    override fun withTimestamp(ts: Long) = copy(timestampNanos = ts)
    override fun reify(flavor: ReificationFlavor): Any = TODO()
}

data class EigenEvent(
    val splatId: Long,
    val eigenvalues: Series<Double>,
    val eigenvectors: Series<Series<Double>>,
    val covarianceTrace: Double,
    val aspectRatio: Double,
    val conditionNumber: Double,
    override val timestampNanos: Long
) : SplatEvent() {
    override val eventType = SplatEventType.EIGEN_UPDATE
    override fun withTimestamp(ts: Long) = copy(timestampNanos = ts)
    override fun reify(flavor: ReificationFlavor): Any = TODO()
}

enum class LossType { REPROJECTION, MAHALANOBIS, ENTROPY, SMOOTHNESS }

data class LossEvent(
    val splatId: Long,
    val lossType: LossType,
    val value: Double,
    val gradient: Series<Double>?,
    val iteration: Int,
    override val timestampNanos: Long
) : SplatEvent() {
    override val eventType = SplatEventType.LOSS_VAL
    override fun withTimestamp(ts: Long) = copy(timestampNanos = ts)
    override fun reify(flavor: ReificationFlavor): Any = TODO()
}

data class LossVal(
    val splatId: Long,
    val loss: Double,
    val iteration: Int,
    override val timestampNanos: Long
) : SplatEvent() {
    override val eventType = SplatEventType.LOSS_VAL
    override fun withTimestamp(ts: Long) = copy(timestampNanos = ts)
    override fun reify(flavor: ReificationFlavor): Any = TODO()
}