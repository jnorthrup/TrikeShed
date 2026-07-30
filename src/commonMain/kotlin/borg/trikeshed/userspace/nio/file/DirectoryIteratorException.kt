@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public class DirectoryIteratorException : ConcurrentModificationException {
    private val _cause: borg.trikeshed.userspace.nio.IOException

    constructor(p0: borg.trikeshed.userspace.nio.IOException) : super(p0) {
        this._cause = p0
    }

    fun getCause(): borg.trikeshed.userspace.nio.IOException = _cause
}
