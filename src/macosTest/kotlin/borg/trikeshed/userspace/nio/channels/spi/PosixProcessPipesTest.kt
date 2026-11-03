@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.process.ProcessLineChannel
import kotlinx.coroutines.*
import kotlinx.cinterop.toKString
import platform.posix.*
import kotlin.test.*

class PosixProcessPipesTest {
    @Test fun persistentProcessExchangesAndDrains(): Unit = runBlocking {
        val process = ProcessLineChannel.open(CoroutineScope(coroutineContext + PosixProcessOperations()),
            "/bin/sh", _l["-c", "while IFS= read -r line; do printf '%s\\n' \"\$line\"; done"], timeoutMs = 5_000)
        try {
            assertEquals("first", process.exchange("first"))
            assertEquals("second", process.exchange("second"))
        } finally { process.drain() }
    }

    @Test fun closingAnActiveProcessReapsItAndUnblocksRead(): Unit = runBlocking {
        val pipes = PosixProcessOperations().open("/bin/sh", _l["-c", "printf '%s\\n' \"\$\$\"; exec /bin/sleep 30"])
        val bytes = ByteArray(64)
        val count = withTimeout(5_000) { pipes.read(ProcessOutput.Stdout, bytes) }
        val pid = bytes.decodeToString(0, count).trim().toInt()
        val pending = async { pipes.read(ProcessOutput.Stdout, ByteArray(1)) }
        try {
            withTimeout(5_000) { pipes.close() }
            withTimeout(5_000) { runCatching { pending.await() } }
            assertEquals(-1, kill(pid, 0))
            assertEquals(ESRCH, errno)
            pipes.close()
        } finally { pipes.close() }
    }

    @Test fun cancelledReadTerminatesAndReapsTheProcess(): Unit = runBlocking {
        val pipes = PosixProcessOperations().open("/bin/sh", _l["-c", "printf '%s\\n' \"\$\$\"; exec /bin/sleep 30"])
        val bytes = ByteArray(64)
        val count = withTimeout(5_000) { pipes.read(ProcessOutput.Stdout, bytes) }
        val pid = bytes.decodeToString(0, count).trim().toInt()
        try {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { pipes.read(ProcessOutput.Stdout, ByteArray(1)) }
            pending.cancelAndJoin()
            assertEquals(-1, kill(pid, 0))
            assertEquals(ESRCH, errno)
        } finally { pipes.close() }
    }

    @Test fun childClosingStdinDoesNotSignalTheHost(): Unit = runBlocking {
        val pipes = PosixProcessOperations().open("/bin/sh", _l["-c", "exec 0<&-; printf 'ready\\n'; exec /bin/sleep 30"])
        try {
            val bytes = ByteArray(32)
            val count = withTimeout(5_000) { pipes.read(ProcessOutput.Stdout, bytes) }
            assertEquals("ready\n", bytes.decodeToString(0, count))
            assertFailsWith<IllegalStateException> { pipes.write("payload".encodeToByteArray()) }
        } finally { pipes.close() }
    }

    @Test fun stdoutStderrEnvironmentAndExitRemainSeparate(): Unit = runBlocking {
        val pipes = PosixProcessOperations().open("/bin/sh", _l["-c", "printf '%s' \"\$TRIKESHED_TEST_VALUE\"; printf 'failure' >&2; exit 7"],
            _m["TRIKESHED_TEST_VALUE" j "value"])
        try {
            val out = ByteArray(32)
            val err = ByteArray(32)
            val outSize = withTimeout(5_000) { pipes.read(ProcessOutput.Stdout, out) }
            val errSize = withTimeout(5_000) { pipes.read(ProcessOutput.Stderr, err) }
            assertEquals("value", out.decodeToString(0, outSize))
            assertEquals("failure", err.decodeToString(0, errSize))
            assertEquals(7, withTimeout(5_000) { pipes.awaitExit() })
        } finally { pipes.close() }
    }

    @Test fun childReceivesOnlyTheCuratedAndAllowedEnvironment(): Unit = runBlocking {
        val name = "TRIKESHED_AMBIENT_SECRET_PROBE"
        val previous = getenv(name)?.toKString()
        check(setenv(name, "ambient", 1) == 0)
        try {
            val pipes = PosixProcessOperations().open("/bin/sh", _l["-c",
                "printf '%s|%s|%s' \"\${TRIKESHED_AMBIENT_SECRET_PROBE-unset}\" \"\${TRIKESHED_EXPLICIT_TOKEN_PROBE-unset}\" \"\$TRIKESHED_ALLOWED_PROBE\""],
                _m["TRIKESHED_EXPLICIT_TOKEN_PROBE" j "injected", "TRIKESHED_ALLOWED_PROBE" j "provided"])
            try {
                val out = ByteArray(128)
                val count = withTimeout(5_000) { pipes.read(ProcessOutput.Stdout, out) }
                assertEquals("unset|unset|provided", out.decodeToString(0, count))
                assertEquals(0, withTimeout(5_000) { pipes.awaitExit() })
            } finally { pipes.close() }
        } finally {
            if (previous == null) unsetenv(name) else setenv(name, previous, 1)
        }
    }

    @Test fun missingExecutableFailsOpenAndParentPathFindsShell(): Unit = runBlocking {
        val ops = PosixProcessOperations()
        assertFailsWith<IllegalStateException> { ops.open("/trikeshed-no-such-executable") }
        val pipes = ops.open("sh", _l["-c", "printf '%s' \"\$PATH\""], _m["PATH" j "/trikeshed-no-such-directory"])
        try {
            val out = ByteArray(128)
            val count = withTimeout(5_000) { pipes.read(ProcessOutput.Stdout, out) }
            assertEquals("/trikeshed-no-such-directory", out.decodeToString(0, count))
            assertEquals(0, withTimeout(5_000) { pipes.awaitExit() })
        } finally { pipes.close() }
    }

    @Test fun unrelatedDescriptorIsNotInherited(): Unit = runBlocking {
        val descriptor = open("/dev/null", O_RDONLY)
        check(descriptor > 2)
        try {
            check(fcntl(descriptor, F_SETFD, 0) == 0)
            val pipes = PosixProcessOperations().open("/bin/sh", _l["-c", "if [ -e /dev/fd/$descriptor ]; then exit 42; else exit 0; fi"])
            try { assertEquals(0, withTimeout(5_000) { pipes.awaitExit() }) } finally { pipes.close() }
        } finally { close(descriptor) }
    }
}
