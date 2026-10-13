package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.Teleported

import borg.trikeshed.vm.Teleported.Arr
import borg.trikeshed.vm.Teleported.Num
import borg.trikeshed.vm.Teleported.Real
import borg.trikeshed.vm.Teleported.Str
import borg.trikeshed.pointcut.VmFacet
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [InProcessIsolate] against the probed bounds in [GuestBounds]: teleported eval/call both ways,
 * the `host` door, the JS statement limit (isolate dies), the Python wall watchdog (isolate
 * survives), explicit cross-thread interrupt, root observations and counters.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class InProcessIsolateTest {
    private fun js(budget: Budget = Budget(), onRoot: (RootObservation) -> Unit = {}) =
        InProcessIsolate("js-${System.nanoTime()}", VmFacet.GRAAL_JS, budget, onRootReturn = onRoot)

    private fun py(budget: Budget = Budget()) =
        InProcessIsolate("py-${System.nanoTime()}", VmFacet.GRAAL_PYTHON, budget)

    private fun expectFailure(kind: GuestFailure, block: () -> Unit): GuestException {
        val e = assertFailsWith<GuestException>(block = block)
        assertEquals(kind, e.kind, "expected $kind, got ${e.kind}: ${e.message}")
        return e
    }

    @Test fun jsEvalTeleportsPrimitivesAndArrays() {
        js().use { iso ->
            assertEquals(Num(3), iso.eval("1+2"))
            assertEquals(Real(0.5), iso.eval("1/2"))
            assertEquals(Str("hi"), iso.eval("'h'+'i'"))
            assertEquals(Arr(listOf(Num(1), Num(2), Num(3))), iso.eval("[1,2,3]"))
            assertEquals(Teleported.Null, iso.eval("undefined"))
            assertEquals(Teleported.Obj(mapOf("a" to Num(1), "b" to Arr(listOf(Str("x"))))), iso.eval("({b:['x'], a:1})"))
            assertTrue(iso.isAlive)
        }
    }

    @Test fun pythonEvalTeleportsPrimitivesAndArrays() {
        py().use { iso ->
            assertEquals(Num(3), iso.eval("1+2"))
            assertEquals(Str("hi"), iso.eval("'h'+'i'"))
            assertEquals(Arr(listOf(Num(1), Num(2), Num(3))), iso.eval("[1,2,3]"))
            assertEquals(Real(0.5), iso.eval("1/2"))
            assertTrue(iso.isAlive)
        }
    }

    @Test fun callInvokesAGuestGlobalRootInJs() {
        js().use { iso ->
            assertEquals(Teleported.Null, iso.eval("function add(a,b){return a+b}"))
            assertEquals(Num(5), iso.call("add", Num(2), Num(3)))
            assertEquals(Str("ab"), iso.call("add", Str("a"), Str("b")))
            assertEquals(Real(2.5), iso.call("add", Num(2), Real(0.5)))
            expectFailure(GuestFailure.GUEST_ERROR) { iso.call("nope") }
            iso.eval("var notAFunction = 1")
            expectFailure(GuestFailure.GUEST_ERROR) { iso.call("notAFunction") }
            // a guest throw is a GUEST_ERROR and leaves the isolate alive
            iso.eval("function boom(){ throw new Error('kaboom') }")
            expectFailure(GuestFailure.GUEST_ERROR) { iso.call("boom") }
            assertTrue(iso.isAlive)
            assertEquals(Num(5), iso.call("add", Num(2), Num(3)))
        }
    }

    @Test fun callInvokesAGuestGlobalRootInPython() {
        py().use { iso ->
            iso.eval("def add(a,b): return a+b")
            assertEquals(Num(5), iso.call("add", Num(2), Num(3)))
            assertEquals(Str("ab"), iso.call("add", Str("a"), Str("b")))
            expectFailure(GuestFailure.GUEST_ERROR) { iso.call("nope") }
            assertTrue(iso.isAlive)
        }
    }

    @Test fun guestReachesHostThroughTheDelegateDoorInJsAndPython() {
        js().use { iso ->
            iso.delegate("double") { args -> Num(2 * (args[0] as Num).v) }
            assertEquals(Num(42), iso.eval("host.call('double', 21)"))
            assertEquals(1L, iso.stats().hostCalls)
            // teleported containers come back as proxies the guest can index
            iso.delegate("pair") { args -> Teleported.Obj(mapOf("sum" to Num((args[0] as Num).v + (args[1] as Num).v), "xs" to Arr(args))) }
            assertEquals(Num(7), iso.eval("host.call('pair', 3, 4).sum"))
            assertEquals(Num(2), iso.eval("host.call('pair', 3, 4).xs.length"))
            // and guest containers arrive teleported
            iso.delegate("len") { args -> Num((args[0] as Arr).v.size.toLong()) }
            assertEquals(Num(3), iso.eval("host.call('len', [1,2,3])"))
        }
        py().use { iso ->
            iso.delegate("double") { args -> Num(2 * (args[0] as Num).v) }
            assertEquals(Num(42), iso.eval("host.call('double', 21)"))
            assertEquals(1L, iso.stats().hostCalls)
        }
    }

    @Test fun unknownHostDelegateIsAGuestErrorAndTheIsolateSurvives() {
        js().use { iso ->
            expectFailure(GuestFailure.GUEST_ERROR) { iso.eval("host.call('nope', 1)") }
            expectFailure(GuestFailure.GUEST_ERROR) { iso.eval("host.call(42)") }  // name must be a string
            assertTrue(iso.isAlive)
            assertEquals(Num(1), iso.eval("1"))
        }
    }

    @Test fun jsStatementLimitExhaustsAndKillsTheIsolate() {
        js(Budget(statements = 50_000)).use { iso ->
            assertEquals(Num(2), iso.eval("1+1"))
            val e = expectFailure(GuestFailure.EXHAUSTED) { iso.eval("let i=0; while(true){i++}") }
            assertIs<org.graalvm.polyglot.PolyglotException>(e.cause)
            assertFalse(iso.isAlive)
            expectFailure(GuestFailure.DEAD) { iso.eval("1") }
            expectFailure(GuestFailure.DEAD) { iso.call("anything") }
            assertFalse(iso.interrupt())
        }
    }

    /**
     * GraalPy's statement limit is UNSAFE (GIL assert — [GuestBounds.PYTHON], GraalBoundsSmokeTest):
     * a statements-only budget (wall disabled) must still stop a runaway loop, as a clean outside
     * interrupt after [GuestBounds.DEFAULT_WALL_MILLIS] with the isolate surviving — before this
     * hardening such a budget was silently unenforced and the loop ran forever.
     */
    @Test fun pythonStatementsBudgetDegradesToTheWallProxyInterrupt() {
        py(Budget(statements = 10_000, wallMillis = 0)).use { iso ->
            assertEquals(Num(2), iso.eval("1+1"))
            val t0 = System.nanoTime()
            expectFailure(GuestFailure.INTERRUPTED) { iso.eval("i=0\nwhile True:\n    i+=1") }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(
                ms < GuestBounds.DEFAULT_WALL_MILLIS + InProcessIsolate.INTERRUPT_GRACE_MS + 5_000,
                "proxy watchdog took ${ms}ms (default wall ${GuestBounds.DEFAULT_WALL_MILLIS}ms + grace)",
            )
            assertTrue(iso.isAlive)
            assertEquals(1L, iso.stats().interrupted)
            assertEquals(Num(4), iso.eval("2+2"))
        }
    }

    /**
     * The crossing failure taxonomy as a table. The GIL-assert shape — engine-internal, usually also
     * cancelled — must come out DEAD and fail closed, never INTERRUPTED over a poisoned context.
     */
    @Test fun failureTaxonomyFailsClosedOnEngineInternalErrors() {
        assertEquals(GuestFailure.EXHAUSTED to true, InProcessIsolate.classify(resourceExhausted = true, internalError = false, interrupted = true))
        assertEquals(GuestFailure.DEAD to true, InProcessIsolate.classify(resourceExhausted = false, internalError = true, interrupted = true))
        assertEquals(GuestFailure.DEAD to true, InProcessIsolate.classify(resourceExhausted = false, internalError = true, interrupted = false))
        assertEquals(GuestFailure.INTERRUPTED to false, InProcessIsolate.classify(resourceExhausted = false, internalError = false, interrupted = true))
        assertEquals(GuestFailure.GUEST_ERROR to false, InProcessIsolate.classify(resourceExhausted = false, internalError = false, interrupted = false))
    }

    @Test fun pythonLoopIsInterruptedByTheWallWatchdogAndTheIsolateSurvives() {
        py(Budget(wallMillis = 500)).use { iso ->
            assertEquals(Num(2), iso.eval("1+1"))
            val t0 = System.nanoTime()
            expectFailure(GuestFailure.INTERRUPTED) { iso.eval("i=0\nwhile True:\n    i+=1") }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms < 5_000, "watchdog took ${ms}ms (budget 500ms + ${InProcessIsolate.INTERRUPT_GRACE_MS}ms grace)")
            assertTrue(iso.isAlive)
            assertEquals(1L, iso.stats().interrupted)
            assertEquals(Num(4), iso.eval("2+2"))
            iso.eval("def add(a,b): return a+b")
            assertEquals(Num(3), iso.call("add", Num(1), Num(2)))
        }
    }

    @Test fun explicitInterruptFromAnotherThreadStopsARunningJsLoop() {
        js(Budget(statements = 0)).use { iso ->
            val outcome = AtomicReference<Throwable?>()
            val entered = CountDownLatch(1)
            val t = Thread({
                entered.countDown()
                try { iso.eval("while(true){}") } catch (e: Throwable) { outcome.set(e) }
            }, "guest-loop").apply { isDaemon = true }
            t.start(); entered.await()
            // interrupt is idempotent; keep knocking until the loop thread is gone
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var interrupted = false
            while (t.isAlive && System.nanoTime() < deadline) {
                Thread.sleep(200)
                interrupted = iso.interrupt() || interrupted
            }
            assertFalse(t.isAlive, "guest loop still running after interrupt()")
            assertTrue(interrupted)
            val e = outcome.get()
            assertIs<GuestException>(e, "loop thread ended with $e")
            assertEquals(GuestFailure.INTERRUPTED, e.kind, e.message)
            assertTrue(iso.stats().interrupted >= 1)
            // the context survives an interrupt; sequential access from this thread is legal
            assertTrue(iso.isAlive)
            assertEquals(Num(1), iso.eval("1"))
        }
    }

    @Test fun rootObservationsDistinguishLeavesFromCallers() {
        val seen = CopyOnWriteArrayList<RootObservation>()
        js(onRoot = { seen += it }).use { iso ->
            iso.eval("function leaf(n){return n*2}\nfunction outer(n){return leaf(n)+1}\nfor (let i=0;i<5;i++) outer(i);", "obs.js")
            assertEquals(setOf("leaf", "outer"), seen.map { it.root }.toSet(), "program/noise roots must be filtered")
            val leaves = seen.filter { it.root == "leaf" }
            val outers = seen.filter { it.root == "outer" }
            assertEquals(5, leaves.size); assertEquals(5, outers.size)
            assertTrue(leaves.all { it.selfContained }, "leaf enters no foreign root")
            assertTrue(outers.none { it.selfContained }, "outer enters leaf")
            assertTrue(leaves.all { it.nanos > 0 })
            assertTrue(outers.all { it.nanos > 0 })
            assertTrue(leaves.all { it.characters != null && it.characters!!.contains("n*2") }, leaves.first().characters ?: "null")
            assertEquals("obs.js", leaves.first().sourceName)
            assertEquals(1, leaves.first().line); assertEquals(2, outers.first().line)
            assertEquals(10L, iso.stats().rootEnters)
            // a host call inside the window taints every open frame
            seen.clear()
            iso.delegate("tick") { it.first() }
            iso.eval("function viaHost(n){return host.call('tick', n)}\nfunction wraps(n){return viaHost(n)}\nwraps(1); wraps(2);")
            assertTrue(seen.filter { it.root == "viaHost" }.none { it.selfContained })
            assertTrue(seen.filter { it.root == "wraps" }.none { it.selfContained })
            assertEquals(2, seen.count { it.root == "viaHost" })
        }
    }

    /** Python carries the same native source and frame semantics as the JS listener. */
    @Test fun pythonRootsAreObservedWithSourceSections() {
        val seen = CopyOnWriteArrayList<RootObservation>()
        InProcessIsolate("py-bp", VmFacet.GRAAL_PYTHON, Budget()) { seen += it }.use { iso ->
            iso.eval("def leaf(n): return n*2\ndef outer(n): return leaf(n)+1", "obs.py")
            assertEquals(0, seen.size)
            repeat(5) { assertEquals(Num(it * 2L + 1), iso.call("outer", Num(it.toLong()))) }
            assertEquals(setOf("leaf", "outer"), seen.map { it.root }.toSet(), "module roots must be filtered")
            assertTrue(iso.wrappedRoots.isEmpty())
            val leaves = seen.filter { it.root == "leaf" }
            val outers = seen.filter { it.root == "outer" }
            assertEquals(5, leaves.size); assertEquals(5, outers.size)
            assertTrue(leaves.all { it.selfContained }, "leaf enters no foreign root")
            assertTrue(outers.none { it.selfContained }, "outer enters leaf")
            assertTrue(leaves.all { it.characters == "def leaf(n): return n*2" && it.line == 1 && it.sourceName == "obs.py" })
            assertTrue(outers.all { it.line == 2 && it.sourceName == "obs.py" })
            assertTrue(seen.all { it.nanos > 0 })
            assertEquals(10L, iso.stats().rootEnters)
            // the program text is what a leaf host re-parses for roots without characters
            assertTrue(iso.program.contains("def leaf(n)") && iso.program.contains("def outer(n)"), iso.program)
            // A later eval's functions participate without installing any wrapper.
            seen.clear()
            iso.eval("def twice(n): return outer(outer(n))")
            assertEquals(Num(7), iso.eval("twice(1)"))
            assertEquals(listOf("leaf", "outer", "leaf", "outer", "twice"), seen.map { it.root })
            // a host call inside the window taints every open frame
            seen.clear()
            iso.delegate("tick") { it.first() }
            iso.eval("def viaHost(n): return host.call('tick', n)\ndef wraps(n): return viaHost(n)")
            assertEquals(Num(3), iso.call("wraps", Num(3)))
            assertTrue(seen.filter { it.root == "viaHost" }.none { it.selfContained })
            assertTrue(seen.filter { it.root == "wraps" }.none { it.selfContained })
        }
    }

    @Test fun pythonNativePointcutsCoverNestedFunctionsMethodsAndRedefinitions() {
        val seen = ArrayList<RootObservation>()
        InProcessIsolate("py-source", VmFacet.GRAAL_PYTHON) { seen += it }.use { iso ->
            iso.eval("""
                def outer(n):
                    def inner(*, n): return n * 2
                    return inner(n=n)
                class Sample:
                    def label(self, *, n): return str(n)
                def render(n): return Sample().label(n=outer(n))
            """.trimIndent(), "nested.py")
            seen.clear()
            assertEquals(Str("6"), iso.call("render", Num(3)))
            assertEquals(listOf("inner", "outer", "label", "render"), seen.map { it.root })
            assertEquals(listOf(2, 1, 5, 6), seen.map { it.line })
            assertTrue(seen.all { it.sourceName == "nested.py" && it.characters != null })
            assertTrue(seen.filter { it.root == "inner" || it.root == "label" }.all { it.selfContained })
            assertTrue(seen.filter { it.root == "outer" || it.root == "render" }.none { it.selfContained })
            iso.eval("def outer(n): return n + 10", "replacement.py")
            seen.clear()
            assertEquals(Num(13), iso.call("outer", Num(3)))
            assertEquals("replacement.py", seen.single().sourceName)
            assertEquals("def outer(n): return n + 10", seen.single().characters)
            assertTrue(iso.wrappedRoots.isEmpty())
        }
    }

    @Test fun pythonNativePointcutsPreserveKeywordsMetadataAndGuestExceptions() {
        val seen = ArrayList<RootObservation>()
        InProcessIsolate("py-keywords", VmFacet.GRAAL_PYTHON) { seen += it }.use { iso ->
            iso.eval("""
                def label(value: int, /, *, prefix: str = 'v') -> str:
                    'Label a value.'
                    return prefix + str(value)
                label.unit = 'count'
                def outer(*, value): return label(value, prefix='n')
                def failure(*, error): raise error
                def via_host(*, value): return host.call('echo', value)
            """.trimIndent())
            assertEquals(Str("n7"), iso.eval("outer(value=7)"))
            assertEquals(listOf("label", "outer"), seen.map { it.root })
            assertTrue(seen.first().selfContained)
            assertFalse(seen.last().selfContained)
            assertEquals(Teleported.Bool(true), iso.eval("""
                import inspect
                assert inspect.isfunction(label)
                assert not hasattr(label, '__wrapped__')
                assert label.__name__ == 'label' and label.__doc__ == 'Label a value.'
                assert label.unit == 'count' and label.__annotations__['return'] is str
                signature = inspect.signature(label)
                assert signature.parameters['value'].kind is inspect.Parameter.POSITIONAL_ONLY
                assert signature.parameters['prefix'].kind is inspect.Parameter.KEYWORD_ONLY
                assert signature.parameters['prefix'].default == 'v'
                try: label(value=1)
                except TypeError: pass
                else: raise AssertionError('positional-only constraint lost')
                error = ValueError('expected')
                try: failure(error=error)
                except ValueError as caught: assert caught is error
                else: raise AssertionError('guest exception lost')
                True
            """.trimIndent()))
            seen.clear()
            assertEquals(Str("v2"), iso.call("label", Num(2)))
            assertTrue(seen.single().selfContained, "failed calls must release observation frames")
            iso.delegate("echo") { it.first() }
            seen.clear()
            assertEquals(Num(3), iso.eval("via_host(value=3)"))
            assertFalse(seen.single().selfContained, "keyword forwarding must retain host-effect attribution")
        }
    }

    @Test fun pythonRebindingIsNotGuestWorkButSubsequentLibraryCallsAreObserved() {
        val seen = ArrayList<RootObservation>()
        InProcessIsolate("py-rebind", VmFacet.GRAAL_PYTHON) { seen += it }.use { iso ->
            iso.eval("def score(n): return n*2", "curation.py")
            seen.clear()
            iso.onGuestThread {
                iso.rebind("score", org.graalvm.polyglot.proxy.ProxyExecutable { args -> args[0].asInt() * 2 })
            }
            assertTrue(seen.isEmpty(), "rebind setup leaked roots: ${seen.map { it.root }}")
            assertEquals(Num(6), iso.eval("score(n=3)"))
            assertEquals(listOf("score"), seen.map { it.root })
            assertEquals("curation.py", seen.single().sourceName)
            seen.clear()
            iso.eval("import functools\nfunctools.update_wrapper(lambda: 0, score)")
            assertTrue(seen.any { it.root == "update_wrapper" }, "guest library execution must still be observed")
        }
    }

    @Test fun programAccumulatesEvaluatedSourcesAndSettleIsASafePoint() {
        js().use { iso ->
            iso.eval("function a(){return 1}", "a.js")
            iso.eval("function b(){return a()+1}", "b.js")
            assertEquals("function a(){return 1}\nfunction b(){return a()+1}\n", iso.program)
            assertEquals(0, iso.pendingGuestActions)
            iso.settle()
            assertEquals(Num(2), iso.call("b"))
        }
    }

    @Test fun statsCountersMove() {
        js().use { iso ->
            assertEquals(IsolateStats(0, 0, 0, 0, 0, 0, 0, 0), iso.stats())
            iso.delegate("id") { it.first() }
            iso.eval("function f(x){return host.call('id', x)}")
            assertEquals(Num(1), iso.call("f", Num(1)))
            assertEquals(Num(2), iso.call("f", Num(2)))
            assertEquals(Num(3), iso.eval("f(3)"))
            val s = iso.stats()
            assertEquals(2L, s.evals)
            assertEquals(2L, s.calls)
            assertEquals(3L, s.hostCalls)
            assertEquals(3L, s.rootEnters)
            assertEquals(0L, s.interrupted)
            assertEquals(0L, s.delegationsMemo); assertEquals(0L, s.delegationsHost); assertEquals(0L, s.refutations)
            assertTrue(iso.nextSeq() < iso.nextSeq())
        }
    }
}
