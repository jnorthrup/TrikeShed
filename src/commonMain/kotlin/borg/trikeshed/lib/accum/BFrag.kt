package borg.trikeshed.lib.accum

import borg.trikeshed.lib.*

typealias BFrag = Join< /**endexclusive range*/ Twin<Int>, ByteArray>

val BFrag.len: Int
    get() {
        val (bounds) = this
        val (beg, end) = bounds
        return end - beg
    }

fun BFrag.isEmpty(): Boolean = len == 0

/**slice is 0-based as if beg was 0;*/
fun BFrag.slice(atInclusive: Int, untilExclusive: Int = a.b): BFrag = a.run { a + atInclusive j untilExclusive } j b

/*
as in ByteBuffer.flip after a read
flip is 0-based as if beg was 0;
*/
fun BFrag.flip(endExclusive: Int): BFrag {
    if (endExclusive == len) return this
    val (beg) = a
    val newEnd = beg + endExclusive
    val buf = b
    return beg j newEnd j buf
}

fun BFrag.split1(lit: Byte): Twin<BFrag?> {
    val (beg, end) = a
    var x = beg
    while (x < end && b[x] != lit) x++

    val ret: Twin<BFrag?> = when (x) {
        end -> null j this
        end - 1 -> this j null
        else -> {
            x++
            x -= beg
            val line = flip(x)
            val tail = slice(x)
            line j tail
        }
    }
    assert(ret.a != null || ret.b != null)
    return ret
}

fun BFrag.copyInto(ret: ByteArray, offset: Int) {
    val (bounds, buf) = this
    val (beg, end) = bounds
    buf.copyInto(ret, offset, beg, end)
}

val BFrag.byteSeries: ByteSeries
    get() = ByteSeries(b, a.a, a.b)
