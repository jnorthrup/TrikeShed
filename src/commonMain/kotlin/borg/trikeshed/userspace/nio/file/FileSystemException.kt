@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public open class FileSystemException : java.io.IOException {
    constructor(p0: String)
    constructor(p0: String, p1: String, p2: String)
    fun getFile(): String
    fun getOtherFile(): String
    fun getReason(): String
    fun getMessage(): String
}
