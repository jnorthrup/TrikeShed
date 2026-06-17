package borg.trikeshed.forge.platform

import kotlin.random.Random

/**
 * Multiplatform platform utilities.
 * Provides platform-agnostic APIs for time, UUID generation, and coroutine dispatchers.
 */
expect object PlatformUtils {
    /**
     * Current time in milliseconds since epoch.
     */
    fun currentTimeMillis(): Long

    /**
     * Generate a random UUID string.
     * Uses platform-appropriate randomness (not cryptographically secure for simple IDs).
     */
    fun randomUuid(): String

    /**
     * Default coroutine dispatcher for IO-bound work.
     * On JVM: Dispatchers.IO
     * On JS/WASM: Dispatchers.Default (single-threaded, but works for async)
     */
    val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher
}