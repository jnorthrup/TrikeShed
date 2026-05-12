@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.charset

public open class UnsupportedCharsetException : IllegalArgumentException {
    public constructor(p0: CharSequence) : super(p0.toString())

    public fun getCharsetName(): CharSequence = message ?: ""
}
