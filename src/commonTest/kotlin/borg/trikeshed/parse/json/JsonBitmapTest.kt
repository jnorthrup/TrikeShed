@file:OptIn(ExperimentalUnsignedTypes::class)

package borg.trikeshed.parse.json

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class JsonBitmapTest {
    @Test
    fun categoryNibbles() {
        val input = ubyteArrayOf(
            0x7bu, 0x5bu, 0x7du, 0x5du, 0x2cu, 0x3au, 0x22u,
            0x5cu, 0x20u, 0x00u, 0x80u, 0xffu, 0x61u,
        )
        assertContentEquals(
            ubyteArrayOf(0x11u, 0x22u, 0x30u, 0x48u, 0x00u, 0xccu, 0x00u),
            JsonBitmap.encode(input),
        )
    }

    @Test
    fun byteCategoryDomain() {
        val encoded = JsonBitmap.encode(UByteArray(256) { it.toUByte() })
        assertEquals(128, encoded.size)
        for (byte in 0..255) {
            val nibble = (encoded[byte / 2].toInt() shr if (byte % 2 == 0) 4 else 0) and 15
            val structural = when (byte) {
                '{'.code, '['.code -> 1
                '}'.code, ']'.code -> 2
                ','.code -> 3
                else -> 0
            }
            val lexical = when (byte) {
                '"'.code -> 1
                '\\'.code -> 2
                in 128..255 -> 3
                else -> 0
            }
            assertEquals(structural, nibble and 3, "structural byte=$byte")
            assertEquals(lexical, nibble shr 2, "lexical byte=$byte")
        }
    }

    @Test
    fun structuralBitPairs() {
        val chunks = arrayOf(ubyteArrayOf(0x11u), ubyteArrayOf(0x22u, 0x30u))
        assertDecoded(chunks, 6u, intArrayOf(1, 1, 2, 2, 3, 0))
        assertContentEquals(ubyteArrayOf(0x5au), chunks[0])
        assertContentEquals(ubyteArrayOf(0xc0u, 0x00u), chunks[1])
    }

    @Test
    fun emptyAndZeroLength() {
        assertContentEquals(ubyteArrayOf(), JsonBitmap.encode(ubyteArrayOf()))
        val empty = emptyArray<UByteArray>()
        assertSame(empty, JsonBitmap.decode(empty))
        val emptyChunks = arrayOf(ubyteArrayOf(), ubyteArrayOf())
        assertSame(emptyChunks, JsonBitmap.decode(emptyChunks))
        assertDecoded(arrayOf(ubyteArrayOf(), ubyteArrayOf(0xffu, 0xffu)), 0u, intArrayOf())
    }

    @Test
    fun capacityValidationPrecedesMutation() {
        assertFailsWith<IllegalArgumentException> { JsonBitmap.decode(emptyArray(), 1u) }
        for (size in uintArrayOf(5u, UInt.MAX_VALUE)) {
            val chunks = arrayOf(ubyteArrayOf(), ubyteArrayOf(0x11u), ubyteArrayOf(0x23u))
            val before = Array(chunks.size) { chunks[it].copyOf() }
            assertFailsWith<IllegalArgumentException>("inputSize=$size") {
                JsonBitmap.decode(chunks, size)
            }
            for (index in chunks.indices) assertContentEquals(before[index], chunks[index])
        }
    }

    @Test
    fun bitmapBoundariesAndPadding() {
        val events = intArrayOf(1, 2, 3, 0)
        for (size in intArrayOf(0, 1, 2, 3, 4, 5, 7, 8, 9, 15, 16, 17, 31, 32, 33)) {
            val input = "{],x".repeat(9).take(size).encodeToByteArray().toUByteArray()
            val encoded = JsonBitmap.encode(input)
            assertEquals((size + 1) / 2, encoded.size, "source bytes=$size")
            if (size % 2 != 0) assertEquals(0, encoded.last().toInt() and 15)
            assertDecoded(arrayOf(encoded), size.toUInt(), IntArray(size) { events[it % 4] })
        }

        // The caller's length excludes both a nonzero padding nibble and extra capacity.
        assertDecoded(
            arrayOf(ubyteArrayOf(0x13u), ubyteArrayOf(), ubyteArrayOf(0xffu, 0xffu)),
            1u,
            intArrayOf(1),
        )
        val defaultLength = arrayOf(ubyteArrayOf(0x12u), ubyteArrayOf(), ubyteArrayOf(0x30u))
        JsonBitmap.decode(defaultLength)
        assertContentEquals(ubyteArrayOf(0x6cu), defaultLength[0])
        assertContentEquals(ubyteArrayOf(0u), defaultLength[2])
    }

    @Test
    fun stringTokensAcrossEveryEncodedByteSplit() {
        val documents = arrayOf(
            """{"x":[true,false,null,-1.25e+2],"s":"{[,:]}"}""",
            """["\u0022\u005c\n\r\t\b\f\/",{"":""}]""",
            """{"é":"水😀{,}","x":["😀é水",{}]}""",
        )
        for (document in documents) {
            // Both byte alignments put every interior UTF-8 boundary at a chunk edge.
            for (prefix in arrayOf("", " ")) {
                val source = prefix + document
                assertEverySplit(source.encodeToByteArray().toUByteArray(), stringTokenReference(source))
            }
        }
    }

    @Test
    fun escapeRunParityAcrossChunks() {
        for (run in 0..8) {
            val literal = "\"" + "\\".repeat(run) + if (run % 2 == 0) "\"" else "\"{,}\""
            val source = "[$literal,{}]"
            assertEverySplit(source.encodeToByteArray().toUByteArray(), stringTokenReference(source))
        }
    }

    @Test
    fun generatedJsonGrammar() {
        val values = arrayOf(
            "null", "true", "false", "-0.125e+2", "\"\"", "\"é水😀\"",
            "\"quote: \\\" slash: \\\\ structural: {[,]}\"", "\"\\u0022\\n\\t\\/\"",
        )
        for (sample in 0..7) {
            val source = StringBuilder()
            val expected = mutableListOf<Int>()
            // A literal contributes only zero events; punctuation declares its event.
            // This reference records the grammar during emission instead of rescanning it.
            fun fragment(text: String, event: Int = 0) {
                source.append(text)
                repeat(text.encodeToByteArray().size) { expected.add(event) }
            }
            fragment("{", 1)
            fragment("\"k$sample\":")
            fragment("[", 1)
            repeat(sample + 1) {
                if (it > 0) fragment(",", 3)
                fragment(values[(sample * 3 + it * 5) % values.size])
            }
            fragment("]", 2)
            fragment(",", 3)
            fragment("\"nested\":")
            fragment("{", 1)
            fragment("\"x\":")
            fragment(values[sample])
            fragment("}", 2)
            fragment("}", 2)
            assertEverySplit(source.toString().encodeToByteArray().toUByteArray(), expected.toIntArray())
        }
    }

    @Test
    fun lexicalClassificationDoesNotRequireValidJson() {
        val source = "\\{[,]}\"unterminated{,[]"
        val expected = IntArray(source.length)
        intArrayOf(0, 1, 1, 3, 2, 2).copyInto(expected)
        assertEverySplit(source.encodeToByteArray().toUByteArray(), expected)

        // UTF-8 bytes outside strings do not act as either quotes or backslashes.
        assertEverySplit(
            "é,水[😀]".encodeToByteArray().toUByteArray(),
            intArrayOf(0, 0, 3, 0, 0, 0, 1, 0, 0, 0, 0, 2),
        )
    }

    fun assertEverySplit(input: UByteArray, expected: IntArray) {
        val encoded = JsonBitmap.encode(input)
        assertDecoded(arrayOf(encoded.copyOf()), input.size.toUInt(), expected, "contiguous bitmap")
        for (split in 0..encoded.size) {
            assertDecoded(
                arrayOf(
                    ubyteArrayOf(), encoded.copyOfRange(0, split), ubyteArrayOf(),
                    encoded.copyOfRange(split, encoded.size), ubyteArrayOf(),
                ),
                input.size.toUInt(),
                expected,
                "source bytes=${input.size}, encoded split=$split",
            )
        }
        assertDecoded(
            Array(encoded.size * 2 + 1) { if (it % 2 == 0) ubyteArrayOf() else ubyteArrayOf(encoded[it / 2]) },
            input.size.toUInt(),
            expected,
            "source bytes=${input.size}, single-byte chunks",
        )
    }

    fun assertDecoded(chunks: Array<UByteArray>, inputSize: UInt, expected: IntArray, context: String = "") {
        val storage = Array(chunks.size) { chunks[it].asByteArray() }
        val output = JsonBitmap.decode(chunks, inputSize)
        assertSame(chunks, output, context)
        var slot = 0
        for (chunk in output.indices) {
            assertSame(storage[chunk], output[chunk].asByteArray(), context)
            for (byte in output[chunk]) {
                for (shift in intArrayOf(6, 4, 2, 0)) {
                    assertEquals(expected.getOrElse(slot) { 0 }, (byte.toInt() shr shift) and 3, "$context slot=$slot")
                    slot++
                }
            }
        }
        assertEquals(inputSize.toInt(), expected.size, context)
    }

    fun stringTokenReference(source: String): IntArray {
        // Map each UTF-8 byte to one Char so regex ranges retain byte offsets.
        val byteText = source.encodeToByteArray().joinToString("") { (it.toInt() and 255).toChar().toString() }
        val unquoted = Regex("\"(?:[^\"\\\\]|\\\\.)*\"").replace(byteText) { " ".repeat(it.value.length) }
        return IntArray(unquoted.length) {
            when (unquoted[it]) {
                '{', '[' -> 1
                '}', ']' -> 2
                ',' -> 3
                else -> 0
            }
        }
    }
}
