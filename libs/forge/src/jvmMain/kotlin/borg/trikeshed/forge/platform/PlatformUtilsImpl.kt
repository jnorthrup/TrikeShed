package borg.trikeshed.forge.platform

import kotlinx.coroutines.Dispatchers
import java.util.UUID

actual fun providePlatformUtils(): PlatformUtils = object : PlatformUtils {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
    override fun randomUuid(): String = UUID.randomUUID().toString()
    override val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
    override fun toPlatformByteArray(str: String): ByteArray = str.toByteArray()
    override fun toPlatformString(bytes: ByteArray): String = String(bytes)
}