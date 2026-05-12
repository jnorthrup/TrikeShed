@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.charset

public open class IllegalCharsetNameException : IllegalArgumentException {
    public constructor(p0: String) : super(p0)

    public fun getCharsetName():CharSequence= message ?: ""
}
