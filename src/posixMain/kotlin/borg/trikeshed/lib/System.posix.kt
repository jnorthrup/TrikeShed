@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.lib
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv as posix_getenv

actual object System {
    actual fun getenv(name: CharSequence, defaultVal: CharSequence?): CharSequence? {
        val ptr = posix_getenv(name.toString())
        return ptr?.toKString() ?: defaultVal
    }
    actual val homedir: CharSequence = run {
        val ptr = posix_getenv("HOME")
        ptr?.toKString() ?: "/tmp"
    }
}
