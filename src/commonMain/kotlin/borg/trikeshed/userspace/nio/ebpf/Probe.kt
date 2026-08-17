package borg.trikeshed.userspace.nio.ebpf

import borg.trikeshed.userspace.containment.ContainmentPolicy

expect fun bpfProbeAttach(progFd: Int, tracepoint: String): Int

object Tracepoints {
    const val SYS_ENTER_SOCKET = "sys_enter_socket"
    const val SYS_ENTER_CONNECT = "sys_enter_connect"
    const val SYS_ENTER_EXECVE = "sys_enter_execve"
    const val SYS_ENTER_OPENAT = "sys_enter_openat"
}

object ContainmentHooks {
    val hooks = mapOf<String, (ContainmentPolicy) -> Boolean>(
        Tracepoints.SYS_ENTER_SOCKET to { policy -> policy.layer3Syscall.blockedSyscalls.contains("socket") == false },
        Tracepoints.SYS_ENTER_CONNECT to { policy -> policy.layer3Syscall.blockedSyscalls.contains("connect") == false },
        Tracepoints.SYS_ENTER_EXECVE to { policy -> policy.layer3Syscall.blockedSyscalls.contains("execve") == false },
        Tracepoints.SYS_ENTER_OPENAT to { policy -> policy.layer3Syscall.blockedSyscalls.contains("openat") == false }
    )
}
