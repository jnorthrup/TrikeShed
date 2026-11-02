package borg.trikeshed.tilting.leaf

import borg.trikeshed.tilting.lz4.Lz4Block
import borg.trikeshed.tilting.lz4.Lz4BlockOps
import borg.trikeshed.tilting.lz4.Lz4Exception
import borg.trikeshed.tilting.lz4.Lz4Kotlin
import borg.trikeshed.tilting.lz4.Xxh32
import borg.trikeshed.tilting.lz4.lz4Decompress
import borg.trikeshed.tilting.lzo.Lzo1x
import borg.trikeshed.tilting.lzo.LzoBlockOps
import borg.trikeshed.tilting.lzo.LzoException
import borg.trikeshed.tilting.lzo.LzoKotlin
import borg.trikeshed.tilting.lzo.lzo1xDecompress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class LeafCodecTest {
    private val text =
        "SELECT p.id, p.body, p.created_at, u.username FROM posts p JOIN users u ON u.id = p.user_id ".encodeToByteArray()

    private fun inputs(): List<ByteArray> {
        val rnd = Random(20261014)
        val lengths = listOf(1, 3, 4, 5, 12, 13, 17, 18, 19, 20, 21, 22, 31, 255, 256, 270, 1000, 4095, 4096, 4097,
            49151, 49152, 49153, 65535, 65536, 65537)
        return buildList {
            add(ByteArray(0))
            add(ByteArray(4096))
            add(ByteArray(4096) { rnd.nextInt().toByte() })
            add(ByteArray(4096) { text[it % text.size] })
            add(ByteArray(200_000) { if (it % 1000 < 900) 0 else rnd.nextInt().toByte() })
            add(ByteArray(140_000) { text[(it * 7) % text.size] })
            for (n in lengths) add(ByteArray(n) { i -> if (i % 9 < 5) text[i % text.size] else rnd.nextInt(4).toByte() })
        }
    }

    private fun lz4Impls(): List<Lz4BlockOps> = listOfNotNull(Lz4Kotlin(), LeafSpecialization.lz4())
    private fun lzoImpls(): List<LzoBlockOps> = listOfNotNull(LzoKotlin(), LeafSpecialization.lzo())

    @Test
    fun lz4BlocksRoundTripAndCrossDecode() {
        val impls = lz4Impls()
        for (input in inputs()) for (enc in impls) {
            val c = ByteArray(Lz4Block.bound(input.size))
            val n = enc.compress(input, 0, input.size, c, 0)
            for (dec in impls) {
                val out = ByteArray(input.size)
                val label = "${enc.name} -> ${dec.name}, ${input.size} bytes"
                assertEquals(input.size, dec.decompress(c, 0, n, out, 0, out.size), label)
                assertContentEquals(input, out, label)
            }
        }
    }

    @Test
    fun lzoBlocksRoundTripAndCrossDecode() {
        val impls = lzoImpls()
        for (input in inputs()) for (enc in impls) {
            val c = ByteArray(Lzo1x.worstCompress(input.size))
            val n = enc.compress(input, 0, input.size, c, 0)
            for (dec in impls) {
                val out = ByteArray(input.size)
                val label = "${enc.name} -> ${dec.name}, ${input.size} bytes"
                assertEquals(input.size, dec.decompress(c, 0, n, out, 0, out.size), label)
                assertContentEquals(input, out, label)
            }
        }
    }

    @Test
    fun leafCodecsRoundTripAndCrossDecode() {
        val families = listOf(
            listOf<LeafCodec>(RawLeafCodec),
            lz4Impls().map { Lz4LeafCodec(it) },
            lzoImpls().map { LzoLeafCodec(it) },
        )
        for (family in families) for (input in inputs()) for (enc in family) {
            val encoded = enc.encode(input, 0, input.size)
            for (dec in family) {
                val out = ByteArray(input.size)
                dec.decodeInto(encoded, out)
                assertContentEquals(input, out, "${enc.name} -> ${dec.name}, ${input.size} bytes")
            }
        }
    }

    @Test
    fun corruptInputsFailOnlyWithCodecExceptions() {
        val rnd = Random(7)
        val input = ByteArray(4096) { i -> if (i % 9 < 5) text[i % text.size] else rnd.nextInt(4).toByte() }
        for (codec in listOf<LeafCodec>(Lz4LeafCodec(Lz4Kotlin()), LzoLeafCodec(LzoKotlin()))) {
            val encoded = codec.encode(input, 0, input.size)
            val out = ByteArray(input.size)
            fun attempt(bytes: ByteArray) {
                try { codec.decodeInto(bytes, out) } catch (_: Lz4Exception) { } catch (_: LzoException) { }
            }
            for (cut in 0 until encoded.size) attempt(encoded.copyOf(cut))
            repeat(3000) {
                val bad = encoded.copyOf()
                bad[rnd.nextInt(bad.size)] = rnd.nextInt().toByte()
                attempt(bad)
            }
        }
        val out = ByteArray(4096)
        repeat(3000) {
            val garbage = ByteArray(1 + rnd.nextInt(64)) { rnd.nextInt().toByte() }
            try { lz4Decompress(garbage, 0, garbage.size, out, 0, out.size) } catch (_: Lz4Exception) { }
            try { lzo1xDecompress(garbage, 0, garbage.size, out, 0, out.size) } catch (_: LzoException) { }
        }
    }

    @Test
    fun xxh32KnownValues() {
        assertEquals(0x02CC5D05, Xxh32.hash(ByteArray(0)))
        assertEquals(0x32D153FF, Xxh32.hash("abc".encodeToByteArray()))
    }
}
