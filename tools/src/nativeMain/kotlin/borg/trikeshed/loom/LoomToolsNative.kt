package borg.trikeshed.loom

import borg.trikeshed.litebike.LitebikeListenerElement
import borg.trikeshed.litebike.PosixLitebikeBindAdapter
import borg.trikeshed.platform.HostSystem
import borg.trikeshed.userspace.nio.channels.inet_ntop
import borg.trikeshed.userspace.nio.posix.trikeshed_signal_pipe
import borg.trikeshed.userspace.nio.posix.trikeshed_spawn_group
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import platform.posix.*
import kotlin.system.exitProcess

fun loomMeshMain() = status("loom-mesh")

fun loomRegistryMain() = status("loom-registry")

fun loomKoboldStageMain() = status("loom-kobold-stage")

fun loomLeaseMain() = status("loom-lease")

/**
 * Runs the binary [name] on the process's arguments as their bytes: an [IllegalStateException] goes to stderr behind
 * `<name>: ` and exits with its status.
 */
fun status(name: String) {
    val binary = binaries(NativeSupervisor).getValue(name)
    val status = try {
        binary.b(HostSystem.args_os()!!.drop(1))
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
    override suspend fun bind(listener: LitebikeListenerElement, listen: SocketAddr): suspend () -> Unit {
        // The adapter binds before its first suspension, so a refused bind has completed the job on return.
        val serving = CoroutineScope(Dispatchers.IO + SupervisorJob()).async(start = CoroutineStart.UNDISPATCHED) {
            PosixLitebikeBindAdapter.bindAndServe(listener, listen.port, inet_ntop(listen.ip))
        }
        if (serving.isCompleted) error("cannot bind mesh listener")
        return {
            try {
                serving.await()
            } finally {
                serving.cancelAndJoin()
            }
        }
    }

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
        if (trikeshed_spawn_group(pid.ptr, argv) != 0) error("cannot start supervised program")
        pid.value
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
