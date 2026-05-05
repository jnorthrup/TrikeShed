@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels.spi

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect abstract class AbstractSelectionKey : borg.trikeshed.userspace.nio.channels.SelectionKey {
    protected constructor()
    fun isValid(): Boolean
    fun cancel(): Unit
}
