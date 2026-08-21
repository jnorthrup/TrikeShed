package borg.trikeshed.platform

import kotlin.coroutines.CoroutineContext

interface PlatformClock {
    fun nowMillis(): Long
    fun monotonicNanos(): Long
}

interface PlatformHost : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<PlatformHost> {
        private var defaultProvider: PlatformHost? = null

        fun register(provider: PlatformHost) {
            defaultProvider = provider
        }

        val default: PlatformHost
            get() = defaultProvider ?: loadPlatformHost().also { defaultProvider = it }
    }

    override val key: CoroutineContext.Key<*> get() = Key

    val clock: PlatformClock
    val processors: Int

    // TODO interfaces for later tasks (documented, not wired)
    val fs: Any? get() = null
    val digest: Any? get() = null
    val random: Any? get() = null
    val lock: Any? get() = null
    val endianness: Any? get() = null
    val cacheTopology: Any? get() = null
    val resources: Any? get() = null
    val shell: Any? get() = null
    val subVm: Any? get() = null
}

expect fun loadPlatformHost(): PlatformHost
