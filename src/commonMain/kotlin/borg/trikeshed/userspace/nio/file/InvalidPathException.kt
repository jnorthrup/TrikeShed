@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public open class InvalidPathException : IllegalArgumentException {
    constructor(p0: String, p1: String, p2: Int) : super("$p1 at index $p2 in $p0")
    constructor(p0: String, p1: String) : super("$p1 in $p0")
    fun getInput(): String = TODO("NIO common stub")
    fun getReason(): String = TODO("NIO common stub")
    fun getIndex(): Int = TODO("NIO common stub")
}
