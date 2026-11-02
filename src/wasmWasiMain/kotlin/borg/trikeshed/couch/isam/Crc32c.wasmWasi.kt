package borg.trikeshed.couch.isam

/** WebAssembly has no CRC32c instruction: the table. */
actual fun crc32c(bytes: ByteArray, offset: Int, length: Int): Int = Crc32C.software(bytes, offset, length)
