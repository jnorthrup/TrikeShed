package borg.trikeshed.lib

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrimitiveJoinJvmTest {
    @Test
    fun allLayoutsUseOnePrimitivePayloadAndPrimitiveAccessors() {
        val values: List<Any> = listOf(true, 1.toByte(), 2.toShort(), 'x', 3, 4.0f)
        val widths = listOf(1, 8, 16, 16, 32, 32)
        for ((i, a) in values.withIndex()) for ((k, b) in values.withIndex()) {
            val layout = (a j b).javaClass
            val fields = layout.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
            assertEquals(1, fields.size, layout.name)
            val width = widths[i] + widths[k]
            val storage = when {
                width <= 8 -> Byte::class.javaPrimitiveType
                width <= 16 -> Short::class.javaPrimitiveType
                width <= 32 -> Int::class.javaPrimitiveType
                else -> Long::class.javaPrimitiveType
            }
            assertEquals(storage, fields.single().type, layout.name)
            for (accessor in listOf("getFirst-impl", "getSecond-impl")) {
                val method = layout.declaredMethods.single { it.name == accessor }
                assertTrue(Modifier.isStatic(method.modifiers), layout.name)
                assertTrue(method.returnType.isPrimitive, layout.name)
            }
        }
    }

    companion object {
        @JvmStatic
        fun intFirst(a: Int, b: Int): Int = (a j b).first

        @JvmStatic
        fun floatSecond(a: Float, b: Float): Float = (a j b).second

        @JvmStatic
        fun mixedSecond(a: Int, b: Float): Float = (a j b).second
    }
}
