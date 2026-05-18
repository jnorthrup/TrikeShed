package borg.trikeshed.userspace.nio.platform.spi

import kotlin.coroutines.CoroutineContext

/**
 * Platform system/env operations — replaces [borg.trikeshed.lib.System] expect object.
 */
interface SystemOperations : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<SystemOperations> {
        private var defaultProvider: SystemOperations? = null

        fun register(provider: SystemOperations) {
            defaultProvider = provider
        }

        val default: SystemOperations
            get() = defaultProvider ?: loadDefaultSystemOperations().also { defaultProvider = it }
    }

    override val key: CoroutineContext.Key<*> get() = Key

    fun getenv(name: String, defaultVal: String? = null): String?
    fun getProperty(name: String, defaultVal: String? = null): String?
    val homedir: String
}

/**
 * Platform-specific loader for the default SystemOperations SPI.
 */
expect fun loadDefaultSystemOperations(): SystemOperations
