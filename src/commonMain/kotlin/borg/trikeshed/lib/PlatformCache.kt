package borg.trikeshed.lib

import borg.trikeshed.userspace.nio.platform.spi.CacheTopology

/**
 * Platform-specific cache topology — provided by each source set.
 *
 * Forwards to [borg.trikeshed.userspace.nio.platform.spi.platformCacheTopology].
 */
val platformCacheTopology: CacheTopology =
    borg.trikeshed.userspace.nio.platform.spi.platformCacheTopology