package borg.trikeshed.miniduck.manifold

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.j

/**
 * Manifold axes — a product of three independent orthogonals.
 *
 * No axis owns another. All shapes are pure Join/Twin/Series composition
 * per PRELOAD.md: lazy first, typealiases compress semantics not substance.
 *
 *   ShapeAxis  = RowVec family tag  (Int index into family registry)
 *   TimeAxis   = WAL seq j sealedAt  (0L sealedAt = open/mutable)
 *   AccessAxis = cacheOffset j spanLen  (cache-line-aligned topology)
 *
 * TimeAxis.b == 0L  → block is open/mutable (WAL writer holds the pen)
 * TimeAxis.b  > 0L  → sealed at that sequence (readers see immutable view)
 * This IS the sealing boundary — no separate state machine needed.
 *
 * AccessAxis.a aligns to CpuCacheTopology.cacheLineBytes when available.
 * AccessAxis.b is the span length matching Confix Twin<Int> byte offsets.
 *
 * ── Runtime Metrics Axis (hotspot detection) ────────────────────────────────────────
 *
 * MetricsAxis captures timing profiles for executing CCEK branches:
 *   - initialNanos:  time from fork to first execution (cold start)
 *   - steadyNanos: time at steady-state (warm / amortized pattern)
 *   - fibOrdinal:  fibonacci position (0=seed, 1+=subsequent runs)
 *
 * This enables:
 *   - Hamming distance between execution times (hotspot detection)
 *   - "how far" current execution is from steady state
 *   - ranking by execution latency for optimization targets
 *
 * Shape: (initialNanos j steadyNanos) j fibOrdinal = Axis3<Long,Long,Int>
 * Projects as: axis3.metrics.initial, .steady, .fibOrdinal
 */

typealias ShapeAxis  = Int
typealias TimeAxis   = Twin<Long>     // a=seq  b=sealedAt (0L=open)
typealias AccessAxis = Twin<Long>     // a=cacheAlignedOffset  b=spanLen
typealias MetricsAxis = Twin<Long>   // a=initialNanos  b=steadyNanos
typealias FibOrdinal = Int          // fibonacci position (0=seed, grows)

/** Four-axis product now: shape × time × access × metrics */
typealias Axis3<A, B, C>            = Join<A, Join<B, C>>
typealias Axis4<A, B, C, D>        = Join<A, Join<B, Join<C, D>>>

/** A payload P located at a three-axis coordinate. */
typealias ManifoldPoint<A, B, C, P> = Join<Axis3<A, B, C>, P>

/** A payload P located at a four-axis coordinate (with metrics). */
typealias ManifoldPointM<A, B, C, D, P> = Join<Axis4<A, B, C, D>, P>

/** Concrete MiniDuck point: the canonical concept/block carrier. */
typealias MiniDuckPoint = ManifoldPoint<ShapeAxis, TimeAxis, AccessAxis, RowVec>

// ── constructors ─────────────────────────────────────────────────────────────

fun timeAxis(seq: Long, sealedAt: Long = 0L): TimeAxis = seq j sealedAt
fun accessAxis(offset: Long = 0L, span: Long = 0L): AccessAxis = offset j span
fun metricsAxis(initialNanos: Long = 0L, steadyNanos: Long = 0L): MetricsAxis = initialNanos j steadyNanos
fun <A, B, C> axis3(a: A, b: B, c: C): Axis3<A, B, C> = a j (b j c)
fun <A, B, C, D> axis4(a: A, b: B, c: C, d: D): Axis4<A, B, C, D> = a j (b j (c j d))

fun <A, B, C, P> manifoldPoint(shape: A, time: B, access: C, payload: P): ManifoldPoint<A, B, C, P> =
    axis3(shape, time, access) j payload

// ── projections ───────────────────────────────────────────────────────────────

val <A, B, C, P> ManifoldPoint<A, B, C, P>.axes: Axis3<A, B, C> get() = a
val <A, B, C, P> ManifoldPoint<A, B, C, P>.payload: P get() = b
val <A, B, C> Axis3<A, B, C>.shapeAxis: A get() = a
val <A, B, C> Axis3<A, B, C>.timeAxis: B get() = b.a
val <A, B, C> Axis3<A, B, C>.accessAxis: C get() = b.b

// Axis4 projections (four-axis with metrics) — renamed to avoid platform clash
val <A, B, C, D> Axis4<A, B, C, D>.s4: A get() = a
val <A, B, C, D> Axis4<A, B, C, D>.t4: B get() = b.a
val <A, B, C, D> Axis4<A, B, C, D>.a4: C get() = b.b.a
val <A, B, C, D> Axis4<A, B, C, D>.m4: D get() = b.b.b

// TimeAxis helpers
val TimeAxis.seq: Long get() = a
val TimeAxis.sealedAt: Long get() = b
val TimeAxis.isSealed: Boolean get() = b > 0L
fun TimeAxis.seal(atSeq: Long): TimeAxis = a j atSeq

// AccessAxis helpers
val AccessAxis.offset: Long get() = a
val AccessAxis.span: Long get() = b

// MetricsAxis helpers (runtime measurements)
val MetricsAxis.initialNanos: Long get() = a
val MetricsAxis.steadyNanos: Long get() = b

/**
 * Hotspot detection: ratio of initial vs. steady state.
 * > 10x means the operation is still "warming up".
 */
val MetricsAxis.warmupRatio: Float get() =
    if (b > 0L) a.toFloat() / b.toFloat() else Float.POSITIVE_INFINITY

/**
 * Distance from steady state in nanoseconds.
 */
val MetricsAxis.distanceFromSteady: Long get() = a - b

/**
 * Whether asymptotically at steady state (within 5% of steadyNanos).
 */
val MetricsAxis.isSteady: Boolean get() =
    b > 0L && (a - b).toFloat() / b.toFloat() < 0.05f

// FibOrdinal helpers
val FibOrdinal.isSeed: Boolean get() = this == 0
val FibOrdinal.next: FibOrdinal get() = this + 1

// ── Shape family tags (open-ended; extend by adding constants) ────────────────

object ShapeFamily {
    const val DOC   = 0
    const val VIEW  = 1
    const val BLOB  = 2
    const val JSON  = 3
    const val BLOCK = 4
    const val YAML  = 5
}

// ── Series<Int> shape helper for Tensor access (canonical: borg.trikeshed.lib.shapeOf) ──
