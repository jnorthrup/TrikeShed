package borg.trikeshed.platform
import kotlin.time.Clock
actual fun loadPlatformHost(): PlatformHost = object : PlatformHost {
    override val clock: PlatformClock = object : PlatformClock {
        override fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
        override fun monotonicNanos(): Long = Clock.System.now().toEpochMilliseconds() * 1000000L
    }
    override val processors: Int = 1
}
