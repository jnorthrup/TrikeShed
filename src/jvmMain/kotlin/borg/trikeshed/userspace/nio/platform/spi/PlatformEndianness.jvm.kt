package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.nio.ByteOrder
import java.lang.foreign.*

internal actual fun platformNativeByteOrder(): ByteOrder =
    if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

val JAVA_LONG_UNALIGNED_LITTLE_ENDIAN: ValueLayout.OfLong =
    ValueLayout.JAVA_LONG_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN)

// MemorySegment.ofArray does not escape, so escape analysis scalar-replaces it: Graal at every site, C2 at most.
actual fun ByteArray.littleEndianGetLongAt(index: Int): Long =
    MemorySegment.ofArray(this).get(JAVA_LONG_UNALIGNED_LITTLE_ENDIAN, index.toLong())

actual fun ByteArray.littleEndianSetLongAt(index: Int, value: Long) =
    MemorySegment.ofArray(this).set(JAVA_LONG_UNALIGNED_LITTLE_ENDIAN, index.toLong(), value)
