@file:OptIn(ExperimentalUnsignedTypes::class)

package borg.trikeshed.tilting.zran

import borg.trikeshed.lib.leftIdentity

class Point(
    var output: ULong,
    var input: ULong,
    win: UByteArray,
    val winsize: UShort = win.size.toUShort(),
    windowSupplier: () -> UByteArray = win.leftIdentity,
) {
    val window: UByteArray by lazy(windowSupplier)
}