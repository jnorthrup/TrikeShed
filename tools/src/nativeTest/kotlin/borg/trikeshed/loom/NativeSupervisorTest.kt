package borg.trikeshed.loom

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*
import kotlin.test.*

/** main.rs's process seams on this target against the kernel: the child's own group, its status, stop_child, the signal pipe. */
class NativeSupervisorTest {
    fun spawn(program: String, vararg args: String): Int = NativeSupervisor.spawn(program.encodeToByteArray(), args.map { it.encodeToByteArray() })

    @Test
    fun childGroup() = runBlocking {
        val exited = spawn("/bin/sh", "-c", "exit 3")
        assertEquals(3, NativeSupervisor.wait(exited))
        // A descriptor without FD_CLOEXEC stays with the supervisor: the child keeps stdio alone.
        val open = open("/dev/null", O_RDONLY)
        assertEquals(77, dup2(open, 77))
        val probe = spawn("/bin/sh", "-c", "test -e /dev/fd/2 && ! test -e /dev/fd/77")
        assertEquals(0, NativeSupervisor.wait(probe))
        close(77)
        close(open)
        // A shell and its background sleep share the group the shell leads; stop_child ends both and reaps the shell.
        val group = spawn("/bin/sh", "-c", "sleep 30 & wait")
        assertEquals(group, getpgid(group))
        val status = async(Dispatchers.Default) { runCatching { NativeSupervisor.wait(group) } }
        delay(200)
        assertTrue(send_group(NativeSupervisor, group, 0))
        stop_child(NativeSupervisor, group, status, SIGTERM)
        assertNull(status.await().getOrThrow())
        assertFalse(send_group(NativeSupervisor, group, 0))
        assertEquals("cannot start supervised program", assertFailsWith<IllegalStateException> { spawn("/nonexistent/program") }.message)
    }

    /** An argument that is not UTF-8 reaches the program as its bytes, as `Command::args` passes an `OsStr`. */
    @Test
    fun argumentBytes() = runBlocking {
        val out = "${getenv("TMPDIR")?.toKString()?.trimEnd('/') ?: "/tmp"}/native-supervisor-${getpid()}"
        val raw = byteArrayOf(0x61, 0xff.toByte(), 0xfe.toByte(), 0x80.toByte(), 0x62)
        val args = listOf("-c", "printf %s \"\$1\" > \"\$2\"", "sh").map { it.encodeToByteArray() } + listOf(raw, out.encodeToByteArray())
        assertEquals(0, NativeSupervisor.wait(NativeSupervisor.spawn("/bin/sh".encodeToByteArray(), args)))
        val fd = open(out, O_RDONLY)
        val read = ByteArray(16)
        val n = read.usePinned { read(fd, it.addressOf(0), read.size.convert()) }
        close(fd)
        unlink(out)
        assertContentEquals(raw, read.copyOf(n.toInt()))
    }

    @Test
    fun signalPipe() = runBlocking {
        val signals = NativeSupervisor.signals()
        raise(SIGHUP)
        raise(SIGTERM)
        assertEquals(listOf(SIGHUP, SIGTERM), withTimeout(5_000) { listOf(signals.receive(), signals.receive()) })
    }
}
