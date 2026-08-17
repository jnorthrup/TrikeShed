package borg.trikeshed.userspace.nio.ebpf

import borg.trikeshed.userspace.containment.ContainmentPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ProbeTest {
    @Test
    fun verify_tracepoint_constants() {
        assertEquals("sys_enter_socket", Tracepoints.SYS_ENTER_SOCKET)
        assertEquals("sys_enter_connect", Tracepoints.SYS_ENTER_CONNECT)
        assertEquals("sys_enter_execve", Tracepoints.SYS_ENTER_EXECVE)
        assertEquals("sys_enter_openat", Tracepoints.SYS_ENTER_OPENAT)
    }

    @Test
    fun verify_containment_hooks() {
        val hooks = ContainmentHooks.hooks
        assertTrue(hooks.containsKey(Tracepoints.SYS_ENTER_SOCKET))
        assertTrue(hooks.containsKey(Tracepoints.SYS_ENTER_CONNECT))
        assertTrue(hooks.containsKey(Tracepoints.SYS_ENTER_EXECVE))

        val strictPolicy = ContainmentPolicy.MAXIMUM
        // Usually, blockedsyscalls contains these by default if we populate it in test,
        // but let's test our lambda structure:

        // Since ContainmentPolicy is a data class, we can just create a custom one for testing.
        val blockedSocketPolicy = ContainmentPolicy(
            layer3Syscall = borg.trikeshed.userspace.containment.Layer3SyscallPolicy(
                blockedSyscalls = setOf("socket", "connect")
            )
        )

        assertFalse(hooks[Tracepoints.SYS_ENTER_SOCKET]!!(blockedSocketPolicy))
        assertFalse(hooks[Tracepoints.SYS_ENTER_CONNECT]!!(blockedSocketPolicy))
        assertTrue(hooks[Tracepoints.SYS_ENTER_EXECVE]!!(blockedSocketPolicy)) // Not blocked
    }
}
