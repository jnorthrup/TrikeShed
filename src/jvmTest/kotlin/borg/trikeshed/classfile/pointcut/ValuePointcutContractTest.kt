package borg.trikeshed.classfile.pointcut

import borg.trikeshed.classfile.model.BytecodePointcutKind
import borg.trikeshed.classfile.model.div
import borg.trikeshed.classfile.model.rem
import borg.trikeshed.lib.α
import borg.trikeshed.lib.view
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValuePointcutContractTest {
    @Test
    fun `JEP 484 table covers JVM value bytecodes and polyglot operator tokens`() {
        val table = ValueBytecodePointcutTable.jep484()
        val opcodes = table.α { it.jvmOpcode }.view.toSet()

        val required = listOf(
            "ACONST_NULL", "ICONST_0", "BIPUSH", "SIPUSH", "LDC",
            "ILOAD", "ISTORE", "IALOAD", "IASTORE",
            "IADD", "ISUB", "IMUL", "IDIV", "IREM", "INEG",
            "ISHL", "ISHR", "IUSHR", "IAND", "IOR", "IXOR", "IINC",
            "I2L", "L2I", "I2D", "D2I",
            "LCMP", "FCMPL", "FCMPG", "DCMPL", "DCMPG",
            "IFEQ", "IFNE", "IF_ICMPEQ", "IF_ICMPNE",
            "IRETURN", "RETURN",
            "GETFIELD", "PUTFIELD", "GETSTATIC", "PUTSTATIC",
            "INVOKEVIRTUAL", "INVOKESTATIC",
            "NEW", "NEWARRAY", "ANEWARRAY", "MULTIANEWARRAY",
            "CHECKCAST", "INSTANCEOF",
        )

        assertTrue(required.all { it in opcodes }, "missing value opcodes: ${required.filterNot { it in opcodes }}")

        val idiv = table.view.first { it.jvmOpcode == "IDIV" }
        val irem = table.view.first { it.jvmOpcode == "IREM" }
        assertEquals(BytecodePointcutKind.OPERATOR, idiv.kind)
        assertEquals(BytecodePointcutKind.OPERATOR, irem.kind)
        assertTrue(idiv.languageMappings.view.any { it.language == PolyglotLanguage.ECMA && it.sourceToken == "/" })
        assertTrue(irem.languageMappings.view.any { it.language == PolyglotLanguage.ECMA && it.sourceToken == "%" })
    }

    @Test
    fun `JVM pointcut command emits dual phase source rooted value coordinates`() {
        val sink = RecordingPointcutSink()
        val fixture = JvmValuePointcutFixture.allValueOpcodes()

        val activations = JvmPointcutCommand().execute(fixture, sink)
        val emitted = sink.activations()
        val emittedOpcodes = emitted.α { it.coordinate.jvmOpcode }.view.toSet()

        assertEquals(activations.a, emitted.a)
        assertTrue(emitted.a > 0, "JVM command should emit pointcut activations")
        assertTrue(setOf("GETFIELD", "PUTFIELD", "IDIV", "IREM", "I2L", "IRETURN").all { it in emittedOpcodes })

        val bySite = emitted.view.groupBy { it.coordinate.symbol.methodName to (it.coordinate.bytecodeOffset to it.coordinate.jvmOpcode) }
        assertTrue(bySite.isNotEmpty())
        assertTrue(bySite.values.all { site -> site.map { it.phase }.toSet() == setOf(PointcutPhase.BEFORE, PointcutPhase.AFTER) })
        assertTrue(emitted.view.all { it.coordinate.source.language == PolyglotLanguage.JVM.id })
        assertTrue(emitted.view.all { it.coordinate.source.line > 0 })
        assertTrue(emitted.view.all { it.addr == it.coordinate.bytecodeOffset })
        assertTrue(emitted.view.all { it.methodIdx >= 0 && it.templateIdx >= 0 })
    }

    @Test
    fun `Graal ECMA command evaluates script and maps div rem to source coordinates`() {
        val sink = RecordingPointcutSink()
        val script = GraalEcmaScript(
            sourceName = "ecma-value-ops.js",
            source = """
                const a = 17;
                const b = 5;
                const q = (a / b) | 0;
                const r = a % b;
                q + r;
            """.trimIndent(),
        )

        val result = GraalPolyglotPointcutCommand().execute(script, sink)
        val activations = result.activations
        val operatorCoordinates = result.coordinates / BytecodePointcutKind.OPERATOR
        val operatorIndexes = result.coordinates % BytecodePointcutKind.OPERATOR
        val opcodes = operatorCoordinates.α { it.jvmOpcode }.view.toSet()

        assertEquals(5, result.value)
        assertTrue(activations.a > 0, "Graal ECMA command should emit pointcut activations")
        assertEquals(operatorCoordinates.a, operatorIndexes.a)
        assertTrue("IDIV" in opcodes, "ECMA / should map to JVM IDIV operator coordinate")
        assertTrue("IREM" in opcodes, "ECMA % should map to JVM IREM operator coordinate")
        assertTrue(operatorCoordinates.view.all { it.source.sourceFile == "ecma-value-ops.js" })
        assertTrue(operatorCoordinates.view.all { it.source.language == PolyglotLanguage.ECMA.id })
        assertEquals(3, operatorCoordinates.view.first { it.jvmOpcode == "IDIV" }.source.line)
        assertEquals(4, operatorCoordinates.view.first { it.jvmOpcode == "IREM" }.source.line)
        assertTrue(activations.view.groupBy { it.coordinate.jvmOpcode to it.phase }.keys.contains("IDIV" to PointcutPhase.BEFORE))
        assertTrue(activations.view.groupBy { it.coordinate.jvmOpcode to it.phase }.keys.contains("IREM" to PointcutPhase.AFTER))
    }
}
