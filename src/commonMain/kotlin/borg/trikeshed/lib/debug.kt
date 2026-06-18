package borg.trikeshed.lib

/**fun assert(value: Boolean)
(JVM source) (Native source)
For JVM
Throws an AssertionError if the value is false and runtime assertions have been enabled on the JVM using the -ea JVM option.

For Native
Throws an AssertionError if the value is false and runtime assertions have been enabled during compilation.*/

expect fun assert(value: Boolean)

@Throws(AssertionError::class)
expect fun assert(value: Boolean, lazyMessage: () -> Any)

//var forceDebug = env// stopgap measure until i figure out how to do native -ea
val debugging: Boolean =
    try {
        assert(false) { "debugging" }
//        forceDebug
        false
    } catch (e: AssertionError) {
        true
    }

//exactly like .also
fun <T> T.debug(block: (T) -> Unit): T {
    if (debugging) block(this)
    return this
}

fun <T> T.log(block: (T) -> String): T {
    println(block(this))
    return this
}

infix fun <T> T.d(other: T): T {
    println(other)
    return this
}

/** iff debugging is enabled, print the result of the block */
fun logDebug(block: () -> String) {
    if (debugging) println("debug: " + block())
}

fun logNone(block: () -> String) {}

fun logAlways(block: () -> String) {//import as logDebug
    println("info: " + block())
}