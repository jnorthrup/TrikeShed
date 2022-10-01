package borg.trikeshed.lib


object s_  {
      operator fun <  T> get(vararg t: T): Series<T> = t.size j t::get
}


object _l {
    inline operator fun <T> get(vararg t: T): List<T> = listOf(*t)
}
//

object _a {
    inline operator fun get(vararg t: Boolean): BooleanArray = t
    inline operator fun get(vararg t: Byte): ByteArray = t
    inline operator fun get(vararg t: UByte): UByteArray = t
    inline operator fun get(vararg t: Char): CharArray = t
    inline operator fun get(vararg t: Short): ShortArray = t
    inline operator fun get(vararg t: UShort): UShortArray = t
    inline operator fun get(vararg t: Int): IntArray = t
    inline operator fun get(vararg t: UInt): UIntArray = t
    inline operator fun get(vararg t: Long): LongArray = t
    inline operator fun get(vararg t: ULong): ULongArray = t
    inline operator fun get(vararg t: Float): FloatArray = t
    inline operator fun get(vararg t: Double): DoubleArray = t
    inline operator fun <T> get(vararg t: T): Array<out T> =t
}
//
///**
// * missing stdlib set operator https://github.com/Kotlin/KEEP/pull/112
// */
object _s {
    inline operator fun <T> get(vararg t: T): Set<T> = setOf(*t)
}
//
///**
// * missing stdlib map convenience operator
// */
object _m {
    operator fun <K, V, P : Pair<K, V>> get(p: List<P>): Map<K, V> = (p).toMap()
    operator fun <K, V, P : Pair<K, V>> get(vararg p: P): Map<K, V> = mapOf(*p)

}