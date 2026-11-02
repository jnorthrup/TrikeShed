package borg.trikeshed.couch.isam

/** mingwX64 does not compile posix_spawn.def, where the instruction form lives: the table. */
actual fun crc32c(bytes: ByteArray, offset: Int, length: Int): Int = Crc32C.software(bytes, offset, length)
