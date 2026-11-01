package borg.trikeshed.couch.isam

/** JavaScript has no CRC32c instruction to reach: the table. */
actual fun crc32c(bytes: ByteArray, offset: Int, length: Int): Int = Crc32C.software(bytes, offset, length)
