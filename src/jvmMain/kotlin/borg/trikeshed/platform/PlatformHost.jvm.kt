package borg.trikeshed.platform

actual fun loadPlatformHost(): PlatformHost = object : PlatformHost {
    override val clock: PlatformClock = object : PlatformClock {
        override fun nowMillis(): Long = java.lang.System.currentTimeMillis()
        override fun monotonicNanos(): Long = java.lang.System.nanoTime()
    }
    override val processors: Int = Runtime.getRuntime().availableProcessors()
    override val resources: Any? get() = JvmResourceSource
}
