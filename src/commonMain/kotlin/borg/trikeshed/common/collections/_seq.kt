package borg.trikeshed.common.collections

object _seq {
      operator fun <T> get(vararg arrayOfTs: T): Sequence<T> = sequence { for (t: T in arrayOfTs) yield(t) }
}