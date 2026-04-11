package borg.literbike.ccek.keymux

import platform.posix.getenv
import kotlinx.cinterop.toKString

actual object Platform {
    actual fun getenv(key: String): String? {
        val value = getenv(key)
        return value?.toKString()
    }
}
