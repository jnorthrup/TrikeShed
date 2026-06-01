package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.nio.ByteOrder

// Runtime probe: write 0x01020304 as Int32Array, read first byte via Uint8Array view.
// On little-endian: byte[0] = 0x04. On big-endian: byte[0] = 0x01.

@Suppress("TopLevelPropertyNaming")
private val ENDIAN_PROBE: dynamic = js("(new Uint8Array(new Int32Array([0x01020304]).buffer))[0]")

internal actual fun platformNativeByteOrder(): ByteOrder =
    if (ENDIAN_PROBE == 0x04) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
