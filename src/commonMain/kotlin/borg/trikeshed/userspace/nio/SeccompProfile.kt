package borg.trikeshed.userspace.nio

/**
 * Legion Doc 04 Layer 3 - Seccomp-BPF strict profile.
 * Whitelists only essential POSIX syscalls and explicitly blocks
 * dangerous capabilities like ptrace, bpf, userfaultfd, and raw namespace cloning.
 */
object SeccompProfile {

    /**
     * Essential POSIX syscalls allowed for normal operations.
     */
    val whitelistedSyscalls = setOf(
        "read", "write", "open", "close", "stat", "fstat", "lstat",
        "poll", "lseek", "mmap", "mprotect", "munmap", "brk",
        "rt_sigaction", "rt_sigprocmask", "rt_sigreturn",
        "ioctl", "pread64", "pwrite64", "readv", "writev",
        "access", "pipe", "select", "sched_yield", "mremap",
        "msync", "mincore", "madvise", "shmget", "shmat",
        "shmctl", "dup", "dup2", "pause", "nanosleep",
        "getitimer", "alarm", "setitimer", "getpid",
        "sendfile", "socket", "connect", "accept",
        "sendto", "recvfrom", "sendmsg", "recvmsg",
        "shutdown", "bind", "listen", "getsockname",
        "getpeername", "socketpair", "setsockopt",
        "getsockopt", "clone", "fork", "vfork", "execve",
        "exit", "wait4", "kill", "uname", "semget",
        "semop", "semctl", "shmdt", "msgget", "msgsnd",
        "msgrcv", "msgctl", "fcntl", "flock", "fsync",
        "fdatasync", "truncate", "ftruncate", "getdents",
        "getcwd", "chdir", "fchdir", "rename", "mkdir",
        "rmdir", "creat", "link", "unlink", "symlink",
        "readlink", "chmod", "fchmod", "chown", "fchown",
        "lchown", "umask", "gettimeofday", "getrlimit",
        "getrusage", "sysinfo", "times",
        "getuid", "syslog", "getgid", "setuid", "setgid",
        "geteuid", "getegid", "setpgid", "getppid",
        "getpgrp", "setsid", "setreuid", "setregid",
        "getgroups", "setgroups", "setresuid", "getresuid",
        "setresgid", "getresgid", "getpgid", "setfsuid",
        "setfsgid", "getsid", "capget", "capset",
        "rt_sigpending", "rt_sigtimedwait", "rt_sigqueueinfo",
        "rt_sigsuspend", "sigaltstack", "utime", "mknod",
        "uselib", "personality", "ustat", "statfs",
        "fstatfs", "sysfs", "getpriority", "setpriority",
        "sched_setparam", "sched_getparam", "sched_setscheduler",
        "sched_getscheduler", "sched_get_priority_max",
        "sched_get_priority_min", "sched_rr_get_interval",
        "mlock", "munlock", "mlockall", "munlockall",
        "vhangup", "modify_ldt",
        "adjtimex", "setrlimit", "chroot", "sync", "acct", "settimeofday",
        "umount2", "swapon", "swapoff",
        "reboot", "sethostname", "setdomainname",
        "iopl", "ioperm", "create_module", "init_module",
        "delete_module", "get_kernel_syms", "query_module",
        "quotactl", "nfsservctl", "getpmsg", "putpmsg",
        "afs_syscall", "tuxcall", "security", "gettid",
        "readahead", "setxattr", "lsetxattr", "fsetxattr",
        "getxattr", "lgetxattr", "fgetxattr", "listxattr",
        "llistxattr", "flistxattr", "removexattr",
        "lremovexattr", "fremovexattr", "tkill", "time",
        "futex", "sched_setaffinity", "sched_getaffinity",
        "set_thread_area", "io_setup", "io_destroy",
        "io_getevents", "io_submit", "io_cancel",
        "get_thread_area", "lookup_dcookie", "epoll_create",
        "epoll_ctl_old", "epoll_wait_old", "remap_file_pages",
        "getdents64", "set_tid_address", "restart_syscall",
        "semtimedop", "fadvise64", "timer_create",
        "timer_settime", "timer_gettime", "timer_getoverrun",
        "timer_delete", "clock_settime", "clock_gettime",
        "clock_getres", "clock_nanosleep", "exit_group",
        "epoll_wait", "epoll_ctl", "tgkill", "utimes",
        "vserver", "mbind", "set_mempolicy", "get_mempolicy",
        "mq_open", "mq_unlink", "mq_timedsend", "mq_timedreceive",
        "mq_notify", "mq_getsetattr", "kexec_load", "waitid",
        "add_key", "request_key", "keyctl", "ioprio_set",
        "ioprio_get", "inotify_init", "inotify_add_watch",
        "inotify_rm_watch", "migrate_pages", "openat",
        "mkdirat", "mknodat", "fchownat", "futimesat",
        "newfstatat", "unlinkat", "renameat", "linkat",
        "symlinkat", "readlinkat", "fchmodat", "faccessat",
        "pselect6", "ppoll", "unshare", "set_robust_list",
        "get_robust_list", "splice", "tee", "sync_file_range",
        "vmsplice", "move_pages", "utimensat", "epoll_pwait",
        "signalfd", "timerfd_create", "eventfd", "fallocate",
        "timerfd_settime", "timerfd_gettime", "accept4",
        "signalfd4", "eventfd2", "epoll_create1", "dup3",
        "pipe2", "inotify_init1", "preadv", "pwritev",
        "rt_tgsigqueueinfo", "perf_event_open", "recvmmsg",
        "fanotify_init", "fanotify_mark", "prlimit64",
        "name_to_handle_at", "open_by_handle_at", "clock_adjtime",
        "syncfs", "sendmmsg", "setns", "getcpu", "process_vm_readv",
        "process_vm_writev", "kcmp", "finit_module", "sched_setattr",
        "sched_getattr", "renameat2", "seccomp", "getrandom",
        "memfd_create", "kexec_file_load",
        "execveat",
        "membarrier", "mlock2", "copy_file_range",
        "preadv2", "pwritev2", "pkey_mprotect", "pkey_alloc",
        "pkey_free", "statx", "io_pgetevents", "rseq"
    )

    /**
     * Explicitly blocked syscalls.
     */
    val blockedSyscalls = setOf(
        "ptrace",
        "bpf",
        "userfaultfd",
        "mount",
        "pivot_root"
    )

    // Namespace flags (for clone and clone3)
    const val CLONE_NEWNS = 0x00020000
    const val CLONE_NEWCGROUP = 0x02000000
    const val CLONE_NEWUTS = 0x04000000
    const val CLONE_NEWIPC = 0x08000000
    const val CLONE_NEWUSER = 0x10000000
    const val CLONE_NEWPID = 0x20000000
    const val CLONE_NEWNET = 0x40000000

    const val BLOCKED_CLONE_FLAGS = CLONE_NEWNS or CLONE_NEWCGROUP or
            CLONE_NEWUTS or CLONE_NEWIPC or CLONE_NEWUSER or
            CLONE_NEWPID or CLONE_NEWNET

    /**
     * Validates if clone flags are permitted under this strict profile.
     */
    fun isCloneAllowed(flags: Int): Boolean {
        return (flags and BLOCKED_CLONE_FLAGS) == 0
    }
}
