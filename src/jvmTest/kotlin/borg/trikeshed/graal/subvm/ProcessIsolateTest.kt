package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.Teleported

import borg.trikeshed.vm.Teleported.Num
import borg.trikeshed.vm.Teleported.Real
import borg.trikeshed.vm.Teleported.Str
import borg.trikeshed.pointcut.VmFacet
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [ProcessIsolate]: the same [GuestIsolate] contract behind a process wall (a child JVM running
 * [SubVmMain] on this JVM's `java.class.path`).
 *
 * Teleported values ride the envelope as canonical strings ([Teleported.parseCanonical]), so `Num`
 * survives the wall. The envelope itself is `JsonSupport`; see [sourcesWithQuotesOrNewlinesCrossTheWall]
 * for what that means for the `source` field.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ProcessIsolateTest {
    private val classpathNote: String
        get() {
            val cp = System.getProperty("java.class.path") ?: ""
            return "java.class.path: ${cp.length} chars, ${cp.split(java.io.File.pathSeparator).size} entries, " +
                "polyglot=${cp.contains("polyglot")}, pathingJar=${cp.endsWith(".jar") && !cp.contains(java.io.File.pathSeparator)}"
        }

    private fun subVmChildren(): List<ProcessHandle> = ProcessHandle.current().children().filter { h ->
        val info = h.info()
        val cmd = info.commandLine().orElse("") + " " + info.arguments().map { it.joinToString(" ") }.orElse("")
        cmd.contains("SubVmMain") || cmd.isBlank()
    }.toList()

    private fun spawnJs(budget: Budget = Budget(), replyTimeoutMillis: Long = 30_000) =
        ProcessIsolate("p-${System.nanoTime()}", VmFacet.GRAAL_JS, budget, replyTimeoutMillis = replyTimeoutMillis)

    @Test fun jsEvalReturnsAnIntegerAcrossTheWall() {
        spawnJs().use { iso ->
            assertEquals(Trust.UNTRUSTED, iso.trust)
            assertTrue(iso.isAlive, classpathNote)
            assertEquals(Num(3), iso.eval("1+2"), "Num must survive the wall ($classpathNote)")
            assertEquals(Str("hi"), iso.eval("'h'+'i'"))
            assertEquals(Teleported.Bool(true), iso.eval("1<2"))
            assertEquals(Teleported.Null, iso.eval("null"))
            assertEquals(4L, iso.stats().evals)   // 1+2, 'h'+'i', 1<2, null
        }
    }

    @Test fun jsDefineAndCallARootAcrossTheWall() {
        spawnJs().use { iso ->
            iso.eval("function add(a,b){return a+b}")
            assertEquals(Num(5), iso.call("add", Num(2), Num(3)))
            assertEquals(Str("ab"), iso.call("add", Str("a"), Str("b")))
            assertEquals(Str("hi"), iso.eval("'h'+'i'"))
            val e = assertFailsWith<GuestException> { iso.call("nope") }
            assertEquals(GuestFailure.GUEST_ERROR, e.kind, e.message)
            val boom = assertFailsWith<GuestException> { iso.eval("throw new Error('kaboom')") }
            assertEquals(GuestFailure.GUEST_ERROR, boom.kind, boom.message)
            assertTrue(iso.isAlive)
            assertEquals(Num(7), iso.call("add", Num(3), Num(4)))
            assertEquals(Real(2.5), iso.call("add", Num(2), Real(0.5)))
            assertEquals(5L, iso.stats().calls)   // add, add, nope, add, add(2, 0.5)
        }
    }

    @Test fun jsHostDelegateAcrossTheWall() {
        spawnJs().use { iso ->
            iso.delegate("double") { args -> Num(2 * (args[0] as Num).v) }
            val r = try { iso.eval("host.call('double', 4)") }
                catch (e: GuestException) { fail("guest → host delegation did not cross the wall: ${e.kind}: ${e.message} ($classpathNote)") }
            assertEquals(Num(8), r)
            assertEquals(1L, iso.stats().hostCalls)
            iso.delegate("pair") { args -> Teleported.Obj(mapOf("sum" to Num((args[0] as Num).v + (args[1] as Num).v), "xs" to Teleported.Arr(args))) }
            assertEquals(Num(7), iso.eval("host.call('pair', 3, 4).sum"))
            assertEquals(Num(2), iso.eval("host.call('pair', 3, 4).xs.length"))
            iso.delegate("len") { args -> Num((args[0] as Teleported.Arr).v.size.toLong()) }
            assertEquals(Num(3), iso.eval("host.call('len', [1,2,3])"))
            // a host delegate that throws is a guest error on the child side; the wall stays up
            iso.delegate("boom") { error("host side failure") }
            val e = assertFailsWith<GuestException> { iso.eval("host.call('boom')") }
            assertEquals(GuestFailure.GUEST_ERROR, e.kind, e.message)
            assertEquals(Num(8), iso.eval("host.call('double', 4)"))
            assertTrue(iso.isAlive)
        }
    }

    /**
     * The envelope is `JsonSupport`: `stringify` escapes a source's `"` and newlines, but the reader
     * hands the escapes through verbatim, so the guest receives `\"` and `\n` as two characters. Every
     * other test here keeps sources single-line and single-quoted; this one does not.
     */
    @Test fun sourcesWithQuotesOrNewlinesCrossTheWall() {
        spawnJs().use { iso ->
            assertEquals(Str("ab"), iso.eval("\"a\" + \"b\""), "double quotes in a source")
            assertEquals(Num(3), iso.eval("1 +\n2"), "a newline in a source")
            iso.eval("function greet(who) {\n  return \"hi \" + who;\n}")
            assertEquals(Str("hi x"), iso.call("greet", Str("x")))
        }
    }

    @Test fun jsStatementLimitExhaustsTheChildIsolate() {
        spawnJs(Budget(statements = 100_000)).use { iso ->
            assertEquals(Num(2), iso.eval("1+1"))
            val t0 = System.nanoTime()
            val e = assertFailsWith<GuestException> { iso.eval("let i=0; while(true){i++}") }
            assertEquals(GuestFailure.EXHAUSTED, e.kind, e.message)
            assertTrue((System.nanoTime() - t0) < TimeUnit.SECONDS.toNanos(20))
            assertFalse(iso.isAlive)
            val dead = assertFailsWith<GuestException> { iso.eval("1") }
            assertEquals(GuestFailure.DEAD, dead.kind)
            assertFalse(iso.interrupt())
        }
    }

    @Test fun interruptDestroysAHungChild() {
        val before = subVmChildren().size
        val iso = spawnJs(Budget(statements = 0, wallMillis = 0), replyTimeoutMillis = 8_000)
        try {
            assertEquals(Num(1), iso.eval("1"))
            assertTrue(subVmChildren().size >= before + 1, "child JVM visible as a direct child process")
            val outcome = AtomicReference<Throwable?>()
            val entered = CountDownLatch(1)
            val t = Thread({
                entered.countDown()
                try { iso.eval("while(true){}") } catch (e: Throwable) { outcome.set(e) }
            }, "hung-guest").apply { isDaemon = true }
            t.start(); entered.await()
            Thread.sleep(500)
            val t0 = System.nanoTime()
            assertTrue(iso.interrupt())
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms < 6_500, "interrupt() returned after ${ms}ms")
            assertFalse(iso.isAlive, "hung child must be destroyed")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (subVmChildren().size > before && System.nanoTime() < deadline) Thread.sleep(50)
            assertEquals(before, subVmChildren().size, "child process gone after destroyForcibly")
            t.join(20_000)
            assertFalse(t.isAlive, "eval thread released after the child died")
            val e = outcome.get()
            assertIs<GuestException>(e, "hung eval ended with $e")
            assertTrue(e.kind == GuestFailure.DEAD || e.kind == GuestFailure.INTERRUPTED, e.message)
        } finally { iso.close() }
    }

    @Test fun closeTerminatesTheChildProcess() {
        val before = subVmChildren().size
        val iso = spawnJs()
        assertEquals(Num(4), iso.eval("2+2"))
        assertTrue(subVmChildren().size >= before + 1)
        iso.close()
        assertFalse(iso.isAlive)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (subVmChildren().size > before && System.nanoTime() < deadline) Thread.sleep(50)
        assertEquals(before, subVmChildren().size, "child process gone after close()")
        val e = assertFailsWith<GuestException> { iso.eval("1") }
        assertEquals(GuestFailure.DEAD, e.kind)
    }

    @Test fun pythonEvalAndCallAcrossTheWall() {
        ProcessIsolate("pp-${System.nanoTime()}", VmFacet.GRAAL_PYTHON).use { iso ->
            assertEquals(VmFacet.GRAAL_PYTHON, iso.facet)
            assertEquals(Num(3), iso.eval("1+2"))
            assertEquals(Str("hi"), iso.eval("'h'+'i'"))
            iso.eval("def add(a,b): return a+b")
            assertEquals(Num(5), iso.call("add", Num(2), Num(3)))
            assertEquals(Str("ab"), iso.call("add", Str("a"), Str("b")))
            assertEquals(Teleported.Arr(listOf(Num(1), Num(2))), iso.eval("[1,2]"))
            val e = assertFailsWith<GuestException> { iso.call("nope") }
            assertEquals(GuestFailure.GUEST_ERROR, e.kind)
            assertTrue(iso.isAlive)
        }
    }

    @Test fun pythonHostDelegateAcrossTheWall() {
        ProcessIsolate("ph-${System.nanoTime()}", VmFacet.GRAAL_PYTHON).use { iso ->
            iso.delegate("double") { args -> Num(2 * (args[0] as Num).v) }
            val r = try { iso.eval("host.call('double', 4)") }
                catch (e: GuestException) { fail("guest → host delegation did not cross the wall: ${e.kind}: ${e.message} ($classpathNote)") }
            assertEquals(Num(8), r)
            assertEquals(1L, iso.stats().hostCalls)
        }
    }
}
