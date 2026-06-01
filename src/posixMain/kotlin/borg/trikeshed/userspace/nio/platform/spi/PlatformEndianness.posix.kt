@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.nio.ByteOrder
import kotlinx.cinterop.*

// Runtime probe: write 0x01020304 to native int via CPointer<IntVar>,
// read first byte via CPointer<ByteVar> view of the same memory.
// On little-endian: byte[0] = 0x04. On big-endian: byte[0] = 0x01.

internal actual fun platformNativeByteOrder(): ByteOrder = memScoped {
    val bytes = allocArray<ByteVar>(4)
    val intPtr = bytes.reinterpret<IntVar>()
    intPtr.pointed.value = 0x01020304
    if (bytes.pointed.value == 0x04.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
}
