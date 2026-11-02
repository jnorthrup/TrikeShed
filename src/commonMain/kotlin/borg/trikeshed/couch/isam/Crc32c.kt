package borg.trikeshed.couch.isam

/**
 * The CRC32c (Castagnoli, as SCTP and iSCSI carry it) of bytes[offset, offset + length). Each target uses the fastest
 * form it has: the CPU's instruction on posix (SSE4.2 on x86_64, the CRC extension on arm64, checked at run time), the
 * JVM's intrinsic CRC32C, and the table ([Crc32C.software]) elsewhere. Every form gives the same value (Crc32cTest).
 */
expect fun crc32c(bytes: ByteArray, offset: Int, length: Int): Int
