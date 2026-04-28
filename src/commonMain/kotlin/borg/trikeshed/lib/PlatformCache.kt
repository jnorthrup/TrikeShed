@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.lib

/**
 * Platform-specific cache topology — provided by each source set.
 *
 * Defaults to [CacheTopology.UNKNOWN] when no platform detection is available.
 * Override in `jvmMain` / `posixMain` / `jsMain` / `wasmJsMain` etc. to supply
 * actual cache sizes derived from the OS or hardware introspection.
 */
expect val platformCacheTopology: CacheTopology
