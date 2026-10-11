package borg.trikeshed.loom

import borg.trikeshed.litebike.LitebikeListenerElement
import borg.trikeshed.userspace.nio.posix.trikeshed_signal_pipe
import borg.trikeshed.userspace.nio.posix.trikeshed_spawn_group
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import platform.posix.*
import kotlin.system.exitProcess

fun loomMeshMain(args: Array<String>) = status("loom-mesh", args)

fun loomRegistryMain(args: Array<String>) = status("loom-registry", args)

fun loomKoboldStageMain(args: Array<String>) = status("loom-kobold-stage", args)

fun loomLeaseMain(args: Array<String>) = status("loom-lease", args)

/** Runs the binary [name]: an [IllegalStateException] goes to stderr behind `<name>: ` and exits with its status. */
fun status(name: String, args: Array<String>) {
    val binary = binaries(NativeSupervisor).getValue(name)
    val status = try {
        binary.b(args)
    } catch (failure: IllegalStateException) {
        fflush(stdout)
        fputs("$name: ${failure.message}\n", stderr)
        fflush(stderr)
        exitProcess(binary.a)
    }
    exitProcess(status)
}

/**
 * main.rs's process seams on this target: the supervised program posix_spawn'd into its own group through the
 * posix_spawn cinterop, waitpid and kill(2), and SIGTERM, SIGINT and SIGHUP through the cinterop's signal pipe.
 */
object NativeSupervisor : Supervisor {
    override suspend fun bind(listener: LitebikeListenerElement, listen: SocketAddr): suspend () -> Unit = TODO(
        "loom-mesh --config on this target: the litebike listener's bind adapter, which waits on SOCKET, BIND, LISTEN and " +
            "ACCEPT in the POSIX userspace channel backend (PosixUserspaceChannelBackend.capabilities), as the node's peer " +
            "client waits on SOCKET and CONNECT there",
    )

    override fun signals(): ReceiveChannel<Int> {
        val fd = trikeshed_signal_pipe()
        if (fd < 0) error("signal handler")
        val delivered = Channel<Int>(3)
        // The reader blocks in read(2) for the life of the process, outside the caller's scope; tokio's per-kind stream
        // coalesces deliveries, and only the first reaches the supervisor's select.
        CoroutineScope(Dispatchers.IO).launch {
            memScoped {
                val byte = alloc<UByteVar>()
                while (true) {
                    val read = read(fd, byte.ptr, 1.convert())
                    if (read == 1L) delivered.trySend(byte.value.toInt()) else if (read < 0 && errno == EINTR) continue else break
                }
            }
        }
        return delivered
    }

    override fun spawn(program: String, args: List<String>): Int = memScoped {
        val pid = alloc<pid_tVar>()
        val argv = allocArray<CPointerVar<ByteVar>>(args.size + 2)
        argv[0] = program.cstr.ptr
        args.forEachIndexed { i, arg -> argv[i + 1] = arg.cstr.ptr }
        argv[args.size + 1] = null
        when (trikeshed_spawn_group(pid.ptr, argv)) {
            0 -> pid.value
            ENOSYS -> TODO("a supervised program on this target: a spawn file action closing descriptors above stderr (glibc 2.34 posix_spawn_file_actions_addclosefrom_np)")
            else -> error("cannot start supervised program")
        }
    }

    override suspend fun wait(pid: Int): Int? = withContext(Dispatchers.IO) {
        memScoped {
            val status = alloc<IntVar>()
            while (waitpid(pid, status.ptr, 0) != pid) if (errno != EINTR) error("supervised program wait")
            if (status.value and 0x7f == 0) (status.value shr 8) and 0xff else null
        }
    }

    override fun kill(pid: Int, signal: Int): Int = if (platform.posix.kill(pid, signal) == 0) 0 else -errno
}
