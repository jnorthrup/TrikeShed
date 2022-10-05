package borg.trikeshed.lib.collections

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

object s_ {
    operator fun <T> get(vararg t: T): Series<T> = t.size j t::get
}