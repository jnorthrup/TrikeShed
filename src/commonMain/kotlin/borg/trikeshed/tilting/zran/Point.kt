package borg.trikeshed.tilting.zran

import borg.trikeshed.lib.`↺`

class Point(
    var output: ULong,
    var input: ULong,
    win: UByteArray,
    val winsize: UShort = win.size.toUShort(),
    windowSupplier: () -> UByteArray = win.`↺`,
) {
    val window: UByteArray by lazy(windowSupplier)
}