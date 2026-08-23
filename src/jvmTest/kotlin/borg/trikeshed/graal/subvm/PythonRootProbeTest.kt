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
 * KNOWN BOUND (GraalPy 25.0.2 CE): an unfiltered `ExecutionListener.roots(true)` receives NO root
 * events from Python code — not the module root, not function roots — while the same listener
 * sees every JS root. This is why [GuestBounds.PYTHON.rootEventsObservable] is false and Python
 * uses binding pointcuts ([InProcessIsolate] wraps top-level callables after each eval).
 * If this test ever starts failing, GraalPy has begun reporting roots: flip the bound and delete
 * the binding-pointcut path for Python.
 */
class PythonRootProbeTest {
    private fun roots(language: String, program: String): List<String> {
        val seen = ArrayList<String>()
        val engine = Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build()
        val listener = ExecutionListener.newBuilder().roots(true).onEnter { seen += (it.rootName ?: "<null>") }.attach(engine)
        Context.newBuilder(language).engine(engine).allowHostAccess(HostAccess.NONE).build().use { c -> c.eval(language, program) }
        listener.close(); engine.close(true)
        return seen
    }

    @Test fun pythonRootsAreNotObservableButJsRootsAre() {
        val py = roots("python", "def fib(n):\n    return n if n < 2 else fib(n-1) + fib(n-2)\nfib(5)")
        val js = roots("js", "function fib(n){return n<2?n:fib(n-1)+fib(n-2)} fib(5)")
        println("PY-ROOTS ${py.groupingBy { it }.eachCount()}  JS-ROOTS ${js.groupingBy { it }.eachCount()}")
        assertEquals(emptyList(), py, "GraalPy now reports roots — flip GuestBounds.PYTHON.rootEventsObservable")
        assertTrue(js.count { it == "fib" } >= 15, "JS listener should see the fib recursion; saw ${js.size}")
        assertEquals(false, GuestBounds.PYTHON.rootEventsObservable)
        assertEquals(true, GuestBounds.JS.rootEventsObservable)
    }

    @Test fun pythonBindingPointcutsObserveCallsInstead() {
        val seen = ArrayList<RootObservation>()
        InProcessIsolate("py-probe", borg.trikeshed.pointcut.VmFacet.GRAAL_PYTHON) { seen += it }.use { iso ->
            iso.eval("def fib(n):\n    return n if n < 2 else fib(n-1) + fib(n-2)\ndef outer(n):\n    return fib(n) + 1\n")
            assertEquals(Teleported.Num(6), iso.call("outer", Teleported.Num(5)))
        }
        val fib = seen.filter { it.root == "fib" }
        val outer = seen.filter { it.root == "outer" }
        assertTrue(fib.size >= 15, "binding pointcut should observe the recursion; saw ${fib.size}")
        assertTrue(fib.all { it.selfContained }, "self-recursion is self-contained")
        assertEquals(1, outer.size)
        assertEquals(false, outer.single().selfContained, "outer called a foreign root")
    }
}
