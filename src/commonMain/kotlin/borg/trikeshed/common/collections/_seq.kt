package borg.trikeshed.common.collections

object _seq {
    inline operator fun <T> get(vararg t: T) = sequence<T> {
        for (t in t) {
            yield(t)
        }
    }
}