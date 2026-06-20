package borg.trikeshed.usersignals.platform

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlin.math.pow
import kotlin.math.round
import platform.posix.gettimeofday
import platform.posix.timeval

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun currentTimeMillis(): Long = memScoped {
    val tv = alloc<timeval>()
    gettimeofday(tv.ptr, null)
    tv.tv_sec * 1000L + tv.tv_usec.toLong() / 1000L
}

actual fun formatDouble(value: Double, decimals: Int): String {
    val factor = 10.0.pow(decimals)
    val rounded = round(value * factor) / factor
    val raw = rounded.toString()
    val dot = raw.indexOf('.')
    return when {
        decimals <= 0 -> raw.substringBefore('.')
        dot < 0 -> raw + "." + "0".repeat(decimals)
        else -> raw + "0".repeat((decimals - (raw.length - dot - 1)).coerceAtLeast(0))
    }
}
