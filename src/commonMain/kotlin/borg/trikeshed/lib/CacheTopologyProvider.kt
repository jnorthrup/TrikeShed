@file:Suppress("NonAsciiCharacters")
package borg.trikeshed.lib

import kotlin.coroutines.CoroutineContext

/**
 * CCEK: provides [CacheTopology] from the platform.
 *
 * Replaces `expect val platformCacheTopology: CacheTopology`.
 * Register via NioSupervisor or direct context composition.
 *
 * The [Combine] layer reads this to compute [ReificationContext],
 * which caps lazy Series nesting depth to fit L1 cache.
 */
interface CacheTopologyProvider : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<CacheTopologyProvider> {
        val UNKNOWN: CacheTopologyProvider = object : CacheTopologyProvider {
            override val topology: CacheTopology = CacheTopology.UNKNOWN
        }
    }
    override val key: CoroutineContext.Key<*> get() = Key

    val topology: CacheTopology
}

/**
 * Resolve [ReificationContext] from the coroutine context.
 * Falls back to unlimited depth when no [CacheTopologyProvider] is registered.
 */
fun CoroutineContext.reificationContext(): ReificationContext {
    val t = this[CacheTopologyProvider.Key]?.topology ?: CacheTopology.UNKNOWN
    return ReificationContext.from(t)
}
