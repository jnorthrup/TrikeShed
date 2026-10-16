package borg.trikeshed.graal.subvm

import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.Teleported
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.hermes.HermesPen
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Exercise stock modules in real child JVMs; no sleeve modules are on their import path. */
@Timeout(value = 360, unit = TimeUnit.SECONDS)
class PythonWorkerTest {
    @TempDir lateinit var directory: Path

    private fun worker(output: ByteArrayOutputStream = ByteArrayOutputStream(),
                       errors: ByteArrayOutputStream = output, path: List<String> = emptyList()) =
        ProcessIsolate("stock-python-${System.nanoTime()}", VmFacet.GRAAL_PYTHON,
            terminalOutput = output, terminalError = errors,
            pythonWorker = PythonWorker(workingDirectory = directory.toString(), pythonPath = path))

    @Test
    fun nativeDatabaseThreadsAndProcessesUseStockModulesAndSurviveAWorkerRestart() {
        var pid = 0L
        val terminal = ByteArrayOutputStream()
        worker(terminal).use { guest ->
            assertEquals(Trust.OWN, guest.trust)
            pid = guest.processId
            assertEquals(Teleported.Bool(true), guest.eval("""
                import sqlite3, termios, fcntl, threading, queue, subprocess, pathlib, sys, hashlib, os
                assert sys.argv == ['python']
                vectors = {
                    1: ('6b', '4a'),
                    8: ('d8bb14d833d59559', 'd7ad0c41ccfe1315'),
                    16: ('cf4ab791c62b8d2b2109c90275287816', 'c60153445dafca3c68639fe63c7c6312'),
                    32: ('bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319',
                         'd63a93ea9b4bd91fe0b20e6d87a26042d2fe45eec5e4934ec6915a1bdb0c9afa'),
                    64: ('ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923',
                         '61ce80f69e6b4300540c5e78254055cb41fefc8dc87af2322bc777db0b9e4f44e03f8cddee63b505060746d5dd86b70a4e3faaf1a2c5d1fd6bf10bed761d4e0d'),
                }
                for size, (abc, abd) in vectors.items():
                    digest = hashlib.blake2b(b'a', digest_size=size)
                    fork = digest.copy()
                    digest.update(b'bc')
                    fork.update(b'bd')
                    assert digest.digest_size == size and len(digest.digest()) == size
                    assert digest.hexdigest() == abc and fork.hexdigest() == abd
                    assert digest.hexdigest() == abc
                assert hashlib.blake2b(b'abc').hexdigest() == vectors[64][0]
                import _sqlite3
                assert not hasattr(sqlite3.Connection, '__trikeshed_sqlite__')
                assert _sqlite3.__file__.endswith(('.so', '.dylib', '.pyd'))
                database = sqlite3.connect('session.db')
                database.execute('CREATE TABLE messages (value TEXT NOT NULL)')
                database.execute('INSERT INTO messages VALUES (?)', ('durable',))
                database.commit()
                database.close()
                values = queue.Queue()
                thread = threading.Thread(target=lambda: values.put('thread-ran'))
                thread.start()
                thread.join()
                assert values.get_nowait() == 'thread-ran'
                assert subprocess.run(['/bin/echo', 'child-ran'], capture_output=True,
                                      text=True, check=True).stdout == 'child-ran' + chr(10)
                with open('owner.lock', 'w') as lease:
                    fcntl.flock(lease, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    fcntl.flock(lease, fcntl.LOCK_UN)
                for index in range(128):
                    os.write(1, ('<stdout-%03d>' % index).encode())
                    os.write(2, ('<stderr-%03d>' % index).encode())
                owner_thread = threading.get_ident()
                affinity_db = sqlite3.connect(':memory:')
                True
            """.trimIndent()))
            repeat(4) {
                assertEquals(Teleported.Num(42), guest.eval("""
                    assert threading.get_ident() == owner_thread
                    affinity_db.execute('SELECT 42').fetchone()[0]
                """.trimIndent()), "Sequential guest calls must preserve Python/SQLite thread affinity")
            }
            guest.eval("affinity_db.close()")
        }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        val ordered = (0 until 128).joinToString("") { "<stdout-%03d><stderr-%03d>".format(it, it) }
        assertTrue(terminal.toString(Charsets.UTF_8).contains(ordered), "The shared terminal must preserve native FD1/FD2 write order")
        assertTrue(Files.size(directory.resolve("session.db")) > 0)
        worker().use { guest ->
            assertEquals(Teleported.Str("durable"), guest.eval("""
                import sqlite3, termios
                database = sqlite3.connect('session.db')
                value = database.execute('SELECT value FROM messages').fetchone()[0]
                database.close()
                value
            """.trimIndent()))
        }
    }

    @Test
    fun stockImportsInputOutputAndBoundedHostDelegatesCrossTheProcessWall() {
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        Files.writeString(directory.resolve("worker_module.py"), "VALUE = 'source-import'\n")
        worker(output, errors, listOf(directory.toString())).use { guest ->
            guest.delegate("echo", 5_000) { arguments -> arguments.single() }
            guest.delegate("expire", 100) {
                Thread.sleep(10_000)
                Teleported.Null
            }
            guest.pushInput("terminal-input\n")
            assertEquals(Teleported.Str("source-import"), guest.eval("""
                import worker_module, sys, json, os
                assert input() == 'terminal-input'
                assert host.call('echo', 'host-result') == 'host-result'
                print('stdout-marker')
                print('stderr-marker', file=sys.stderr)
                os.write(1, b'fd-stdout-marker')
                os.write(2, b'fd-stderr-marker')
                def invoke(value):
                    return host.call('echo', value)
                worker_module.VALUE
            """.trimIndent()))
            assertEquals(Teleported.Num(17), guest.call("invoke", Teleported.Num(17)))
            guest.pushInput("native-input")
            assertEquals(Teleported.Str("native-input"), guest.eval("os.read(0, 12).decode()"))
            val error = assertFailsWith<GuestException> { guest.eval("host.call('expire')") }
            assertTrue(error.message.orEmpty().contains("exceeded"), error.message)
            assertEquals(Teleported.Num(42), guest.eval("6 * 7"))
        }
        assertTrue(output.toString(Charsets.UTF_8).contains("stdout-marker"))
        assertFalse(output.toString(Charsets.UTF_8).contains("stderr-marker"))
        assertTrue(errors.toString(Charsets.UTF_8).contains("stderr-marker"))
        assertTrue(output.toString(Charsets.UTF_8).contains("fd-stdout-marker"))
        assertTrue(errors.toString(Charsets.UTF_8).contains("fd-stderr-marker"))
    }

    @Test
    fun simultaneousPythonThreadsReceiveIndependentBoundedHostReplies() {
        val entered = CountDownLatch(2)
        val finished = CountDownLatch(2)
        worker().use { guest ->
            guest.delegate("parallel", 5_000) { arguments ->
                entered.countDown()
                try {
                    check(entered.await(2, TimeUnit.SECONDS)) { "the protocol serialized concurrent callbacks" }
                    arguments.single()
                } finally { finished.countDown() }
            }
            assertEquals(Teleported.Bool(true), guest.eval("""
                import threading, queue
                replies = queue.Queue()
                def invoke(value):
                    replies.put(host.call('parallel', value))
                threads = [threading.Thread(target=invoke, args=(value,)) for value in ('title', 'main')]
                for thread in threads:
                    thread.start()
                for thread in threads:
                    thread.join()
                assert sorted(replies.get_nowait() for _ in threads) == ['main', 'title']
                True
            """.trimIndent()))
            assertTrue(finished.await(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun deadlineReplyWaitsForTheAdmittedHostCallToFinishCleanup() {
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val replied = CountDownLatch(1)
        val outcome = AtomicReference<Throwable?>()
        worker().use { guest ->
            guest.delegate("cleanup", 100) {
                try { Thread.sleep(10_000); Teleported.Null }
                finally {
                    cleanupEntered.countDown()
                    check(releaseCleanup.await(10, TimeUnit.SECONDS))
                }
            }
            val caller = Thread {
                try { guest.eval("host.call('cleanup')") }
                catch (failure: Throwable) { outcome.set(failure) }
                finally { replied.countDown() }
            }.apply { start() }
            try {
                assertTrue(cleanupEntered.await(10, TimeUnit.SECONDS))
                assertFalse(replied.await(200, TimeUnit.MILLISECONDS),
                    "A deadline reply must not claim completion while the admitted host effect is still cleaning up")
            } finally {
                releaseCleanup.countDown()
                caller.join(10_000)
            }
            assertFalse(caller.isAlive)
            val failure = assertIs<GuestException>(outcome.get())
            assertTrue(failure.message.orEmpty().contains("exceeded"), failure.message)
            assertEquals(Teleported.Num(42), guest.eval("6 * 7"))
        }
    }

    @Test
    fun closeJoinsTheAdmittedCallbackAndItsGuestReply() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val result = AtomicReference<Teleported?>()
        val evaluationFailure = AtomicReference<Throwable?>()
        val closeFailure = AtomicReference<Throwable?>()
        val guest = worker()
        val pid = guest.processId
        var caller: Thread? = null
        var closer: Thread? = null
        try {
            guest.delegate("held") {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                Teleported.Num(42)
            }
            caller = Thread {
                try { result.set(guest.eval("host.call('held')")) }
                catch (failure: Throwable) { evaluationFailure.set(failure) }
            }.apply { start() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            closer = Thread {
                closing.countDown()
                try { guest.close() }
                catch (failure: Throwable) { closeFailure.set(failure) }
                finally { closed.countDown() }
            }.apply { start() }
            assertTrue(closing.await(1, TimeUnit.SECONDS))
            assertFalse(closed.await(200, TimeUnit.MILLISECONDS), "Close returned before its admitted host effect")
            release.countDown()
            caller.join(10_000)
            assertFalse(caller.isAlive)
            assertNull(evaluationFailure.get())
            assertEquals(Teleported.Num(42), result.get(), "The admitted guest call must receive its actual reply")
            assertTrue(closed.await(10, TimeUnit.SECONDS))
            assertNull(closeFailure.get())
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        } finally {
            release.countDown()
            caller?.join(10_000)
            closer?.join(10_000)
            if (closer == null) guest.close()
        }
    }

    @Test
    fun modelDeadlineIncludesLeaseResolutionAndJoinsItsCancellation() {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        worker().use { guest ->
            HermesPen(ConfixBlackboard.empty(), modelMuxProvider = {
                entered.countDown()
                try { awaitCancellation() }
                finally { finished.countDown() }
            }).install(guest)
            val failure = assertFailsWith<GuestException> {
                guest.eval("host.call('mux_completion', 'model', '{\"messages\":[]}', 100)")
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(finished.await(1, TimeUnit.SECONDS), "The lease resolver must finish before the failure reply")
            assertTrue(failure.message.orEmpty().contains("100"), failure.message)
            assertEquals(Teleported.Num(42), guest.eval("6 * 7"))
        }
    }
}
