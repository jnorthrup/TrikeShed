@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public open class InvalidPathException : IllegalArgumentException {
    constructor(p0: CharSequence, p1: CharSequence, p2: Int) : super("$p1 at index $p2 in $p0")
    constructor(p0: CharSequence, p1: CharSequence) : super("$p1 in $p0")
    fun getInput(): CharSequence = TODO("NIO common stub")
    fun getReason(): CharSequence = TODO("NIO common stub")
    fun getIndex(): Int = TODO("NIO common stub")
}
