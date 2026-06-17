package borg.trikeshed.forge.platform

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Multiplatform platform utilities interface.
 * Provides platform-agnostic APIs for time, UUID generation, and coroutine dispatchers.
 */
interface PlatformUtils {
    fun currentTimeMillis(): Long
    fun randomUuid(): String
    val ioDispatcher: CoroutineDispatcher
    fun toPlatformByteArray(str: String): ByteArray
    fun toPlatformString(bytes: ByteArray): String
}

/** Platform-specific implementation provider */
expect fun providePlatformUtils(): PlatformUtils

/** Platform-specific implementation provider */
val platformUtils: PlatformUtils = providePlatformUtils()