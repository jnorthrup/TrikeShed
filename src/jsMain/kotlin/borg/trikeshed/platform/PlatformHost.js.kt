package borg.trikeshed.platform
import kotlin.time.Clock.System
actual fun loadPlatformHost(): PlatformHost = object : PlatformHost {
    override val clock: PlatformClock = object : PlatformClock {
        override fun nowMillis(): Long = kotlin.js.Date.now().toLong()
        override fun monotonicNanos(): Long = System.now().toEpochMilliseconds() * 1000000L
    }
    override val processors: Int = 1
}
