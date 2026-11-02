@file:OptIn(ExperimentalNativeApi::class, ExperimentalNativeApi::class)

package borg.trikeshed.lib

import kotlin.experimental.ExperimentalNativeApi

/**fun assert(value: Boolean)
(JVM source) (Native source)
For JVM
Throws an AssertionError if the value is false and runtime assertions have been enabled on the JVM using the -ea JVM option.

For Native
Throws an AssertionError if the value is false and runtime assertions have been enabled during compilation.*/
@OptIn(ExperimentalNativeApi::class)
actual fun assert(value: Boolean): Unit =kotlin.assert(value)
actual inline fun   assert(value: Boolean, lazyMessage: () -> Any) { kotlin.assert(value, lazyMessage) }