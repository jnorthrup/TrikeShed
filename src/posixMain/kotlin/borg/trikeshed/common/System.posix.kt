package borg.trikeshed.common

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv as posix_getenv

@OptIn(ExperimentalForeignApi::class)
actual object System {
    actual fun getenv(name: String, string: String): String? = posix_getenv(name)?.toKString()

    actual val homedir: String by lazy {
        getenv("HOME", "false")
            ?: getenv("USERPROFILE", "false")
            ?: run {
                val homedrive = getenv("HOMEDRIVE", "false")
                val homepath = getenv("HOMEPATH", "false")
                if (homedrive != null && homepath != null) "$homedrive$homepath" else null
            }
            ?: error("Failed to determine home directory")
    }
}
