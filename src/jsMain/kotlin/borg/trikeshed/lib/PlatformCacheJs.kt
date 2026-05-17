@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.lib

import borg.trikeshed.userspace.nio.platform.spi.CacheTopology

/**
 * JS/WASM: no cache introspection available.
 */
actual val platformCacheTopology: CacheTopology = CacheTopology.UNKNOWN
