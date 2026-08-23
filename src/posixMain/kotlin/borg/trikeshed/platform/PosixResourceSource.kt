package borg.trikeshed.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

/** POSIX natives: the source tree (there is no classpath); the baked bundle covers packaged binaries. */
@OptIn(ExperimentalForeignApi::class)
object PosixResourceSource : ResourceSource {
    override fun bytes(path: String): ByteArray? {
        val file = fopen("src/commonMain/resources/" + resourceKey(path), "rb") ?: return null
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file).toInt()
            fseek(file, 0, SEEK_SET)
            if (size <= 0) return ByteArray(0)
            val bytes = ByteArray(size)
            bytes.usePinned { pinned -> fread(pinned.addressOf(0), size.toULong(), 1u, file) }
            return bytes
        } finally { fclose(file) }
    }
}
