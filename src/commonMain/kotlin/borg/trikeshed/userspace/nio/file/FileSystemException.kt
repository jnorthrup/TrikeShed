@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public open class FileSystemException : borg.trikeshed.userspace.nio.IOException {
    constructor(p0: String) : super(p0)
    constructor(p0: String, p1: String, p2: String) : super(listOfNotNull(p0, p1, p2).joinToString(" -> "))
    fun getFile():CharSequence= TODO("NIO common stub")
    fun getOtherFile():CharSequence= TODO("NIO common stub")
    fun getReason():CharSequence= TODO("NIO common stub")
}
