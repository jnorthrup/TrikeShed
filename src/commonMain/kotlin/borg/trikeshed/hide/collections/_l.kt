package borg.trikeshed.common.collections

object _l {
    operator fun <T> get(vararg t: T): List<T> = listOf(*t)
}

