@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public open class InvalidPathException : IllegalArgumentException {
    private val input: String
    private val reason: String
    private val index: Int

    constructor(p0: String, p1: String, p2: Int) : super("$p1 at index $p2 in $p0") {
        this.input = p0
        this.reason = p1
        this.index = p2
    }

    constructor(p0: String, p1: String) : super("$p1 in $p0") {
        this.input = p0
        this.reason = p1
        this.index = -1
    }

    fun getInput(): String = input
    fun getReason(): String = reason
    fun getIndex(): Int = index
}
