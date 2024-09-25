package borg.trikeshed.common.collections.associative

import CSeries
import SeriesSortedSet
import borg.trikeshed.lib.EmptySeries
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

object SortedSets {
    operator fun <T : Comparable<T>> invoke(series: CSeries<T>): SeriesSortedSet<T> =
        series j Comparator { a, b -> a.compareTo(b) }

    operator fun <T : Comparable<T>> invoke(series: CSeries<T>, comparator: Comparator<T>): SeriesSortedSet<T> =
        series j comparator

    fun <T : Comparable<T>> empty(): SeriesSortedSet<T> = (EmptySeries as Series<T>) j (naturalOrder() as Comparator<T>)