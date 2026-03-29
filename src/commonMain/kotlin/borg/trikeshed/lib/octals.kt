package borg.trikeshed.lib

import kotlin.math.pow

fun Int.toOctal(): Int {
    var n = this
    var octalNumber = 0
    var i = 0
    while (n != 0) {
        octalNumber += (n % 8 * 10.0.pow(i.toDouble())).toInt()
        n /= 8
        ++i
    }
    return octalNumber
}

fun Int.fromOctal(): Int {
    var n = this
    var decimalNumber = 0
    var i = 0
    while (n != 0) {
        decimalNumber += (n % 10 * 8.0.pow(i.toDouble())).toInt()
        n /= 10
        ++i
    }
    return decimalNumber
}

val Int.fromBinary: Int get() = this.toLong().fromBinary
val Long.fromBinary: Int get() {
    var x = this
    var r = 0
    var i = 0
    while (x != 0L) {
        r += (x and 1L).toInt() shl i
        x = x shr 1
        i++
    }
    return r
}

object binaryPrefix {
    infix fun invoke(p1: Long): Int = p1.fromBinary
    infix fun invoke(p1: Int): Int = p1.fromBinary
}

object octalPrefix {
    infix fun invoke(p1: Int): Int = p1.fromOctal()
}
