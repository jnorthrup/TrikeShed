@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.common
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr

//kotlinx.cinterop.CValuesRef<platform.posix.fenv_t>?
actual object System {
    actual fun getenv(name: String, defaultVal: String?): String?   = (getenv(name)?:defaultVal?.cstr) as String?
    actual val homedir: String = getenv("HOME") ?: "/tmp"
}
