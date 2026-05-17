@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import kotlinx.cinterop.*
import platform.posix.getenv as posix_getenv

class PosixSystemOperations : SystemOperations {

    override fun getenv(name: String, defaultVal: String?): String? {
        val ptr = posix_getenv(name); return ptr?.toKString() ?: defaultVal
    }

    override fun getProperty(name: String, defaultVal: String?): String? = defaultVal

    override val homedir: String
        get() { val ptr = posix_getenv("HOME"); return ptr?.toKString() ?: "/tmp" }
}
