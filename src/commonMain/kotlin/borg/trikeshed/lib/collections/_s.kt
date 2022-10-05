package borg.trikeshed.lib.collections

//
// /**
// * missing stdlib set operator https://github.com/Kotlin/KEEP/pull/112
// */
object _s {
    inline operator fun <T> get(vararg t: T): Set<T> = setOf(*t)
}
