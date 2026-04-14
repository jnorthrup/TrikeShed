package borg.trikeshed.common.collections

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.view

object _l {
    operator fun <T> get(vararg t: T): List<T> = listOf(*t)
    operator fun <T> get(head: T, middle: Series<T>, tail: T): List<T> = listOf(head) + middle.view + listOf(tail)
}
