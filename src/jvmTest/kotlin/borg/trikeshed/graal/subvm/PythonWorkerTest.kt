package borg.trikeshed.graal.subvm

import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.Teleported
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
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
        worker().use { guest ->
            pid = guest.processId
            assertEquals(Teleported.Bool(true), guest.eval("""
                import sqlite3, termios, fcntl, threading, queue, subprocess, pathlib
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
                True
            """.trimIndent()))
        }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
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
}
