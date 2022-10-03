package borg.trikeshed.lib

object _l {
    inline operator fun <T> get(vararg t: T): List<T> = listOf(*t)
}