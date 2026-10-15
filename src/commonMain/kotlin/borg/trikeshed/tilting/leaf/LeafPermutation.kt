package borg.trikeshed.tilting.leaf

import borg.trikeshed.tilting.lz4.Lz4Kotlin
import borg.trikeshed.tilting.lzo.LzoKotlin
import kotlin.math.round
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * The permutation {raw, lz4, lzo} x {kotlin, this platform's [LeafSpecialization]} over [corpus], concatenated
 * [pageSize] leaves. Every leaf round-trips through every implementation of its format, cross-decoding included;
 * then encode and decode throughput, each the median of 5 timed passes after 2 untimed ones; then each codec as a
 * [LeafCache]'s storage encoding under one skewed access sequence. One [report] line per cross-check, per codec and
 * per cache, each starting with "PERMUTATION <platform>".
 */
fun leafPermutation(corpus: ByteArray, pageSize: Int = 4096, report: (String) -> Unit) {
    val platform = LeafSpecialization.platform
    val leaves = List(corpus.size / pageSize) { corpus.copyOfRange(it * pageSize, (it + 1) * pageSize) }
    val rawBytes = leaves.size.toLong() * pageSize
    val formats = listOf(
        listOf<LeafCodec>(RawLeafCodec),
        listOfNotNull<LeafCodec>(Lz4LeafCodec(Lz4Kotlin()), LeafSpecialization.lz4()?.let { Lz4LeafCodec(it) }),
        listOfNotNull<LeafCodec>(LzoLeafCodec(LzoKotlin()), LeafSpecialization.lzo()?.let { LzoLeafCodec(it) }),
    )
    val dst = ByteArray(pageSize)
    var sink = 0L
    for (family in formats) {
        val encoded = family.map { codec -> leaves.map { codec.encode(it, 0, pageSize) } }
        for ((i, enc) in family.withIndex()) for (dec in family) {
            for ((k, leaf) in leaves.withIndex()) {
                dec.decodeInto(encoded[i][k], dst)
                check(leaf.contentEquals(dst)) { "${enc.name} -> ${dec.name}: leaf $k differs" }
            }
            if (enc !== dec) report("PERMUTATION $platform cross ${enc.name} -> ${dec.name}: ${leaves.size} leaves identical")
        }
        for ((i, codec) in family.withIndex()) {
            val bytes = encoded[i].sumOf { it.size.toLong() }
            // Each pass reads a byte of every output at a data-dependent index, so no compiler can drop the copy a raw
            // encode returns (native-image did: 1.5 TB/s).
            val encodeNs = medianNs {
                for (leaf in leaves) {
                    val e = codec.encode(leaf, 0, pageSize)
                    sink += e.size + e[(sink and 0x7fffffff).toInt() % e.size]
                }
            }
            val decodeNs = medianNs { for (e in encoded[i]) { codec.decodeInto(e, dst); sink += dst[0] } }
            report("PERMUTATION $platform ${codec.name.padEnd(13)} leaves=${leaves.size} ratio=${fixed(bytes.toDouble() / rawBytes, 3)}" +
                " encode=${fixed(rawBytes * 1e3 / encodeNs, 0)} MB/s decode=${fixed(rawBytes * 1e3 / decodeNs, 0)} MB/s")
        }
    }
    // Leaf caching: each codec as the cache's storage encoding, capacity a quarter of the corpus's raw bytes, one skewed
    // access sequence for every codec and platform (kotlin.random.Random(seed) is the same generator everywhere). A miss
    // loads the leaf from the corpus and puts it (encode); a hit decodes. Every timed pass starts from an empty cache.
    val capacity = rawBytes / 4
    val sequence = Random(20261014).let { rnd -> IntArray(CACHE_ACCESSES) { val u = rnd.nextDouble(); (leaves.size * u * u).toInt() } }
    for (family in formats) for (codec in family) {
        var cache = LeafCache(codec, capacity)
        val ns = medianNs {
            cache = LeafCache(codec, capacity)
            for (k in sequence) if (!cache.get(k.toLong(), dst)) {
                cache.put(k.toLong(), leaves[k])
                leaves[k].copyInto(dst)
            }
            sink += cache.hits + dst[(sink and 0x7fffffff).toInt() % pageSize]
        }
        report("PERMUTATION $platform cache ${codec.name.padEnd(13)} capacity=${capacity / 1024}KiB accesses=$CACHE_ACCESSES" +
            " hit=${fixed(cache.hits * 100.0 / CACHE_ACCESSES, 1)}% entries=${cache.entries} held=${cache.heldBytes / 1024}KiB" +
            " ${fixed(ns.toDouble() / CACHE_ACCESSES, 0)} ns/access")
    }
    report("PERMUTATION $platform done [$sink]")
}

/** Accesses per timed pass of the leaf-cache permutation (wasi runs lz4/lzo encode at 10-30 MB/s: ~2 min per run). */
const val CACHE_ACCESSES: Int = 50_000

private inline fun medianNs(pass: () -> Unit): Long {
    repeat(2) { pass() }
    val times = LongArray(5) {
        val mark = TimeSource.Monotonic.markNow()
        pass()
        mark.elapsedNow().inWholeNanoseconds
    }
    times.sort()
    return times[2]
}

private fun fixed(v: Double, digits: Int): String {
    var scale = 1L
    repeat(digits) { scale *= 10 }
    val r = round(v * scale).toLong()
    return if (digits == 0) r.toString() else "${r / scale}.${(r % scale).toString().padStart(digits, '0')}"
}
