@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.nio.platform.spi.CacheTopology

/**
 * WASM: no cache introspection available.
 */
actual val platformCacheTopology: CacheTopology = CacheTopology.UNKNOWN
