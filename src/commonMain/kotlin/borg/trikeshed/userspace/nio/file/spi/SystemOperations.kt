package borg.trikeshed.userspace.nio.file.spi

import kotlin.coroutines.CoroutineContext

/**
 * Platform system/env operations — replaces [borg.trikeshed.lib.System] expect object.
 */
interface SystemOperations : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<SystemOperations>
    override val key: CoroutineContext.Key<*> get() = Key

    fun getenv(name: String, defaultVal: String? = null): String?
    val homedir: CharSequence
}
