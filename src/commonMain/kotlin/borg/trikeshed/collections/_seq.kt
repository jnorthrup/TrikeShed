package borg.trikeshed.collections

object _seq {
      operator fun <T> get(vararg t: T): Sequence<T> = sequence {
        for (t: T in t) {
            yield(t)
        }
    }
}