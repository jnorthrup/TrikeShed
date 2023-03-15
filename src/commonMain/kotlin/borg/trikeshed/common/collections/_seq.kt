package borg.trikeshed.common.collections

object _seq {
      operator fun <T> get(vararg t: T) = sequence<T> {
        for (t: T in t) {
            yield(t)
        }
    }
}