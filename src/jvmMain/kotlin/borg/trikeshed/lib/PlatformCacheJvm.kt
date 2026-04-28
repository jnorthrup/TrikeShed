@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.lib

/**
 * JVM: no portable cache-line introspection without Unsafe or JNI.
 * Callers should supply a known [CacheTopology] manually when needed.
 */
actual val platformCacheTopology: CacheTopology = CacheTopology.UNKNOWN
