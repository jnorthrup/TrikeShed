package borg.trikeshed.lib

/**fun assert(value: Boolean)
(JVM source) (Native source)
For JVM
Throws an AssertionError if the value is false and runtime assertions have been enabled on the JVM using the -ea JVM option.

For Native
Throws an AssertionError if the value is false and runtime assertions have been enabled during compilation.*/
actual fun assert(value: Boolean) =kotlin.assert(value)
actual inline fun   assert(value: Boolean, lazyMessage: () -> Any) { kotlin.assert(value, lazyMessage) }