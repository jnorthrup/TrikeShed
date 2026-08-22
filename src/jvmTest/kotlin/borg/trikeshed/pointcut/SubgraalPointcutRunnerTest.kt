package borg.trikeshed.pointcut

import borg.trikeshed.lib.size
import borg.trikeshed.classfile.model.BytecodePointcutKind
import borg.trikeshed.pointcut.polyglot.TspyPolyglotHostImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubgraalPointcutRunnerTest {
    @Test
    fun testEvaluate() = runBlocking {
        val host = TspyPolyglotHostImpl()
        val pythonResult = host.evaluatePython("x = {'a': 1}\nx['a'] = 2")
        val jsResult = host.evaluateJs("const o={a:1};\no.a=2;")

        var pythonWriteFound = false
        for (i in 0 until pythonResult.size) {
            val coord = pythonResult.b(i)
            if (coord.kind == BytecodePointcutKind.INSTANCE_FIELD_WRITE || coord.kind == BytecodePointcutKind.LOCAL_WRITE) {
                pythonWriteFound = true
            }
        }
        assertTrue(pythonWriteFound)

        var jsWriteFound = false
        for (i in 0 until jsResult.size) {
            val coord = jsResult.b(i)
            if (coord.kind == BytecodePointcutKind.INSTANCE_FIELD_WRITE || coord.kind == BytecodePointcutKind.LOCAL_WRITE) {
                jsWriteFound = true
            }
        }
        assertTrue(jsWriteFound)
    }
}
