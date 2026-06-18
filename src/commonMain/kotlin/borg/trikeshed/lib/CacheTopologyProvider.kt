@file:Suppress("NonAsciiCharacters")
package borg.trikeshed.lib

import borg.trikeshed.cursor.CacheTopology
import borg.trikeshed.cursor.ReificationContext
import borg.trikeshed.userspace.nio.platform.spi.CacheTopology as SpiCacheTopology
import kotlin.coroutines.CoroutineContext

/**
 * CCEK: provides [borg.trikeshed.userspace.nio.platform.spi.CacheTopology] from the platform.
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
            override val topology: SpiCacheTopology = SpiCacheTopology.UNKNOWN
        }
    }
    override val key: CoroutineContext.Key<*> get() = Key

    val topology: SpiCacheTopology
}

/**
 * Resolve [ReificationContext] from the coroutine context.
 * Falls back to unlimited depth when no [CacheTopologyProvider] is registered.
 */
fun CoroutineContext.reificationContext(): ReificationContext {
    val t = this[CacheTopologyProvider.Key]?.topology
    val cursorTopology = CacheTopology(
        l1DataBytes = t?.l1DataBytes,
        l2Bytes = t?.l2Bytes,
        l3Bytes = t?.l3Bytes,
    )
    return ReificationContext.from(cursorTopology)
}
