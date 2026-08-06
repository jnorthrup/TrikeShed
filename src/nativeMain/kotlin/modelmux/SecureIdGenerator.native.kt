package modelmux

import platform.posix.read
import platform.posix.open
import platform.posix.close
import platform.posix.O_RDONLY
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.convert

actual val defaultSecureIdGenerator: SecureIdGenerator = object : SecureIdGenerator {
    override fun generateHexId(prefix: String, byteLength: Int): String {
        val bytes = ByteArray(byteLength)
        val fd = open("/dev/urandom", O_RDONLY)
        if (fd < 0) error("Failed to open /dev/urandom")
        try {
            bytes.usePinned { pinned ->
                val readBytes = read(fd, pinned.addressOf(0), byteLength.convert())
                if (readBytes < 0) error("Failed to read from /dev/urandom")
            }
        } finally {
            close(fd)
        }
        val hex = bytes.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        return "$prefix-$hex"
    }
}
