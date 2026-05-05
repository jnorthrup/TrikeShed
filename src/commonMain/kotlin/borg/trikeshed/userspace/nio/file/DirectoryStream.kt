@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect interface DirectoryStream<T> : java.io.Closeable, Iterable<T> {
    fun iterator(): java.util.Iterator<T>

    expect interface Filter<T> {
        fun accept(p0: T): Boolean
    }
}
