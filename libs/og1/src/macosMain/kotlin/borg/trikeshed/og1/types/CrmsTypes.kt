package borg.trikeshed.og1.types

import kotlin.random.Random
import kotlin.time.TimeSource

actual fun generateOg1Id(): String =
    Random.nextBytes(4).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

actual fun currentTimeSeconds(): Double {
    val ts = TimeSource.Monotonic
    return ts.markNow().elapsedNow().inWholeMilliseconds.toDouble() / 1000.0
}
