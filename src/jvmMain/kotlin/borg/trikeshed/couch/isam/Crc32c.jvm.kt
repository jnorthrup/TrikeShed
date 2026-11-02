package borg.trikeshed.couch.isam

import java.util.zip.CRC32C

/** The JVM's CRC32C, which HotSpot and Graal compile to the CPU's CRC32c instruction. */
actual fun crc32c(bytes: ByteArray, offset: Int, length: Int): Int {
    val crc = CRC32C()
    crc.update(bytes, offset, length)
    return crc.value.toInt()
}
