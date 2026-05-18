@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package borg.trikeshed.lib

import platform.posix.getenv
import kotlinx.cinterop.toKString

actual object System {
    actual val homedir: String
        get() = getenv("HOME")?.toKString() ?: ""

    actual fun getenv(name: String, defaultVal: String?): String? =
        getenv(name)?.toKString() ?: defaultVal
}
