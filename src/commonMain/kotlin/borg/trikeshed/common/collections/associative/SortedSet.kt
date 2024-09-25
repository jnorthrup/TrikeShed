@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.common.collections.associative

import borg.trikeshed.common.collections._l
import borg.trikeshed.common.collections.binarySearch
import borg.trikeshed.common.collections.s_
import borg.trikeshed.lib.*

/**
 * A [Set] that further provides a *total ordering* on its elements.
 * The elements are ordered using their [natural][Comparable], or by a [Comparator] typically provided at sorted
 * set creation time.  The set's iterator will traverse the set in
 * ascending element order. Several additional operations are provided
 * to take advantage of the ordering.  (This interface is the set
 * analogue of [SortedMap].)
 *
 *
 * All elements inserted into a sorted set must implement the `Comparable`
 * interface (or be accepted by the specified comparator).  Furthermore, all
 * such elements must be *mutually comparable*: `e1.compareTo(e2)`
 * (or `comparator.compare(e1, e2)`) must not throw a
 * `ClassCastException` for any elements `e1` and `e2` in
 * the sorted set.  Attempts to violate this restriction will cause the
 * offending method or constructor invocation to throw a
 * `ClassCastException`.
 *
 *
 * Note that the ordering maintained by a sorted set (whether or not an
 * explicit comparator is provided) must be *consistent with equals* if
 * the sorted set is to correctly implement the `Set` interface.  (See
 * the `Comparable` interface or `Comparator` interface for a
 * precise definition of *consistent with equals*.)  This is so because
 * the `Set` interface is defined in terms of the `equals`
 * operation, but a sorted set performs all element comparisons using its
 * `compareTo` (or `compare`) method, so two elements that are
 * deemed equal by this method are, from the standpoint of the sorted set,
 * equal.  The behavior of a sorted set *is* well-defined even if its
 * ordering is inconsistent with equals; it just fails to obey the general
 * contract of the `Set` interface.
 *
 *
 * All general-purpose sorted set implementation classes should
 * provide four "standard" constructors: 1) A void (no arguments)
 * constructor, which creates an empty sorted set sorted according to
 * the natural ordering of its elements.  2) A constructor with a
 * single argument of type `Comparator`, which creates an empty
 * sorted set sorted according to the specified comparator.  3) A
 * constructor with a single argument of type `Collection`,
 * which creates a new sorted set with the same elements as its
 * argument, sorted according to the natural ordering of the elements.
 * 4) A constructor with a single argument of type `SortedSet`,
 * which creates a new sorted set with the same elements and the same
 * ordering as the input sorted set.  There is no way to enforce this
 * recommendation, as interfaces cannot contain constructors.
 *
 *
 * Note: several methods return subsets with restricted ranges.
 * Such ranges are *half-open*, that is, they include their low
 * endpoint but not their high endpoint (where applicable).
 * If you need a *closed range* (which includes both endpoints), and
 * the element type allows for calculation of the successor of a given
 * value, merely request the subrange from `lowEndpoint` to
 * `successor(highEndpoint)`.  For example, suppose that `s`
 * is a sorted set of strings.  The following idiom obtains a view
 * containing all of the strings in `s` from `low` to
 * `high`, inclusive:<pre>
 * SortedSet&lt;String&gt; sub = s.subSet(low, high+"\0");</pre>
 *
 * A similar technique can be used to generate an *open range* (which
 * contains neither endpoint).  The following idiom obtains a view
 * containing all of the Strings in `s` from `low` to
 * `high`, exclusive:<pre>
 * SortedSet&lt;String&gt; sub = s.subSet(low+"\0", high);</pre>
 *
 *
 * This interface is a member of the
 * [
 * Java Collections Framework]({@docRoot}/java.base/java/util/package-summary.html#CollectionsFramework).
 *
 * @param <E> the type of elements maintained by this set
 *
 * @author  Josh Bloch
 * @see Set
 *
 * @see TreeSet
 *
 * @see SortedMap
 *
 * @see Collection
 *
 * @see Comparable
 *
 * @see Comparator
 *
 * @see ClassCastException
 *
 * @since 1.2
</E> */
interface SortedSet<E> : MutableSet<E> {
    /**
     * Returns the comparator used to order the elements in this set,
     * or `null` if this set uses the [ natural ordering][Comparable] of its elements.
     *
     * @return the comparator used to order the elements in this set,
     * or `null` if this set uses the natural ordering
     * of its elements
     */
    fun comparator(): Comparator<in E>?

    /**
     * Returns a view of the portion of this set whose elements range
     * from `fromElement`, inclusive, to `toElement`,
     * exclusive.  (If `fromElement` and `toElement` are
     * equal, the returned set is empty.)  The returned set is backed
     * by this set, so changes in the returned set are reflected in
     * this set, and vice-versa.  The returned set supports all
     * optional set operations that this set supports.
     *
     *
     * The returned set will throw an `IllegalArgumentException`
     * on an attempt to insert an element outside its range.
     *
     * @param fromElement low endpoint (inclusive) of the returned set
     * @param toElement high endpoint (exclusive) of the returned set
     * @return a view of the portion of this set whose elements range from
     * `fromElement`, inclusive, to `toElement`, exclusive
     * @throws ClassCastException if `fromElement` and
     * `toElement` cannot be compared to one another using this
     * set's comparator (or, if the set has no comparator, using
     * natural ordering).  Implementations may, but are not required
     * to, throw this exception if `fromElement` or
     * `toElement` cannot be compared to elements currently in
     * the set.
     * @throws NullPointerException if `fromElement` or
     * `toElement` is null and this set does not permit null
     * elements
     * @throws IllegalArgumentException if `fromElement` is
     * greater than `toElement`; or if this set itself
     * has a restricted range, and `fromElement` or
     * `toElement` lies outside the bounds of the range
     */
    fun subSet(fromElement: E, toElement: E): SortedSet<E>?

    /**
     * Returns a view of the portion of this set whose elements are
     * strictly less than `toElement`.  The returned set is
     * backed by this set, so changes in the returned set are
     * reflected in this set, and vice-versa.  The returned set
     * supports all optional set operations that this set supports.
     *
     *
     * The returned set will throw an `IllegalArgumentException`
     * on an attempt to insert an element outside its range.
     *
     * @param toElement high endpoint (exclusive) of the returned set
     * @return a view of the portion of this set whose elements are strictly
     * less than `toElement`
     * @throws ClassCastException if `toElement` is not compatible
     * with this set's comparator (or, if the set has no comparator,
     * if `toElement` does not implement [Comparable]).
     * Implementations may, but are not required to, throw this
     * exception if `toElement` cannot be compared to elements
     * currently in the set.
     * @throws NullPointerException if `toElement` is null and
     * this set does not permit null elements
     * @throws IllegalArgumentException if this set itself has a
     * restricted range, and `toElement` lies outside the
     * bounds of the range
     */
    fun headSet(toElement: E): SortedSet<E>?

    /**
     * Returns a view of the portion of this set whose elements are
     * greater than or equal to `fromElement`.  The returned
     * set is backed by this set, so changes in the returned set are
     * reflected in this set, and vice-versa.  The returned set
     * supports all optional set operations that this set supports.
     *
     *
     * The returned set will throw an `IllegalArgumentException`
     * on an attempt to insert an element outside its range.
     *
     * @param fromElement low endpoint (inclusive) of the returned set
     * @return a view of the portion of this set whose elements are greater
     * than or equal to `fromElement`
     * @throws ClassCastException if `fromElement` is not compatible
     * with this set's comparator (or, if the set has no comparator,
     * if `fromElement` does not implement [Comparable]).
     * Implementations may, but are not required to, throw this
     * exception if `fromElement` cannot be compared to elements
     * currently in the set.
     * @throws NullPointerException if `fromElement` is null
     * and this set does not permit null elements
     * @throws IllegalArgumentException if this set itself has a
     * restricted range, and `fromElement` lies outside the
     * bounds of the range
     */
    fun tailSet(fromElement: E): SortedSet<E>?

    /**
     * Returns the first (lowest) element currently in this set.
     *
     * @return the first (lowest) element currently in this set
     * @throws NoSuchElementException if this set is empty
     */
    fun first(): E

    /**
     * Returns the last (highest) element currently in this set.
     *
     * @return the last (highest) element currently in this set
     * @throws NoSuchElementException if this set is empty
     */
    fun last(): E

}