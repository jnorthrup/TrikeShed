package borg.trikeshed.common.collections

//
// /**
// * missing stdlib set operator https://github.com/Kotlin/KEEP/pull/112
// */
object _s {
      operator fun <T> get(vararg t: T): Set<T> = setOf(*t)
}
