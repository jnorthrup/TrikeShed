@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.media

import borg.trikeshed.lib.*
import borg.trikeshed.lib.cascade.*

/*
 * ISO BMFF (MP4/MOV/HEIF) as a cascade key: the file is a tree of boxes `[size:u32][type:4cc]`
 * (largesize:u64 follows when size==1; size==0 ⇒ to end of parent; 'uuid' ⇒ +16 bytes usertype),
 * big-endian. Each box is one Emit whose key is its type path from the root and whose value is
 * `Stats.of(own bytes)` — a leaf's whole size, a container's header only — so prefix reductions
 * never double-count: `trie[prefix].sum` is exactly the bytes under that prefix, `trie.leaf(key)`
 * is the boxes of one type path. A truncated tail ends the walk; what was seen is emitted.
 */

/** Boxes whose payload is child boxes. `meta` is a FullBox: 4 bytes version/flags precede the children. */
private val CONTAINERS =
    "moov trak mdia minf stbl dinf edts udta mvex moof traf mfra tref meta sinf schi ipro iprp ipco".split(' ').toSet()

private fun ByteArray.u32(i: Int): Long = (0 until 4).fold(0L) { a, k -> a shl 8 or (this[i + k].toLong() and 0xff) }

/** One Emit per box, pre-order, containers included (valued at their header bytes). */
fun ByteArray.boxes(): Series<Emit<String, Stats>> {
    val out = ArrayList<Emit<String, Stats>>()
    fun walk(from: Int, to: Int, path: List<String>) {
        var at = from
        while (to - at >= 8) {
            val type = decodeToString(at + 4, at + 8)
            var hdr = 8
            var size = u32(at)
            if (size == 1L) { if (to - at < 16) return; size = u32(at + 8) shl 32 or u32(at + 12); hdr = 16 }
            if (size == 0L) size = (to - at).toLong()
            if (type == "uuid") hdr += 16
            if (size < hdr || size > to - at) return
            val end = at + size.toInt()
            val p = path + type
            val kids = type in CONTAINERS
            out += p.toSeries() j Stats.of((if (kids) hdr.toLong() else size).toDouble())
            if (kids) walk(at + hdr + if (type == "meta") 4 else 0, end, p)
            at = end
        }
    }
    walk(0, size, emptyList())
    return out.toSeries()
}

/** `trie[["moov","trak"]].sum` = bytes under every trak, `leaf(...)` = the trak boxes themselves; `level(1)` = top-level boxes; `unseen(p)` = first sighting. */
fun ByteArray.boxTrie(): Trie<String, Stats> = Trie<String, Stats>(Stats).also { t -> boxes().toList().forEach { t.add(it.a, it.b) } }

/** The box types in walk order — the file's shape signature at box granularity. */
fun ByteArray.boxKey(): Key<String> = boxes() α { it.a.last() }
