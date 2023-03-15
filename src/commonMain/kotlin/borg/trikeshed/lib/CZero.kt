package borg.trikeshed.lib

object CZero {
    val Byte.nz: Boolean get() = 0 != this.toInt()
    val Short.nz: Boolean get() = 0 != this.toInt()
    val Char.nz: Boolean get() = 0 != this.code
    val Int.nz: Boolean get() = 0 != this
    val Long.nz: Boolean get() = 0L != this
    val UByte.nz: Boolean get() = 0 != this.toInt()
    val UShort.nz: Boolean get() = 0 != this.toInt()
    val UInt.nz: Boolean get() = 0U != this
    val ULong.nz: Boolean get() = 0UL != this
    val Byte.z: Boolean get() = 0 == this.toInt()
    val Short.z: Boolean get() = 0 == this.toInt()
    val Char.z: Boolean get() = 0 == this.code
    val Int.z: Boolean get() = 0 == this
    val Long.z: Boolean get() = 0L == this
    val UByte.z: Boolean get() = 0 == this.toInt()
    val UShort.z: Boolean get() = 0 == this.toInt()
    val UInt.z: Boolean get() = 0U == this
    val ULong.z: Boolean get() = 0UL == this
    val Boolean.bool: Int get() = if (this) 1 else 0
}