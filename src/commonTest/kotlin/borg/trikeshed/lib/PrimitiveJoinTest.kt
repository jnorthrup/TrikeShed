package borg.trikeshed.lib

import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class PrimitiveJoinTest {
    private val samples: List<List<Any>> = listOf(
        listOf(false, true),
        listOf(Byte.MIN_VALUE, (-1).toByte(), 0.toByte(), 1.toByte(), Byte.MAX_VALUE),
        listOf(Short.MIN_VALUE, (-1).toShort(), 0.toShort(), 1.toShort(), Short.MAX_VALUE),
        listOf('\u0000', '\u0001', '\u7FFF', '\u8000', '\uFFFF'),
        listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE),
        listOf(-0.0f, 0.0f, -1.0f, 1.0f, Float.MIN_VALUE, Float.MAX_VALUE,
            Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.fromBits(0x7FC12345), Float.fromBits(0xFFC12345.toInt())),
    )

    private val layouts: List<List<KClass<*>>> = listOf(
        listOf(TwBoolean::class, BooleanByteJoin::class, BooleanShortJoin::class, BooleanCharJoin::class, BooleanIntJoin::class, BooleanFloatJoin::class),
        listOf(ByteBooleanJoin::class, Twyte::class, ByteShortJoin::class, ByteCharJoin::class, ByteIntJoin::class, ByteFloatJoin::class),
        listOf(ShortBooleanJoin::class, ShortByteJoin::class, Twhort::class, ShortCharJoin::class, ShortIntJoin::class, ShortFloatJoin::class),
        listOf(CharBooleanJoin::class, CharByteJoin::class, CharShortJoin::class, Twhar::class, CharIntJoin::class, CharFloatJoin::class),
        listOf(IntBooleanJoin::class, IntByteJoin::class, IntShortJoin::class, IntCharJoin::class, TwInt::class, IntFloatJoin::class),
        listOf(FloatBooleanJoin::class, FloatByteJoin::class, FloatShortJoin::class, FloatCharJoin::class, FloatIntJoin::class, TwFloat::class),
    )

    private fun sameValue(expected: Any?, actual: Any?) {
        assertEquals(expected?.let { it::class }, actual?.let { it::class })
        if (expected is Float) assertEquals(expected.toRawBits(), (actual as Float).toRawBits())
        else assertEquals(expected, actual)
    }

    private fun checkJoin(a: Any?, b: Any?, actual: Join<*, *>) {
        sameValue(a, actual.a)
        sameValue(b, actual.b)
        val (left, right) = actual
        sameValue(a, left)
        sameValue(b, right)
        sameValue(a, actual.pair.first)
        sameValue(b, actual.pair.second)
    }

    private fun <A, B> erasedJoin(a: A, b: B): Join<A, B> = a j b

    @Test
    fun allOrderedPrimitivePairsPackThroughGenericConstruction() {
        for ((i, left) in samples.withIndex()) for ((k, right) in samples.withIndex()) {
            for (a in left) for (b in right) {
                val result = erasedJoin(a, b)
                assertEquals(layouts[i][k], result::class)
                checkJoin(a, b, result)
            }
        }
    }

    @Test
    fun typedOverloadsRetainConcreteValueClasses() {
        val booleanBoolean: TwBoolean = true j true
        sameValue(true, booleanBoolean.first)
        sameValue(true, booleanBoolean.second)
        val booleanByte: BooleanByteJoin = true j (-117).toByte()
        sameValue(true, booleanByte.first)
        sameValue((-117).toByte(), booleanByte.second)
        val booleanShort: BooleanShortJoin = true j (-30001).toShort()
        sameValue(true, booleanShort.first)
        sameValue((-30001).toShort(), booleanShort.second)
        val booleanChar: BooleanCharJoin = true j '\uFFFF'
        sameValue(true, booleanChar.first)
        sameValue('\uFFFF', booleanChar.second)
        val booleanInt: BooleanIntJoin = true j Int.MIN_VALUE
        sameValue(true, booleanInt.first)
        sameValue(Int.MIN_VALUE, booleanInt.second)
        val booleanFloat: BooleanFloatJoin = true j Float.fromBits(0xFFC12345.toInt())
        sameValue(true, booleanFloat.first)
        sameValue(Float.fromBits(0xFFC12345.toInt()), booleanFloat.second)
        val byteBoolean: ByteBooleanJoin = (-117).toByte() j true
        sameValue((-117).toByte(), byteBoolean.first)
        sameValue(true, byteBoolean.second)
        val byteByte: Twyte = (-117).toByte() j (-117).toByte()
        sameValue((-117).toByte(), byteByte.first)
        sameValue((-117).toByte(), byteByte.second)
        val byteShort: ByteShortJoin = (-117).toByte() j (-30001).toShort()
        sameValue((-117).toByte(), byteShort.first)
        sameValue((-30001).toShort(), byteShort.second)
        val byteChar: ByteCharJoin = (-117).toByte() j '\uFFFF'
        sameValue((-117).toByte(), byteChar.first)
        sameValue('\uFFFF', byteChar.second)
        val byteInt: ByteIntJoin = (-117).toByte() j Int.MIN_VALUE
        sameValue((-117).toByte(), byteInt.first)
        sameValue(Int.MIN_VALUE, byteInt.second)
        val byteFloat: ByteFloatJoin = (-117).toByte() j Float.fromBits(0xFFC12345.toInt())
        sameValue((-117).toByte(), byteFloat.first)
        sameValue(Float.fromBits(0xFFC12345.toInt()), byteFloat.second)
        val shortBoolean: ShortBooleanJoin = (-30001).toShort() j true
        sameValue((-30001).toShort(), shortBoolean.first)
        sameValue(true, shortBoolean.second)
        val shortByte: ShortByteJoin = (-30001).toShort() j (-117).toByte()
        sameValue((-30001).toShort(), shortByte.first)
        sameValue((-117).toByte(), shortByte.second)
        val shortShort: Twhort = (-30001).toShort() j (-30001).toShort()
        sameValue((-30001).toShort(), shortShort.first)
        sameValue((-30001).toShort(), shortShort.second)
        val shortChar: ShortCharJoin = (-30001).toShort() j '\uFFFF'
        sameValue((-30001).toShort(), shortChar.first)
        sameValue('\uFFFF', shortChar.second)
        val shortInt: ShortIntJoin = (-30001).toShort() j Int.MIN_VALUE
        sameValue((-30001).toShort(), shortInt.first)
        sameValue(Int.MIN_VALUE, shortInt.second)
        val shortFloat: ShortFloatJoin = (-30001).toShort() j Float.fromBits(0xFFC12345.toInt())
        sameValue((-30001).toShort(), shortFloat.first)
        sameValue(Float.fromBits(0xFFC12345.toInt()), shortFloat.second)
        val charBoolean: CharBooleanJoin = '\uFFFF' j true
        sameValue('\uFFFF', charBoolean.first)
        sameValue(true, charBoolean.second)
        val charByte: CharByteJoin = '\uFFFF' j (-117).toByte()
        sameValue('\uFFFF', charByte.first)
        sameValue((-117).toByte(), charByte.second)
        val charShort: CharShortJoin = '\uFFFF' j (-30001).toShort()
        sameValue('\uFFFF', charShort.first)
        sameValue((-30001).toShort(), charShort.second)
        val charChar: Twhar = '\uFFFF' j '\uFFFF'
        sameValue('\uFFFF', charChar.first)
        sameValue('\uFFFF', charChar.second)
        val charInt: CharIntJoin = '\uFFFF' j Int.MIN_VALUE
        sameValue('\uFFFF', charInt.first)
        sameValue(Int.MIN_VALUE, charInt.second)
        val charFloat: CharFloatJoin = '\uFFFF' j Float.fromBits(0xFFC12345.toInt())
        sameValue('\uFFFF', charFloat.first)
        sameValue(Float.fromBits(0xFFC12345.toInt()), charFloat.second)
        val intBoolean: IntBooleanJoin = Int.MIN_VALUE j true
        sameValue(Int.MIN_VALUE, intBoolean.first)
        sameValue(true, intBoolean.second)
        val intByte: IntByteJoin = Int.MIN_VALUE j (-117).toByte()
        sameValue(Int.MIN_VALUE, intByte.first)
        sameValue((-117).toByte(), intByte.second)
        val intShort: IntShortJoin = Int.MIN_VALUE j (-30001).toShort()
        sameValue(Int.MIN_VALUE, intShort.first)
        sameValue((-30001).toShort(), intShort.second)
        val intChar: IntCharJoin = Int.MIN_VALUE j '\uFFFF'
        sameValue(Int.MIN_VALUE, intChar.first)
        sameValue('\uFFFF', intChar.second)
        val intInt: TwInt = Int.MIN_VALUE j Int.MIN_VALUE
        sameValue(Int.MIN_VALUE, intInt.first)
        sameValue(Int.MIN_VALUE, intInt.second)
        val intFloat: IntFloatJoin = Int.MIN_VALUE j Float.fromBits(0xFFC12345.toInt())
        sameValue(Int.MIN_VALUE, intFloat.first)
        sameValue(Float.fromBits(0xFFC12345.toInt()), intFloat.second)
        val floatBoolean: FloatBooleanJoin = Float.fromBits(0xFFC12345.toInt()) j true
        sameValue(Float.fromBits(0xFFC12345.toInt()), floatBoolean.first)
        sameValue(true, floatBoolean.second)
        val floatByte: FloatByteJoin = Float.fromBits(0xFFC12345.toInt()) j (-117).toByte()
        sameValue(Float.fromBits(0xFFC12345.toInt()), floatByte.first)
        sameValue((-117).toByte(), floatByte.second)
        val floatShort: FloatShortJoin = Float.fromBits(0xFFC12345.toInt()) j (-30001).toShort()
        sameValue(Float.fromBits(0xFFC12345.toInt()), floatShort.first)
        sameValue((-30001).toShort(), floatShort.second)
        val floatChar: FloatCharJoin = Float.fromBits(0xFFC12345.toInt()) j '\uFFFF'
        sameValue(Float.fromBits(0xFFC12345.toInt()), floatChar.first)
        sameValue('\uFFFF', floatChar.second)
        val floatInt: FloatIntJoin = Float.fromBits(0xFFC12345.toInt()) j Int.MIN_VALUE
        sameValue(Float.fromBits(0xFFC12345.toInt()), floatInt.first)
        sameValue(Int.MIN_VALUE, floatInt.second)
        val floatFloat: TwFloat = Float.fromBits(0xFFC12345.toInt()) j Float.fromBits(0xFFC12345.toInt())
        sameValue(Float.fromBits(0xFFC12345.toInt()), floatFloat.first)
        sameValue(Float.fromBits(0xFFC12345.toInt()), floatFloat.second)
    }

    @Test
    fun allBytePairsPreserveSignedBits() {
        for (a in -128..127) for (b in -128..127) {
            val pair = a.toByte() j b.toByte()
            assertEquals(a.toByte(), pair.first)
            assertEquals(b.toByte(), pair.second)
        }
    }

    @Test
    fun integerAndShortPairsPreserveFullWidthValues() {
        val random = Random(64)
        repeat(4096) {
            val a = random.nextInt()
            val b = random.nextInt()
            val ints: TwInt = a j b
            assertEquals(a, ints.first)
            assertEquals(b, ints.second)
            val shorts: Twhort = a.toShort() j b.toShort()
            assertEquals(a.toShort(), shorts.first)
            assertEquals(b.toShort(), shorts.second)
        }
        val ints = 1 j -1
        assertEquals(1, ints.first)
        assertEquals(-1, ints.second)
        val shorts = 1.toShort() j (-1).toShort()
        assertEquals(1.toShort(), shorts.first)
        assertEquals((-1).toShort(), shorts.second)
    }

    @Test
    fun twinFactoriesAndLockedPackersUseTheSameLayouts() {
        for ((i, values) in samples.withIndex()) {
            val context = AutoTwinContext<Any>()
            for (a in values) for (b in values) {
                for (result in listOf(Twin(a, b), autoTwin(a, b), context.pack(a, b))) {
                    assertEquals(layouts[i][i], result::class)
                    checkJoin(a, b, result)
                }
            }
        }
        val ints: TwInt = autoTwin(1, -1)
        val floats: TwFloat = autoTwin(-0.0f, Float.fromBits(0x7FC12345))
        assertEquals(1, ints.first)
        assertEquals(-1, ints.second)
        assertEquals((-0.0f).toRawBits(), floats.first.toRawBits())
        assertEquals(0x7FC12345, floats.second.toRawBits())
        val mixed: Twin<Any> = Twin(1, 2.0f)
        assertIs<IntFloatJoin>(mixed)
        checkJoin(1, 2.0f, mixed)
    }

    @Test
    fun nullReferencesAndOversizedPairsRemainOrdinaryJoins() {
        val cases = listOf(
            null to 1, 1 to null, null to null, "left" to "right",
            1L to true, true to 1L, Long.MIN_VALUE to Long.MAX_VALUE,
            1.0 to false, 1.0 to 2.0, 1 to 2L,
            1.toUByte() to 2.toUByte(), 1u to 2u,
        )
        for ((a, b) in cases) {
            assertNull(packedPrimitiveJoin(a, b))
            checkJoin(a, b, erasedJoin(a, b))
        }
        val left = Any()
        val right = Any()
        val references = erasedJoin(left, right)
        assertSame(left, references.a)
        assertSame(right, references.b)
        val series: Series<Int> = 2 j { i: Int -> i + 300 }
        assertEquals(2, series.size)
        assertEquals(301, series[1])
    }
}
