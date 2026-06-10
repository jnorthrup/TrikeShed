@file:Suppress("UNCHECKED_CAST", "NonAsciiCharacters")

package borg.trikeshed.miniduck

/** Identity passthrough for List → List (merge compat). */
fun <T> seriesOf(list: List<T>): List<T> = list

/** Identity passthrough with cast (merge compat). */
fun seriesOfAny(list: List<Any?>): List<Any?> = list
