package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.Teleported

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.management.ExecutionListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GraalPy 25.3.4.1 reports native function roots and source sections. Keep the bound backed
 * by the engine itself as well as the isolate's filtering and observation contract.
 */
class PythonRootProbeTest {
    @Test fun nativePythonRootsCarrySourceAndCoverNestedMethodsAndGenerators() {
        val seen = ArrayList<Triple<String, Int, String>>()
        Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build().use { engine ->
            ExecutionListener.newBuilder().roots(true)
                .rootNameFilter { it != null && !GuestBounds.PYTHON.rootNameNoise(it) }
                .onReturn { event ->
                    val source = event.location
                    if (source?.source?.name == "native-roots.py") seen += Triple(
                        event.rootName, source.startLine, source.characters.toString())
                }.attach(engine).use {
                    Context.newBuilder("python").engine(engine).allowHostAccess(HostAccess.NONE).build().use { context ->
                        context.eval(org.graalvm.polyglot.Source.newBuilder("python", """
                            def outer(value):
                                def inner(*, n): return n * 2
                                return inner(n=value)
                            class Sample:
                                def label(self, *, value): return str(value)
                            def letters(n): return ''.join(chr(65+i) for i in range(n))
                            assert outer(3) == 6
                            assert Sample().label(value=7) == '7'
                            assert letters(3) == 'ABC'
                        """.trimIndent(), "native-roots.py").buildLiteral())
                    }
                }
        }
        println("PY-NATIVE-SECTIONS $seen")
        assertTrue(seen.any { it.first == "outer" && it.second == 1 && it.third.contains("def outer") }, "$seen")
        assertTrue(seen.any { it.first.endsWith("inner") && it.third.contains("def inner") }, "$seen")
        assertTrue(seen.any { it.first.endsWith("label") && it.third.contains("def label") }, "$seen")
        assertTrue(seen.any { it.first == "letters" && it.second == 6 }, "$seen")
    }

    private fun roots(language: String, program: String): List<String> {
        val seen = ArrayList<String>()
        val engine = Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build()
        val listener = ExecutionListener.newBuilder().roots(true).onEnter { seen += (it.rootName ?: "<null>") }.attach(engine)
        Context.newBuilder(language).engine(engine).allowHostAccess(HostAccess.NONE).build().use { c -> c.eval(language, program) }
        listener.close(); engine.close(true)
        return seen
    }

    @Test fun pythonAndJsFunctionRootsAreObservable() {
        val py = roots("python", "def fib(n):\n    return n if n < 2 else fib(n-1) + fib(n-2)\nfib(5)")
        val js = roots("js", "function fib(n){return n<2?n:fib(n-1)+fib(n-2)} fib(5)")
        println("PY-ROOTS ${py.groupingBy { it }.eachCount()}  JS-ROOTS ${js.groupingBy { it }.eachCount()}")
        assertEquals(15, py.count { it == "fib" }, "Python listener must see every recursive call")
        assertTrue(js.count { it == "fib" } >= 15, "JS listener should see the fib recursion; saw ${js.size}")
        assertEquals(true, GuestBounds.PYTHON.rootEventsObservable)
        assertEquals(true, GuestBounds.JS.rootEventsObservable)
    }

    @Test fun pythonNativePointcutsObserveCallsWithoutWrappingBindings() {
        val seen = ArrayList<RootObservation>()
        InProcessIsolate("py-probe", borg.trikeshed.pointcut.VmFacet.GRAAL_PYTHON) { seen += it }.use { iso ->
            iso.eval("def fib(n):\n    return n if n < 2 else fib(n-1) + fib(n-2)\ndef outer(n):\n    return fib(n) + 1\n")
            assertTrue(iso.wrappedRoots.isEmpty(), "native instrumentation must not replace functions")
            assertEquals(Teleported.Num(6), iso.call("outer", Teleported.Num(5)))
        }
        val fib = seen.filter { it.root == "fib" }
        val outer = seen.filter { it.root == "outer" }
        assertEquals(15, fib.size, "native pointcuts must observe recursion exactly once")
        assertTrue(fib.all { it.selfContained }, "self-recursion is self-contained")
        assertEquals(1, outer.size)
        assertEquals(false, outer.single().selfContained, "outer called a foreign root")
    }
}
