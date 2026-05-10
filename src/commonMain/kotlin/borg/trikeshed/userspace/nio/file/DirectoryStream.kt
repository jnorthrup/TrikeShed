@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

import borg.trikeshed.lib.Closeable

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface DirectoryStream<T> : Closeable, Iterable<T> {
    override fun iterator(): kotlin.collections.Iterator<T> = TODO("NIO common stub")

    public interface Filter<T> {
        fun accept(p0: T): Boolean = TODO("NIO common stub")
    }
}
