@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.charset

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect open class UnmappableCharacterException : borg.trikeshed.userspace.nio.charset.CharacterCodingException {
    constructor(p0: Int)
    fun getInputLength(): Int
    fun getMessage(): String
}
