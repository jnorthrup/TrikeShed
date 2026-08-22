package borg.trikeshed.graal.subvm

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.HostAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Probe the real Graal bounds on this machine before building on them. */
class GraalBoundsSmokeTest {
    private fun ctx(vararg langs: String, limit: Long = 0): Context {
        val b = Context.newBuilder(*langs)
            .allowHostAccess(HostAccess.NONE)
            .allowIO(org.graalvm.polyglot.io.IOAccess.NONE)
            .allowCreateThread(false)
            .allowNativeAccess(false)
        if (limit > 0) b.resourceLimits(ResourceLimits.newBuilder().statementLimit(limit, null).build())
        return b.build()
    }

    /** After a resource limit trips, the context is cancelled; close(true) is the only clean exit. */
    private inline fun <T> Context.cancelling(block: (Context) -> T): T = try { block(this) } finally { close(true) }

    @Test fun jsEvaluatesAndHonoursStatementLimit() {
        ctx("js").use { c -> assertEquals(42, c.eval("js", "const f=(n)=>n<2?n:f(n-1)+f(n-2); f(9)+8").asInt()) }
        ctx("js", limit = 5_000).cancelling { c ->
            try { c.eval("js", "let i=0; while(true){i++}"); fail("limit not enforced") }
            catch (e: PolyglotException) { assertTrue(e.isResourceExhausted && e.isCancelled, "expected exhausted+cancelled, got ${e.message}") }
        }
    }

    @Test fun pythonEvaluates() {
        ctx("python").use { c -> assertEquals(55, c.eval("python", "def f(n):\n    return n if n<2 else f(n-1)+f(n-2)\nf(10)").asInt()) }
    }

    /**
     * KNOWN BOUND (GraalPy 25.0.2 CE): statementLimit cancellation inside a Python loop dies with
     * "AssertionError: trying to release the GIL with invalid hold count 0". Python must be stopped
     * from OUTSIDE the context — Context.interrupt(timeout) or close(true) from another thread.
     */
    @Test fun pythonIsStoppedByExternalInterruptNotStatementLimit() {
        val c = ctx("python")
        val t = Thread { try { c.eval("python", "i=0\nwhile True:\n    i+=1") } catch (_: PolyglotException) {} }
        t.start(); Thread.sleep(300)
        c.interrupt(java.time.Duration.ofSeconds(5))
        t.join(5_000)
        assertTrue(!t.isAlive, "python loop not interrupted")
        c.close(true)
    }

    /** KNOWN BOUND: "single-threaded" JS means no CONCURRENT access; sequential access from another thread is fine. */
    @Test fun jsAllowsSequentialAccessFromAnotherThread() {
        ctx("js").use { c ->
            c.eval("js", "1")
            var result: Int = -1; var msg: String? = null
            val t = Thread { try { result = c.eval("js", "2").asInt() } catch (e: Throwable) { msg = e.message } }
            t.start(); t.join()
            assertEquals(2, result, "sequential cross-thread access failed: $msg")
        }
    }
}
