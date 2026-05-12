package borg.trikeshed.userspace.nio.file.spi

import kotlinx.cinterop.toKString
import platform.posix.getenv as posix_getenv

class LinuxSystemOperations : SystemOperations {
    override fun getenv(name: CharSequence, defaultVal: CharSequence?): CharSequence? =
        posix_getenv(name)?.toKString() ?: defaultVal

    override val homedir: CharSequence
        get() = posix_getenv("HOME")?.toKString() ?: "/tmp"
}
