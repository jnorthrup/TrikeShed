package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.nio.ByteOrder

// WebAssembly linear memory is little-endian by specification.
internal actual fun platformNativeByteOrder(): ByteOrder = ByteOrder.LITTLE_ENDIAN

actual fun ByteArray.littleEndianGetLongAt(index: Int): Long = littleEndianGetLongAtCommonImpl(index)

actual fun ByteArray.littleEndianSetLongAt(index: Int, value: Long) = littleEndianSetLongAtCommonImpl(index, value)
