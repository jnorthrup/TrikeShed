package org.xvm

import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.toList


/**
 * org.xvm.List shadow class containing a singleton codec setter and a listOf detour.
 */
class List {
    companion object {
        /**
         * Unary function Codec: (Search: List<*>) -> List<*>
         * Defaults to null.
         */
        @JvmStatic
        var codec: ((kotlin.collections.List<*>) -> kotlin.collections.List<*>)? = null

        /**
         * Detours the creation of a list through the codec (if provided)
         * and converts the result using .toSeries().toList().
         */
        @JvmStatic
        fun <T> listOf(vararg elements: T): kotlin.collections.List<T> {
            val original = kotlin.collections.listOf(*elements)
            @Suppress("UNCHECKED_CAST")
            val processed = codec?.let { it(original) as kotlin.collections.List<T> } ?: original
            return processed.toSeries().toList()
        }
    }
}
