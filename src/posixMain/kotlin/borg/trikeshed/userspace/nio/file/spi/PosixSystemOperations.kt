@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.file.spi

import kotlinx.cinterop.*
import platform.posix.getenv as posix_getenv

class PosixSystemOperations : SystemOperations {

    override fun getenv(name: String, defaultVal: String?): String? {
        val ptr = posix_getenv(name); return ptr?.toKString() ?: defaultVal
    }

    override val homedir: CharSequence
        get() { val ptr = posix_getenv("HOME"); return ptr?.toKString() ?: "/tmp" }
}
