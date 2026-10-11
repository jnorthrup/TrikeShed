package borg.trikeshed.loom

import kotlinx.coroutines.*
import platform.posix.*
import kotlin.test.*

/** main.rs's process seams on this target against the kernel: the child's own group, its status, stop_child, the signal pipe. */
class NativeSupervisorTest {
    @Test
    fun childGroup() = runBlocking {
        val exited = NativeSupervisor.spawn("/bin/sh", listOf("-c", "exit 3"))
        assertEquals(3, NativeSupervisor.wait(exited))
        // A descriptor without FD_CLOEXEC stays with the supervisor: the child keeps stdio alone.
        val open = open("/dev/null", O_RDONLY)
        assertEquals(77, dup2(open, 77))
        val probe = NativeSupervisor.spawn("/bin/sh", listOf("-c", "test -e /dev/fd/2 && ! test -e /dev/fd/77"))
        assertEquals(0, NativeSupervisor.wait(probe))
        close(77)
        close(open)
        // A shell and its background sleep share the group the shell leads; stop_child ends both and reaps the shell.
        val group = NativeSupervisor.spawn("/bin/sh", listOf("-c", "sleep 30 & wait"))
        assertEquals(group, getpgid(group))
        val status = async(Dispatchers.Default) { runCatching { NativeSupervisor.wait(group) } }
        delay(200)
        assertTrue(send_group(NativeSupervisor, group, 0))
        stop_child(NativeSupervisor, group, status, SIGTERM)
        assertNull(status.await().getOrThrow())
        assertFalse(send_group(NativeSupervisor, group, 0))
        assertEquals("cannot start supervised program", assertFailsWith<IllegalStateException> { NativeSupervisor.spawn("/nonexistent/program", listOf()) }.message)
    }

    @Test
    fun signalPipe() = runBlocking {
        val signals = NativeSupervisor.signals()
        raise(SIGHUP)
        raise(SIGTERM)
        assertEquals(listOf(SIGHUP, SIGTERM), withTimeout(5_000) { listOf(signals.receive(), signals.receive()) })
    }
}
