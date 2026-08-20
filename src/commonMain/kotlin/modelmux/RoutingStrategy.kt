package modelmux

import borg.trikeshed.lib.*

/**
 * A routing strategy is a pure ranking function over candidates.
 * It reads candidates and contextual health/latency inputs, and returns
 * a ranked Series of candidates.
 *
 * ## Provider-blind invariant
 * No strategy in this file may read [ModelCatalogEntry.provider] (or [ModelCatalogEntry.model])
 * to decide an ordering. Rankings are computed strictly from the neutral facts —
 * `freeTier`, `quotaRemaining`, `latencyEstimateMs` — plus the candidate's input position.
 * A ranking that consults provider identity would privilege a vendor and is forbidden.
 *
 * ## Laziness
 * Every ranking returns a [Series] whose index permutation is computed on first access and
 * whose elements are still pulled from the source Series. Candidates are never demoted to a
 * `List` and never copied.
 */
sealed interface RoutingStrategy<C, H> {
    /**
     * Stable label naming this ranking discipline. Stamped onto
     * [ModelSelectionEvent.ModelSelected.strategy] at the selection point.
     */
    val strategyName: String

    operator fun invoke(candidates: Series<C>, context: H): Series<C>
}

/**
 * How a candidate `C` exposes the neutral catalog facts a ranking reads.
 *
 * Strategies stay generic in `C` (the interface is unchanged); a lens supplies the typed
 * view onto [ModelCatalogEntry] without the strategy knowing what `C` actually is. When
 * `C` *is* [ModelCatalogEntry], use the entry-typed factories at the foot of this file.
 */
typealias EntryLens<C> = (C) -> ModelCatalogEntry

/**
 * Lazily reorder this Series by a permutation of its own indices, decorate-sort-undecorate.
 *
 * Each candidate is pulled from the source and projected to a sort key **exactly once** — the
 * source's index oracle may be arbitrarily expensive (a cursor, a lazily mapped view), so a
 * comparator that re-read it per comparison would cost `2·n·log n` reads instead of `n`.
 *
 * What is materialized is a `List<Int>` of *indices* and an array of keys; the candidates
 * themselves are never demoted out of Series form. The permutation is computed on first
 * element access and then cached, under [LazyThreadSafetyMode.PUBLICATION] — a ranked result
 * is a value handed to callers and this is a coroutine-heavy codebase, so two dispatcher
 * threads reading one result must not race on a half-published permutation. PUBLICATION costs
 * nothing on the uncontended path.
 *
 * `sortedWith` is a stable sort, so candidates whose keys compare equal keep their input
 * order. That is what makes "stable input order" the universal final tiebreak of every
 * strategy here, and it is why no strategy ever needs a provider-name tiebreak.
 */
private fun <C, K> Series<C>.rankedBy(key: (C) -> K, cmp: Comparator<K>): Series<C> {
    val src = this
    val n = src.size
    val order: List<Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val keys = arrayOfNulls<Any?>(n)
        var i = 0
        while (i < n) { keys[i] = key(src[i]); i++ }
        @Suppress("UNCHECKED_CAST")
        (0 until n).sortedWith(Comparator { x: Int, y: Int -> cmp.compare(keys[x] as K, keys[y] as K) })
    }
    return n j { i: Int -> src[order[i]] }
}

/**
 * Cheapest-first ordering over the neutral catalog facts, shared by [CostOptimizedStrategy]
 * and [AutoStrategy].
 *
 * 1. **Usable before exhausted.** A candidate with no quota left cannot serve the request, so
 *    it sinks below every candidate that can — otherwise a free tier at `quotaRemaining == 0`
 *    would head the ranking and the selection would be a guaranteed failure.
 * 2. **Free tier before paid.** Free costs nothing; the catalog carries no cost field, so this
 *    is the only cost signal that exists.
 * 3. **More remaining quota first.** Quota is budget already paid for.
 *
 * `tiebreak` refines what is left — [CostOptimizedStrategy] passes none and falls through to
 * stable input order, [AutoStrategy] passes latency.
 */
private fun costComparator(tiebreak: Comparator<ModelCatalogEntry>?): Comparator<ModelCatalogEntry> =
    Comparator { x: ModelCatalogEntry, y: ModelCatalogEntry ->
        val byUsable = (y.quotaRemaining > 0).compareTo(x.quotaRemaining > 0)
        if (byUsable != 0) return@Comparator byUsable
        val byTier = y.freeTier.compareTo(x.freeTier)
        if (byTier != 0) return@Comparator byTier
        val byQuota = y.quotaRemaining.compareTo(x.quotaRemaining)
        if (byQuota != 0) byQuota else tiebreak?.compare(x, y) ?: 0
    }

// ═══════════════════════════════════════════
// Core Strategy Hierarchy
// ═══════════════════════════════════════════

/**
 * Rank by **stable input order** — the caller's own precedence list wins, untouched.
 *
 * This is a true identity on the candidate Series and is deliberately so: the catalog (or an
 * upstream filter) has already expressed a preference by ordering, and `PriorityStrategy`
 * honours it rather than second-guessing it. It is the strategy to pick when priority is
 * configuration, not measurement.
 */
class PriorityStrategy<C, H> : RoutingStrategy<C, H> {
    override val strategyName: String get() = "priority"
    override fun invoke(candidates: Series<C>, context: H): Series<C> = candidates
}

/**
 * Rank by a quota-weighted score with a latency penalty, best first.
 *
 * `score = quotaRemaining / (1 + latencyEstimateMs / 1000)`
 *
 * Headroom is the numerator, so a candidate with more quota outranks a starved one; latency
 * divides it, so a second of extra round trip halves the value of that headroom. Ties fall
 * back to input order.
 */
class WeightedStrategy<C, H>(private val lens: EntryLens<C>) : RoutingStrategy<C, H> {
    override val strategyName: String get() = "weighted"

    override fun invoke(candidates: Series<C>, context: H): Series<C> =
        candidates.rankedBy(::score, Comparator { x: Double, y: Double -> y.compareTo(x) })

    /** Quota is the numerator, so an exhausted candidate scores zero and sinks on its own. */
    private fun score(c: C): Double = lens(c).let { e ->
        e.quotaRemaining.toDouble() / (1.0 + e.latencyEstimateMs / 1000.0)
    }
}

/**
 * Rank cheapest first — usable before exhausted, then free tier before paid, then most
 * remaining quota first. See [costComparator]. Ties fall back to input order.
 *
 * [ModelCatalogEntry] carries no cost field, so "cheap" is expressed by the facts that do
 * exist: a free tier costs nothing, and remaining quota is budget already paid for.
 */
class CostOptimizedStrategy<C, H>(private val lens: EntryLens<C>) : RoutingStrategy<C, H> {
    override val strategyName: String get() = "cost-optimized"

    override fun invoke(candidates: Series<C>, context: H): Series<C> =
        candidates.rankedBy(lens, costComparator(null))
}

/**
 * Rotate the candidate list by one position per invocation, spreading load evenly.
 *
 * Invocation *n* starts the ranking at input index *n mod size* and wraps; relative order is
 * otherwise preserved. Rotation is the whole ranking — no candidate fact is consulted, which
 * makes this the most provider-blind strategy of the five.
 *
 * The counter is kept **un-normalized** and reduced modulo the candidate count only at the
 * moment of use. Normalizing it in place would reset the spread whenever the candidate count
 * changed — and it changes constantly, since callers filter by capability before ranking. A
 * counter clamped by a two-candidate call would restart a six-candidate call at index 0 every
 * time, picking the same head twice in a row and starving indices 1..5, which is precisely the
 * even spread this strategy exists to provide.
 *
 * **Single-writer assumption.** The counter is plain instance state, deliberately not guarded
 * (commonMain has no `synchronized`/`@Volatile`, and a lock here would be worse than the drift
 * it prevents). One router owns one instance; concurrent callers must each hold their own, or
 * accept that a raced counter merely skews the spread, never corrupts a result.
 *
 * The counter advances at invoke time, not at element-access time, so the Series a caller
 * receives keeps the offset it was issued.
 */
class RoundRobinStrategy<C, H> : RoutingStrategy<C, H> {
    override val strategyName: String get() = "round-robin"

    /** Un-normalized invocation counter; the start index is this reduced by the candidate count. */
    private var cursor: Int = 0

    /** Invocations served so far — the start index for `n` candidates is `invocations % n`. */
    val invocations: Int get() = cursor

    override fun invoke(candidates: Series<C>, context: H): Series<C> {
        val n = candidates.size
        if (n == 0) return candidates
        val start = cursor % n
        // wrap short of overflow rather than going negative; any multiple of a plausible
        // candidate count would do, and 2^30 is one.
        cursor = if (cursor == MAX_CURSOR) 0 else cursor + 1
        return n j { i: Int -> candidates[(start + i) % n] }
    }

    private companion object {
        const val MAX_CURSOR = 1 shl 30
    }
}

/**
 * The default: cost first, latency as the tiebreak.
 *
 * The primary ordering is exactly [CostOptimizedStrategy]'s ([costComparator]); where that
 * leaves candidates equal, the faster one wins. Expressed as one comparator rather than
 * `CostOptimized j latencySort` because a second stable sort on latency alone would overwrite
 * the cost ordering instead of refining it; this is the composition's *intent*, correctly spelled.
 */
class AutoStrategy<C, H>(private val lens: EntryLens<C>) : RoutingStrategy<C, H> {
    override val strategyName: String get() = "auto"

    override fun invoke(candidates: Series<C>, context: H): Series<C> =
        candidates.rankedBy(lens, costComparator(byLatency))

    private companion object {
        val byLatency = Comparator { x: ModelCatalogEntry, y: ModelCatalogEntry ->
            x.latencyEstimateMs.compareTo(y.latencyEstimateMs)
        }
    }
}

// ═══════════════════════════════════════════
// Join Composition
// ═══════════════════════════════════════════

/**
 * PassThroughStrategy is the identity element. It returns the candidates unchanged.
 */
class PassThroughStrategy<C, H> : RoutingStrategy<C, H> {
    override val strategyName: String get() = "pass-through"
    override fun invoke(candidates: Series<C>, context: H): Series<C> = candidates
}

/**
 * Applying a Join composed of two routing strategies means applying them sequentially: A then B.
 * It evaluates strategy A on the input candidates, and then strategy B on the result.
 */
class ComposedStrategy<C, H>(
    override val a: RoutingStrategy<C, H>,
    override val b: RoutingStrategy<C, H>
) : RoutingStrategy<C, H>, Join<RoutingStrategy<C, H>, RoutingStrategy<C, H>> {
    override val strategyName: String get() = "${a.strategyName} j ${b.strategyName}"

    override fun invoke(candidates: Series<C>, context: H): Series<C> {
        val resultA = a.invoke(candidates, context)
        return b.invoke(resultA, context)
    }
}

/**
 * Infix operator to compose any two strategies dynamically while remaining in the RoutingStrategy domain
 */
infix fun <C, H> RoutingStrategy<C, H>.j(other: RoutingStrategy<C, H>): RoutingStrategy<C, H> =
    ComposedStrategy(this, other)

// ═══════════════════════════════════════════
// Entry-typed factories
// ═══════════════════════════════════════════
//
// The common case: candidates already *are* ModelCatalogEntry, so the lens is identity.
// These exist so callers never write `{ it }` and never reach for an unchecked cast.

fun <H> priorityOf(): PriorityStrategy<ModelCatalogEntry, H> = PriorityStrategy()

fun <H> weightedOf(): WeightedStrategy<ModelCatalogEntry, H> = WeightedStrategy { it }

fun <H> costOptimizedOf(): CostOptimizedStrategy<ModelCatalogEntry, H> = CostOptimizedStrategy { it }

fun <H> roundRobinOf(): RoundRobinStrategy<ModelCatalogEntry, H> = RoundRobinStrategy()

fun <H> autoOf(): AutoStrategy<ModelCatalogEntry, H> = AutoStrategy { it }
