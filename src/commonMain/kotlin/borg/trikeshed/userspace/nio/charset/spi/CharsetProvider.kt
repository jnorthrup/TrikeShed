@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.charset.spi

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect abstract class CharsetProvider {
    protected constructor()
    fun charsets(): java.util.Iterator<borg.trikeshed.userspace.nio.charset.Charset>
    fun charsetForName(p0: String): borg.trikeshed.userspace.nio.charset.Charset
}
