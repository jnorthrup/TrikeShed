package borg.trikeshed.classfile.jep484

import borg.trikeshed.classfile.model.BytecodePointcutKind
import borg.trikeshed.classfile.model.div
import borg.trikeshed.classfile.model.rem
import borg.trikeshed.lib.α
import borg.trikeshed.lib.plus
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Jep484ClassfileScannerTest {
    @Test
    fun `scan returns source rooted series coordinates for value bytecodes`() {
        val coordinates = Jep484ClassfileScanner().scan(compileFixture(), language = "jvm")

        assertTrue(coordinates.a > 0, "scanner should return a Series of pointcut coordinates")
        assertEquals(coordinates.a, coordinates.view.toList().size, ".view is the materialization gateway")

        val expectedOpcodes = listOf(
            "GETFIELD",
            "PUTFIELD",
            "GETSTATIC",
            "PUTSTATIC",
            "ILOAD",
            "ISTORE",
            "IALOAD",
            "IASTORE",
            "IADD",
            "IDIV",
            "IREM",
            "I2L",
        ).toSeries()
        val seenOpcodes = coordinates.α { it.jvmOpcode }.view.toSet()

        assertTrue(
            expectedOpcodes.view.all { it in seenOpcodes },
            "all required value bytecodes should be found, saw $seenOpcodes",
        )

        val putField = coordinates.view.first { it.jvmOpcode == "PUTFIELD" }
        assertEquals(BytecodePointcutKind.INSTANCE_FIELD_WRITE, putField.kind)
        assertEquals("Jep484Fixture.java", putField.source.sourceFile)
        assertEquals("jvm", putField.source.language)
        assertTrue(putField.source.line > 0, "line number table should map PUTFIELD to a source line")
        assertEquals(putField.bytecodeOffset, putField.source.bytecodeOffset)
        assertEquals("Jep484Fixture", putField.symbol.owner)
        assertEquals("instanceValue", putField.symbol.name)
        assertEquals("I", putField.symbol.descriptor)
        assertEquals("exercise", putField.symbol.methodName)
        assertEquals("(I[I)I", putField.symbol.methodDescriptor)
    }

    @Test
    fun `div and rem reduce pointcut cursor to series for alpha and view gateways`() {
        val coordinates = Jep484ClassfileScanner().scan(compileFixture(), language = "ecma")

        val operatorCoordinates = coordinates / BytecodePointcutKind.OPERATOR
        val operatorIndexes = coordinates % BytecodePointcutKind.OPERATOR
        val operatorOpcodes = operatorCoordinates.α { it.jvmOpcode }
        val expectedOperatorOpcodes = arrayOf("IADD", "IDIV", "IREM").toSeries()

        assertTrue(operatorCoordinates.a >= expectedOperatorOpcodes.a)
        assertEquals(operatorCoordinates.a, operatorIndexes.a)
        assertTrue(expectedOperatorOpcodes.view.all { it in operatorOpcodes.view.toSet() })
        assertTrue(operatorCoordinates.view.all { it.source.language == "ecma" })
        assertTrue(operatorIndexes.view.all { index -> coordinates.b(index).kind == BytecodePointcutKind.OPERATOR })
    }

    @Test
    fun `local variable table and line table survive value coordinate mapping`() {
        val coordinates = Jep484ClassfileScanner().scan(compileFixture(), language = "jvm")

        val localReads = coordinates / BytecodePointcutKind.LOCAL_READ
        val localWrites = coordinates / BytecodePointcutKind.LOCAL_WRITE
        val localNames = (localReads + localWrites).α { it.symbol.name }.view.toSet()

        assertTrue(localReads.a > 0, "loads should map to LOCAL_READ")
        assertTrue(localWrites.a > 0, "stores should map to LOCAL_WRITE")
        assertTrue("input" in localNames, "javac -g local variable table should expose input")
        assertTrue("local" in localNames, "javac -g local variable table should expose local")
        assertTrue((localReads + localWrites).view.all { it.source.line > 0 })
    }

    private fun compileFixture(): ByteArray {
        val source = """
            public class Jep484Fixture {
                public int instanceValue;
                public static int staticValue;

                public int exercise(int input, int[] items) {
                    int local = input + 1;
                    this.instanceValue = local;
                    int field = this.instanceValue;
                    staticValue = field;
                    int stat = staticValue;
                    int array = items[0];
                    items[1] = stat;
                    long widened = (long) array;
                    int divided = (int) widened / 2;
                    int rem = divided % 2;
                    return rem + stat;
                }
            }
        """.trimIndent()
        val dir = Files.createTempDirectory("jep484-fixture")
        val sourcePath = dir.resolve("Jep484Fixture.java")
        Files.writeString(sourcePath, source)

        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler(), "tests require a JDK compiler")
        val result = compiler.run(null, null, null, "-g", "-d", dir.toString(), sourcePath.toString())
        assertEquals(0, result, "javac should compile the fixture with debug tables")

        return Files.readAllBytes(dir.resolve("Jep484Fixture.class"))
    }
}
