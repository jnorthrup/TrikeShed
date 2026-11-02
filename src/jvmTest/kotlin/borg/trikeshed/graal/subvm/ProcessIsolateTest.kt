package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.Teleported

import borg.trikeshed.vm.Teleported.Num
import borg.trikeshed.vm.Teleported.Real
import borg.trikeshed.vm.Teleported.Str
import borg.trikeshed.pointcut.VmFacet
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [ProcessIsolate]: the same [GuestIsolate] contract behind a process wall (a child JVM running
 * [SubVmMain] on this JVM's `java.class.path`).
 *
 * Teleported values ride the envelope as canonical strings ([Teleported.parseCanonical]), so `Num`
 * survives the wall. The envelope itself is `reify`; see [sourcesWithQuotesOrNewlinesCrossTheWall]
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

    @Test fun restrictedJsHostLeasePausesGuestWallBudget() {
        // This lane deliberately uses wall-only guest budgeting: the existing JS
        // statement-limited lane does not start its wall watchdog.
        spawnJs(Budget(statements = 0, wallMillis = 1_500), replyTimeoutMillis = 8_000).use { iso ->
            assertTrue(iso.restrictedJsLeaseCapable)
            assertEquals(Num(2), iso.eval("1+1")) // initialize before the timed host crossing
            iso.delegate("slow", 4_000) { Thread.sleep(2_000); Num(7) }
            assertEquals(Num(7), iso.eval("host.call('slow')"))
            assertEquals(Num(3), iso.eval("1+2"))
            assertEquals(Str("undefined"), iso.eval("typeof require"))
            assertEquals(Str("undefined"), iso.eval("typeof process"))
            assertFailsWith<GuestException> { iso.eval("Java.type('java.lang.System')") }
        }
    }

    @Test fun restrictedJsExpiredHostLeaseDoesNotLeaveAStaleReply() {
        spawnJs(Budget(statements = 0, wallMillis = 1_500), replyTimeoutMillis = 8_000).use { iso ->
            assertEquals(Num(1), iso.eval("1"))
            val interrupted = CountDownLatch(1)
            iso.delegate("timeout", 400) {
                try { Thread.sleep(5_000); Num(0) }
                catch (e: InterruptedException) { interrupted.countDown(); throw e }
            }
            assertFailsWith<GuestException> { iso.eval("host.call('timeout')") }
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "cooperative host callback was not interrupted")
            // A failed leased crossing revokes the child, so a late reply cannot
            // be mistaken for another operation on the same isolate.
            assertFalse(iso.isAlive)
            assertFailsWith<GuestException> { iso.eval("19") }
        }
    }

    @Test fun restrictedJsGuestWallAndRegistrationFailClosed() {
        spawnJs(Budget(statements = 0, wallMillis = 1_000), replyTimeoutMillis = 6_000).use { iso ->
            assertTrue(iso.restrictedJsLeaseCapable)
            iso.delegate("value") { Num(11) }
            // A rejected registration cannot replace the prior delegate.
            assertFailsWith<IllegalArgumentException> { iso.delegate("value", 6_000) { Num(99) } }
            assertEquals(Num(11), iso.eval("host.call('value')"))
            iso.delegate("leased", 3_000) { Num(1) }
            assertFailsWith<IllegalArgumentException> { iso.delegate("leased") { Num(2) } }
            assertEquals(Num(1), iso.eval("host.call('leased')"))
            assertFailsWith<GuestException> { iso.eval("while(true){}") }
        }
    }

    @Test fun statementLimitedJsIsNotMisrepresentedAsWallLeased() {
        spawnJs().use { iso ->
            assertFalse(iso.restrictedJsLeaseCapable)
            assertFailsWith<IllegalArgumentException> { iso.delegate("leased", 1_000) { Num(1) } }
            assertEquals(Num(2), iso.eval("1+1"))
        }
    }

    @Test fun nodeLauncherCannotRegisterRestrictedJsLeases() {
        val node = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)
            .map { java.io.File(it, "node") }.firstOrNull { it.isFile && it.canExecute() }
        assumeTrue(node != null, "Node launcher is unavailable")
        ProcessIsolate("node-lease-${System.nanoTime()}", VmFacet.GRAAL_JS,
            budget = Budget(statements = 0, wallMillis = 1_000), nodeLauncher = node,
            replyTimeoutMillis = 6_000).use { iso ->
            assertFalse(iso.restrictedJsLeaseCapable)
            assertFailsWith<IllegalArgumentException> { iso.delegate("leased", 1_000) { Num(1) } }
            assertEquals(Num(2), iso.eval("1+1"))
        }
    }

    @Test fun restrictedJsChildDeathWaitsForAdmittedHostCleanup() {
        val iso = spawnJs(Budget(statements = 0, wallMillis = 1_500), replyTimeoutMillis = 8_000)
        val entered = CountDownLatch(1)
        val cleaning = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outcome = AtomicReference<Throwable?>()
        var caller: Thread? = null
        try {
            iso.eval("1")
            iso.delegate("work", 1_000) {
                entered.countDown()
                try { Thread.sleep(30_000); Num(1) }
                catch (e: InterruptedException) {
                    cleaning.countDown()
                    while (true) try { release.await(); break } catch (_: InterruptedException) { }
                    throw e
                }
            }
            caller = Thread({ try { iso.eval("host.call('work')") } catch (e: Throwable) { outcome.set(e) } }).apply {
                isDaemon = true; start()
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(ProcessHandle.of(iso.processId).orElseThrow().destroyForcibly())
            assertTrue(cleaning.await(8, TimeUnit.SECONDS), "host cleanup did not begin")
            assertTrue(caller.isAlive, "crossing returned before admitted host cleanup")
            release.countDown()
            caller.join(10_000)
            assertFalse(caller.isAlive)
            assertIs<GuestException>(outcome.get())
            assertFalse(iso.isAlive)
            assertFailsWith<GuestException> { iso.eval("1") }
        } finally {
            release.countDown()
            caller?.join(10_000)
            iso.close()
        }
    }

    @Test fun restrictedJsInterruptedCallerRevokesAndWaitsForHostCleanup() {
        val iso = spawnJs(Budget(statements = 0, wallMillis = 1_500), replyTimeoutMillis = 8_000)
        val entered = CountDownLatch(1)
        val cleaning = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outcome = AtomicReference<Throwable?>()
        val interruptRestored = java.util.concurrent.atomic.AtomicBoolean(false)
        var caller: Thread? = null
        try {
            iso.eval("1")
            iso.delegate("work", 1_000) {
                entered.countDown()
                try { Thread.sleep(30_000); Num(1) }
                catch (e: InterruptedException) {
                    cleaning.countDown()
                    while (true) try { release.await(); break } catch (_: InterruptedException) { }
                    throw e
                }
            }
            caller = Thread({
                try { iso.eval("host.call('work')") }
                catch (e: Throwable) { outcome.set(e); interruptRestored.set(Thread.currentThread().isInterrupted) }
            }).apply { isDaemon = true; start() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            caller.interrupt()
            assertTrue(cleaning.await(8, TimeUnit.SECONDS), "host cleanup did not begin")
            assertTrue(caller.isAlive, "interrupted caller returned before admitted host cleanup")
            release.countDown()
            caller.join(10_000)
            assertFalse(caller.isAlive)
            assertIs<InterruptedException>(outcome.get())
            assertTrue(interruptRestored.get(), "caller interrupt status was not restored")
            assertFalse(iso.isAlive)
            assertFailsWith<GuestException> { iso.eval("1") }
        } finally {
            release.countDown()
            caller?.join(10_000)
            iso.close()
        }
    }

    @Test fun restrictedJsBoundsPreviouslyRegisteredOrdinaryDelegate() {
        val iso = spawnJs(Budget(statements = 0, wallMillis = 1_000), replyTimeoutMillis = 6_000)
        val interrupted = CountDownLatch(1)
        try {
            iso.eval("1")
            // Pi installs its catalogue before the first leased delegate.
            iso.delegate("ordinary") {
                try { Thread.sleep(30_000); Num(0) }
                catch (e: InterruptedException) { interrupted.countDown(); throw e }
            }
            iso.delegate("leased", 3_000) { Num(1) }
            assertFailsWith<GuestException> { iso.eval("host.call('ordinary')") }
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "ordinary callback was not interrupted")
            assertFalse(iso.isAlive)
        } finally { iso.close() }
    }

    @Test fun restrictedJsRejectsSynchronousHostReentryBeforeTakingIsolateLock() {
        val iso = spawnJs(Budget(statements = 0, wallMillis = 1_500), replyTimeoutMillis = 8_000)
        try {
            iso.eval("1")
            iso.delegate("reenter", 4_000) { iso.eval("1+1") }
            val start = System.nanoTime()
            assertFailsWith<GuestException> { iso.eval("host.call('reenter')") }
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(7), "host callback deadlocked on the isolate")
            assertFalse(iso.isAlive)
        } finally { iso.close() }
    }

    @Test fun restrictedJsRejectsCrossThreadReentryWithoutBlockingHostCleanup() {
        val iso = spawnJs(Budget(statements = 0, wallMillis = 1_500), replyTimeoutMillis = 8_000)
        try {
            iso.eval("1")
            iso.delegate("reenter", 4_000) {
                val result = AtomicReference<Throwable?>()
                val thread = Thread({
                    try { iso.eval("2") } catch (e: Throwable) { result.set(e) }
                }).apply { isDaemon = true; start() }
                thread.join(2_000)
                check(!thread.isAlive) { "cross-thread reentry blocked on the isolate" }
                check(result.get() is IllegalStateException) { "cross-thread reentry was not refused" }
                Num(42)
            }
            assertEquals(Num(42), iso.eval("host.call('reenter')"))
            assertEquals(Num(2), iso.eval("1+1"))
        } finally { iso.close() }
    }

    @Test fun restrictedJsRejectsCrossThreadInterruptWhileCrossingIsActive() {
        val iso = spawnJs(Budget(statements = 0, wallMillis = 1_500), replyTimeoutMillis = 8_000)
        try {
            iso.eval("1")
            iso.delegate("reenter", 4_000) {
                val result = AtomicReference<Throwable?>()
                val thread = Thread({ try { iso.interrupt() } catch (e: Throwable) { result.set(e) } })
                    .apply { isDaemon = true; start() }
                thread.join(2_000)
                check(!thread.isAlive) { "cross-thread interrupt blocked on the isolate" }
                check(result.get() is IllegalStateException) { "cross-thread interrupt was not refused" }
                Num(42)
            }
            assertEquals(Num(42), iso.eval("host.call('reenter')"))
            assertEquals(Num(2), iso.eval("1+1"))
        } finally { iso.close() }
    }

    @Test fun restrictedJsBusyLifecycleCallsFailWithoutClosingTheChild() {
        val iso = spawnJs(Budget(statements = 0, wallMillis = 1_500), replyTimeoutMillis = 8_000)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outcome = AtomicReference<Teleported?>()
        val failure = AtomicReference<Throwable?>()
        var caller: Thread? = null
        try {
            iso.eval("1")
            iso.delegate("hold", 4_000) { entered.countDown(); release.await(); Num(7) }
            caller = Thread({
                try { outcome.set(iso.eval("host.call('hold')")) } catch (e: Throwable) { failure.set(e) }
            }).apply { isDaemon = true; start() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> { iso.close() }
            assertFailsWith<IllegalStateException> { iso.interrupt() }
            assertFailsWith<IllegalStateException> { iso.pushInput("input") }
            assertTrue(iso.isAlive, "busy close must not silently close the child")
            release.countDown()
            caller.join(10_000)
            assertFalse(caller.isAlive)
            assertNull(failure.get())
            assertEquals(Num(7), outcome.get())
        } finally {
            release.countDown()
            caller?.join(10_000)
            iso.close()
        }
    }

    @Test fun restrictedJsFirstLeaseCannotRaceWithOrdinaryDelegateReplacement() {
        val iso = spawnJs(Budget(statements = 0, wallMillis = 1_500), replyTimeoutMillis = 8_000)
        val lock = ProcessIsolate::class.java.getDeclaredField("lock").let {
            it.isAccessible = true
            it.get(iso) as java.util.concurrent.locks.ReentrantLock
        }
        val outcome = AtomicReference<Throwable?>()
        var caller: Thread? = null
        try {
            iso.eval("1")
            lock.lock()
            try {
                caller = Thread({
                    try { iso.delegate("race") { Num(99) } } catch (e: Throwable) { outcome.set(e) }
                }).apply { isDaemon = true; start() }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!lock.hasQueuedThread(caller) && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue(lock.hasQueuedThread(caller), "ordinary registration did not queue")
                // Reentrant acquisition lets this registration activate the leased lane
                // before the queued ordinary registration gets the crossing lock.
                iso.delegate("race", 4_000) { Num(1) }
            } finally { lock.unlock() }
            caller.join(10_000)
            assertFalse(caller.isAlive)
            assertIs<IllegalArgumentException>(outcome.get())
            assertEquals(Num(1), iso.eval("host.call('race')"))
        } finally {
            caller?.join(10_000)
            iso.close()
        }
    }

    @Test fun restrictedJsOuterDeadlineRevokesAndWaitsForAdmittedHostCleanup() {
        val iso = spawnJs(Budget(statements = 0, wallMillis = 1_500), replyTimeoutMillis = 7_000)
        val entered = CountDownLatch(1)
        val cleaning = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val outcome = AtomicReference<Throwable?>()
        var caller: Thread? = null
        try {
            iso.eval("1")
            iso.delegate("work", 4_000) {
                if (calls.incrementAndGet() <= 4) { Thread.sleep(900); Num(1) }
                else {
                    entered.countDown()
                    try { Thread.sleep(30_000); Num(1) }
                    catch (e: InterruptedException) {
                        cleaning.countDown()
                        while (true) try { release.await(); break } catch (_: InterruptedException) { }
                        throw e
                    }
                }
            }
            caller = Thread({
                try { iso.eval("for (let i=0;i<5;i++) host.call('work'); 19") } catch (e: Throwable) { outcome.set(e) }
            }).apply { isDaemon = true; start() }
            assertTrue(entered.await(8, TimeUnit.SECONDS), "final host call was not admitted")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (iso.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
            assertFalse(iso.isAlive, "outer deadline did not revoke the child")
            assertTrue(cleaning.await(8, TimeUnit.SECONDS), "host cleanup did not begin")
            assertTrue(caller.isAlive, "crossing returned before admitted host cleanup")
            release.countDown()
            caller.join(10_000)
            assertFalse(caller.isAlive)
            assertIs<GuestException>(outcome.get())
            assertFailsWith<GuestException> { iso.eval("19") }
        } finally {
            release.countDown()
            caller?.join(10_000)
            iso.close()
        }
    }

    /**
     * The envelope is `reify`: `stringify` escapes a source's `"` and newlines, but the reader
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
        val iso = spawnJs(Budget(statements = 0, wallMillis = 0), replyTimeoutMillis = 8_000)
        var caller: Thread? = null
        try {
            assertEquals(Num(1), iso.eval("1"))
            val child = ProcessHandle.of(iso.processId).orElseThrow()
            assertTrue(child.isAlive, "the isolate's exact child process is alive")
            assertEquals(ProcessHandle.current().pid(), child.parent().orElseThrow().pid(), "isolate child belongs to this JVM")
            val outcome = AtomicReference<Throwable?>()
            val entered = CountDownLatch(1)
            val t = Thread({
                entered.countDown()
                try { iso.eval("while(true){}") } catch (e: Throwable) { outcome.set(e) }
            }, "hung-guest").apply { isDaemon = true }
            caller = t
            t.start(); entered.await()
            Thread.sleep(500)
            val t0 = System.nanoTime()
            assertTrue(iso.interrupt())
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms < 6_500, "interrupt() returned after ${ms}ms")
            assertFalse(iso.isAlive, "hung child must be destroyed")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (child.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
            assertFalse(child.isAlive, "the isolate's exact child process is gone after interrupt")
            t.join(20_000)
            assertFalse(t.isAlive, "eval thread released after the child died")
            val e = outcome.get()
            assertIs<GuestException>(e, "hung eval ended with $e")
            assertTrue(e.kind == GuestFailure.DEAD || e.kind == GuestFailure.INTERRUPTED, e.message)
        } finally {
            if (caller?.isAlive == true) runCatching { iso.interrupt() }
            caller?.join(20_000)
            iso.close()
        }
    }

    @Test fun closeTerminatesTheChildProcess() {
        val iso = spawnJs()
        var closed = false
        try {
            assertEquals(Num(4), iso.eval("2+2"))
            val child = ProcessHandle.of(iso.processId).orElseThrow()
            assertTrue(child.isAlive, "the isolate's exact child process is alive")
            assertEquals(ProcessHandle.current().pid(), child.parent().orElseThrow().pid(), "isolate child belongs to this JVM")
            iso.close()
            closed = true
            assertFalse(iso.isAlive)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (child.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
            assertFalse(child.isAlive, "the isolate's exact child process is gone after close()")
            val e = assertFailsWith<GuestException> { iso.eval("1") }
            assertEquals(GuestFailure.DEAD, e.kind)
        } finally { if (!closed) iso.close() }
    }

    @Test fun closePreservesAnAdmittedHostCallBeyondTheFormerKillGrace() {
        val iso = spawnJs()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val result = AtomicReference<Teleported?>()
        val evaluationFailure = AtomicReference<Throwable?>()
        val closeFailure = AtomicReference<Throwable?>()
        var caller: Thread? = null
        var closer: Thread? = null
        try {
            iso.delegate("held") {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                Num(42)
            }
            caller = Thread {
                try { result.set(iso.eval("host.call('held')")) }
                catch (failure: Throwable) { evaluationFailure.set(failure) }
            }.apply { start() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            closer = Thread {
                closing.countDown()
                try { iso.close() }
                catch (failure: Throwable) { closeFailure.set(failure) }
                finally { closed.countDown() }
            }.apply { start() }
            assertTrue(closing.await(1, TimeUnit.SECONDS))
            assertFalse(closed.await(1_500, TimeUnit.MILLISECONDS))
            assertTrue(iso.isAlive, "Ordinary close must not kill the child while admitted work remains")
            release.countDown()
            caller.join(10_000)
            assertFalse(caller.isAlive)
            assertEquals(null, evaluationFailure.get())
            assertEquals(Num(42), result.get())
            assertTrue(closed.await(10, TimeUnit.SECONDS))
            assertEquals(null, closeFailure.get())
            assertFalse(ProcessHandle.of(iso.processId).map { it.isAlive }.orElse(false))
        } finally {
            release.countDown()
            caller?.join(10_000)
            closer?.join(10_000)
            if (closer == null) iso.close()
        }
    }

    @Test fun closeReleasesRestrictedTerminalInputBeforeJoiningTheGuest() {
        val iso = ProcessIsolate("stdin-close-${System.nanoTime()}", VmFacet.GRAAL_PYTHON)
        val entered = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val evaluationFailure = AtomicReference<Throwable?>()
        val closeFailure = AtomicReference<Throwable?>()
        var caller: Thread? = null
        var closer: Thread? = null
        try {
            iso.delegate("entered") { entered.countDown(); Teleported.Null }
            caller = Thread {
                try { iso.eval("host.call('entered')\ninput()") }
                catch (failure: Throwable) { evaluationFailure.set(failure) }
                finally { ended.countDown() }
            }.apply { start() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            closer = Thread {
                try { iso.close() }
                catch (failure: Throwable) { closeFailure.set(failure) }
                finally { closed.countDown() }
            }.apply { start() }
            assertTrue(ended.await(10, TimeUnit.SECONDS), "Close must send terminal EOF before waiting for input()")
            val failure = assertIs<GuestException>(evaluationFailure.get())
            assertEquals(GuestFailure.GUEST_ERROR, failure.kind, "Terminal EOF is not process death")
            assertTrue(failure.message.orEmpty().contains("EOF"), failure.message)
            assertTrue(closed.await(10, TimeUnit.SECONDS))
            assertEquals(null, closeFailure.get())
            assertFalse(ProcessHandle.of(iso.processId).map { it.isAlive }.orElse(false))
        } finally {
            if (caller?.isAlive == true || closer?.isAlive == true) iso.interrupt()
            caller?.join(10_000)
            closer?.join(10_000)
            if (closer == null) iso.close()
        }
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
