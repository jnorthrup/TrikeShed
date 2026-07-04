package borg.trikeshed.collections.multiindex

import borg.trikeshed.lib.OpK
import borg.trikeshed.lib.Series

/**
 * MultiIndexK — GADT facet keys for multi-index container dispatch.
 *
 * Analogous to boost::multi_index_container index specifiers.
 * A single element store; multiple simultaneously-maintained index projections.
 * Each key selects a different access mode via FacetedRow<MultiIndexK<*>>.
 *
 * Pattern: same as ConfixIndexK / ColK — sealed OpK<R> family, one sealed
 * branch per access semantics, result type R encoded in the class hierarchy.
 *
 * Usage:
 *   val c = MultiIndexContainer<Person>()
 *   val byName: (String) -> Int? = c.facets[MultiIndexK.ByHash(Person::name)]
 *   val ordered: Series<Int>     = c.facets[MultiIndexK.ByOrder(Person::age)]
 *   val range: (Int,Int)->Series<Int> = c.facets[MultiIndexK.ByRange(Person::age)]
 */
sealed class MultiIndexK<out R> : OpK<R>() {

    /**
     * Hash lookup — key to backing-store position.
     * Returns (K) -> Int? : null when absent.
     * Intended backing: elastic triangular probing (arXiv:2501.02305).
     */
    class ByHash<K : Any>(val extractor: (Any?) -> K) : MultiIndexK<(K) -> Int?>()

    /**
     * Sorted traversal — positions in ascending key order.
     * Returns Series<Int>: a Series of backing-store positions, sorted by extractor.
     */
    class ByOrder<K : Comparable<K>>(val extractor: (Any?) -> K) : MultiIndexK<Series<Int>>()

    /**
     * Range scan — [lo, hi] over a sorted projection.
     * Returns (K, K) -> Series<Int>: closed-interval lookup.
     */
    class ByRange<K : Comparable<K>>(val extractor: (Any?) -> K) : MultiIndexK<(K, K) -> Series<Int>>()

    /** Insertion-order view — positions in the order elements were added. */
    data object BySequence : MultiIndexK<Series<Int>>()

    /** Raw backing element store as a Series. */
    data object Elements : MultiIndexK<Series<Any?>>()
}
