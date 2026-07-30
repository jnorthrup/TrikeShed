@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public open class FileSystemException : borg.trikeshed.userspace.nio.IOException {
    private val _file: String?
    private val _other: String?
    private val _reason: String?

    constructor(p0: String?) : super(p0) {
        this._file = p0
        this._other = null
        this._reason = null
    }

    constructor(p0: String?, p1: String?, p2: String?) : super(listOfNotNull(p0, p1, p2).joinToString(" -> ")) {
        this._file = p0
        this._other = p1
        this._reason = p2
    }

    fun getFile(): String? = _file
    fun getOtherFile(): String? = _other
    fun getReason(): String? = _reason
}
