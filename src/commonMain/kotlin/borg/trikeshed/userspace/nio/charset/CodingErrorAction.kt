@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.charset

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public class CodingErrorAction private constructor(private val name: String) {
    override fun toString(): String = name
    companion object {
        val IGNORE: borg.trikeshed.userspace.nio.charset.CodingErrorAction = CodingErrorAction("IGNORE")
        val REPLACE: borg.trikeshed.userspace.nio.charset.CodingErrorAction = CodingErrorAction("REPLACE")
        val REPORT: borg.trikeshed.userspace.nio.charset.CodingErrorAction = CodingErrorAction("REPORT")
    }
}
