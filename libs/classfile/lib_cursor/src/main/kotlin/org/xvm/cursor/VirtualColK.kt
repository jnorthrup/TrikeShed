package org.xvm.cursor

import borg.trikeshed.lib.FacetedRow
import borg.trikeshed.lib.OpK

/**
 * VirtualColK handles the synthetic/computed real-time projections 
 * on the Cursor blackboard.
 */
sealed class VirtualColK<out R> : OpK<R>() {
    data class Computed<T>(
        val name: CharSequence,
        val compute: (FacetedRow<*>) -> T
    ) : VirtualColK<T>()
}
