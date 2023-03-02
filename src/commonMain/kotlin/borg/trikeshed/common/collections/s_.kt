package borg.trikeshed.common.collections

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**Series macro object*/
object s_ {
    /**Series factorymethod */
    operator fun <T> get(vararg t: T): Series<T> = t.size j t::get
}
