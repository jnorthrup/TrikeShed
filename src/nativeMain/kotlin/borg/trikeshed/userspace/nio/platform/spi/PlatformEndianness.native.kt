@file:OptIn(ExperimentalNativeApi::class)

package borg.trikeshed.userspace.nio.platform.spi

import kotlin.experimental.*

// getLongAt/setLongAt move the word in the target's byte order, and every Kotlin/Native target is
// little-endian; PlatformEndiannessTest pins that order on each native test target. A per-call
// Platform.isLittleEndian test is not folded: its object-initialization check stays in the decode loops.
actual fun ByteArray.littleEndianGetLongAt(index: Int): Long = getLongAt(index)

actual fun ByteArray.littleEndianSetLongAt(index: Int, value: Long) = setLongAt(index, value)
