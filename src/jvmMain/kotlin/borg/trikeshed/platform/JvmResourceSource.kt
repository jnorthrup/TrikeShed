package borg.trikeshed.platform

import java.io.File

/** JVM: the classloader (jar / classes dir) first, then the source tree for un-processed runs. */
object JvmResourceSource : ResourceSource {
    override fun bytes(path: String): ByteArray? {
        val key = resourceKey(path)
        JvmResourceSource::class.java.classLoader.getResourceAsStream(key)?.use { return it.readBytes() }
        val src = File("src/commonMain/resources/$key")
        return if (src.isFile) src.readBytes() else null
    }
}
