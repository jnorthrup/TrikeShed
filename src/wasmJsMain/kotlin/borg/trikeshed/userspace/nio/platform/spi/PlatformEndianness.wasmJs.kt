package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.nio.ByteOrder

// Runtime probe via JS host: write 0x01020304 as Int32Array, read first byte.
// WASM linear memory is LE by spec, but verify via the JS host TypedArray.
// On little-endian: byte[0] = 0x04. On big-endian: byte[0] = 0x01.

@JsFun("() => (new Uint8Array(new Int32Array([0x01020304]).buffer))[0]")
private external fun endianProbe(): Int

internal actual fun platformNativeByteOrder(): ByteOrder =
    if (endianProbe() == 0x04) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
