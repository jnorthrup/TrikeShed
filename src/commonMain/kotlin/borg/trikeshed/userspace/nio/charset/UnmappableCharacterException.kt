@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.charset

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public open class UnmappableCharacterException(message: String = "Unmappable character") : borg.trikeshed.userspace.nio.charset.CharacterCodingException(message) {
    private var _inputLength: Int = -1
    constructor(p0: Int) : this("Unmappable character length=$p0") {
        _inputLength = p0
    }
    fun getInputLength(): Int = _inputLength
}
