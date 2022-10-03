package borg.trikeshed.lib

object s_ {
    operator fun <T> get(vararg t: T): Series<T> = t.size j t::get
}