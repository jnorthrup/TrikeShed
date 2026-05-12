package borg.trikeshed.miniduck.keys

import borg.trikeshed.lib.Join

// ═══════════════════════════════════════════════════════════════════
// Typed keys — name encodes the left type, generic B encodes the
// right chain.  Human-readable, zero-allocation, Comparable.
//
//   lKey<B>     Join<Long, B>       — long key pointing to B
//   iKey<B>     Join<Int, B>
//   sKey<B>     Join<CharSequence, B>
//   dKey<B>     Join<Double, B>
//   fKey<B>     Join<Float, B>
//   bKey<B>     Join<Byte, B>
//   cKey<B>     Join<Char, B>
//
// The class name tells you what .a is.  The type parameter tells you
// what .b is.  No counting .b.b.b.a chains.
// ═══════════════════════════════════════════════════════════════════

 inline class lKey<B>(val join: Join<Long, B>) : Comparable<lKey<B>> {
    override fun compareTo(other: lKey<B>): Int = join.a.compareTo(other.join.a)
}

 inline class iKey<B>(val join: Join<Int, B>) : Comparable<iKey<B>> {
    override fun compareTo(other: iKey<B>): Int = join.a.compareTo(other.join.a)
}

 inline class sKey<B>(val join: Join<CharSequence, B>) : Comparable<sKey<B>> {
    override fun compareTo(other: sKey<B>): Int = join.a.toString().compareTo(other.join.a.toString())
}

 inline class dKey<B>(val join: Join<Double, B>) : Comparable<dKey<B>> {
    override fun compareTo(other: dKey<B>): Int = join.a.compareTo(other.join.a)
}

 inline class fKey<B>(val join: Join<Float, B>) : Comparable<fKey<B>> {
    override fun compareTo(other: fKey<B>): Int = join.a.compareTo(other.join.a)
}

 inline class bKey<B>(val join: Join<Byte, B>) : Comparable<bKey<B>> {
    override fun compareTo(other: bKey<B>): Int = join.a.compareTo(other.join.a)
}

 inline class cKey<B>(val join: Join<Char, B>) : Comparable<cKey<B>> {
    override fun compareTo(other: cKey<B>): Int = join.a.compareTo(other.join.a)
}
