package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.nio.ByteOrder

internal actual fun platformNativeByteOrder(): ByteOrder =
    if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
