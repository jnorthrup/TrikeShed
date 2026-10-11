package borg.trikeshed.loom

import borg.trikeshed.litebike.*
import borg.trikeshed.platform.PosixSignals
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import java.net.InetAddress
import kotlin.system.exitProcess

/** JVM entry for the loom tools: the first argument names the Rust binary, the rest are its arguments. */
fun main(args: Array<String>) {
    // Rust writes UTF-8 whatever the locale; System.out would encode with stdout.encoding.
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
    val binaries = binaries(JvmSupervisor)
    val name = args.firstOrNull()
    val binary = name?.let { binaries[it] } ?: run {
        System.err.println("usage: <${binaries.keys.joinToString("|")}> ARGS...")
        exitProcess(2)
    }
    val status = try {
        binary.b(args.copyOfRange(1, args.size))
    } catch (failure: IllegalStateException) {
        System.out.flush()
        System.err.write("$name: ${failure.message}\n".encodeToByteArray())
        System.err.flush()
        exitProcess(binary.a)
    }
    System.out.flush()
    exitProcess(status)
}

/**
 * main.rs's process seams on the JVM: the listener on [JvmLitebikeBindAdapter], signals through the JDK's own handler
 * ([PosixSignals]). The JDK's ProcessBuilder cannot place a child in a process group of its own, so the supervised
 * program is posix_spawnp'd through FFM, as JvmFileSyscalls reaches libc, the way std::process::Command spawns it:
 * stdio and environment inherited, POSIX_SPAWN_SETPGROUP, an empty signal mask and SIGPIPE at its default. The child
 * keeps stdio alone, as from a Rust parent whose descriptors are all O_CLOEXEC; the JDK's descriptors, the listener
 * among them, are not, so Darwin's POSIX_SPAWN_CLOEXEC_DEFAULT closes the rest.
 */
object JvmSupervisor : Supervisor {
    val linker: Linker = Linker.nativeLinker()
    val int: ValueLayout.OfInt = ValueLayout.JAVA_INT
    val pointer: AddressLayout = ValueLayout.ADDRESS
    val state: StructLayout = Linker.Option.captureStateLayout()
    val errnoOffset = state.byteOffset(MemoryLayout.PathElement.groupElement("errno"))

    fun libc(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle = linker.downcallHandle(
        linker.defaultLookup().find(name).orElseThrow { UnsupportedOperationException("libc $name is unavailable") }, descriptor, *options,
    )

    val spawnpCall by lazy { libc("posix_spawnp", FunctionDescriptor.of(int, pointer, pointer, pointer, pointer, pointer, pointer)) }
    val attrInitCall by lazy { libc("posix_spawnattr_init", FunctionDescriptor.of(int, pointer)) }
    val attrDestroyCall by lazy { libc("posix_spawnattr_destroy", FunctionDescriptor.of(int, pointer)) }
    val setFlagsCall by lazy { libc("posix_spawnattr_setflags", FunctionDescriptor.of(int, pointer, ValueLayout.JAVA_SHORT)) }
    val setGroupCall by lazy { libc("posix_spawnattr_setpgroup", FunctionDescriptor.of(int, pointer, int)) }
    val setMaskCall by lazy { libc("posix_spawnattr_setsigmask", FunctionDescriptor.of(int, pointer, pointer)) }
    val setDefaultsCall by lazy { libc("posix_spawnattr_setsigdefault", FunctionDescriptor.of(int, pointer, pointer)) }
    val actionsInitCall by lazy { libc("posix_spawn_file_actions_init", FunctionDescriptor.of(int, pointer)) }
    val actionsDestroyCall by lazy { libc("posix_spawn_file_actions_destroy", FunctionDescriptor.of(int, pointer)) }

    /** Darwin's posix_spawn_file_actions_addinherit_np: under POSIX_SPAWN_CLOEXEC_DEFAULT, a descriptor the child keeps. */
    val inheritCall: MethodHandle? by lazy {
        linker.defaultLookup().find("posix_spawn_file_actions_addinherit_np").map { linker.downcallHandle(it, FunctionDescriptor.of(int, pointer, int)) }.orElse(null)
    }
    val emptySetCall by lazy { libc("sigemptyset", FunctionDescriptor.of(int, pointer)) }
    val addSetCall by lazy { libc("sigaddset", FunctionDescriptor.of(int, pointer, int)) }
    val waitpidCall by lazy { libc("waitpid", FunctionDescriptor.of(int, int, pointer, int), Linker.Option.captureCallState("errno")) }
    val killCall by lazy { libc("kill", FunctionDescriptor.of(int, int, int), Linker.Option.captureCallState("errno")) }
    val signalCall by lazy { libc("signal", FunctionDescriptor.of(pointer, int, pointer)) }

    /** POSIX_SPAWN_SETPGROUP | POSIX_SPAWN_SETSIGDEF | POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_CLOEXEC_DEFAULT (0x2 | 0x4 | 0x8 | 0x4000, Darwin). */
    const val SPAWN_FLAGS: Short = 16398
    const val SIGPIPE: Int = 13

    override suspend fun bind(listener: LitebikeListenerElement, listen: SocketAddr): suspend () -> Unit {
        val host = InetAddress.getByAddress(listen.ip).hostAddress
        // The adapter binds before its first suspension, so a refused bind has completed the job on return.
        val serving = CoroutineScope(Dispatchers.IO + SupervisorJob()).async(start = CoroutineStart.UNDISPATCHED) {
            JvmLitebikeBindAdapter.bindAndServe(listener, listen.port, host)
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
        // tokio's per-kind stream coalesces deliveries; only the first reaches the supervisor's select.
        val delivered = Channel<Int>(3)
        try {
            for ((name, number) in listOf("TERM" to SIGTERM, "INT" to SIGINT, "HUP" to SIGHUP)) {
                // tokio's handler replaces a disposition inherited as SIG_IGN; the JDK keeps such a shutdown signal
                // ignored, so the disposition returns to SIG_DFL before the JDK installs its handler. SIG_ERR is -1.
                if ((signalCall.invokeExact(number, MemorySegment.NULL) as MemorySegment).address() == -1L) error("signal handler")
                PosixSignals.handle(name) { delivered.trySend(number) }
            }
        } catch (refused: IllegalArgumentException) {
            error("signal handler")
        }
        return delivered
    }

    override fun spawn(program: String, args: List<String>): Int = Arena.ofConfined().use { arena ->
        val inherit = inheritCall
            ?: TODO("a supervised program on this OS: a spawn file action closing descriptors above stderr (glibc 2.34 posix_spawn_file_actions_addclosefrom_np)")
        fun strings(values: List<String>): MemorySegment = arena.allocate(pointer, values.size + 1L).also { array ->
            values.forEachIndexed { i, value -> array.setAtIndex(pointer, i.toLong(), arena.allocateFrom(value)) }
            array.setAtIndex(pointer, values.size.toLong(), MemorySegment.NULL)
        }
        // posix_spawnattr_t, posix_spawn_file_actions_t and sigset_t are a pointer, a pointer and 4 bytes on Darwin.
        val attributes = arena.allocate(512, 16)
        val actions = arena.allocate(512, 16)
        val mask = arena.allocate(128, 8)
        val defaults = arena.allocate(128, 8)
        val pid = arena.allocate(int)
        if (attrInitCall.invokeExact(attributes) as Int != 0) error("cannot start supervised program")
        try {
            if (actionsInitCall.invokeExact(actions) as Int != 0) error("cannot start supervised program")
            try {
                emptySetCall.invokeExact(mask) as Int
                emptySetCall.invokeExact(defaults) as Int
                addSetCall.invokeExact(defaults, SIGPIPE) as Int
                var status = 0
                for (fd in 0..2) if (status == 0) status = inherit.invokeExact(actions, fd) as Int
                if (status == 0) status = setFlagsCall.invokeExact(attributes, SPAWN_FLAGS) as Int
                if (status == 0) status = setGroupCall.invokeExact(attributes, 0) as Int
                if (status == 0) status = setMaskCall.invokeExact(attributes, mask) as Int
                if (status == 0) status = setDefaultsCall.invokeExact(attributes, defaults) as Int
                if (status == 0) status = spawnpCall.invokeExact(
                    pid, arena.allocateFrom(program), actions, attributes, strings(listOf(program) + args),
                    strings(System.getenv().map { (k, v) -> "$k=$v" }),
                ) as Int
                if (status != 0) error("cannot start supervised program")
                pid.get(int, 0)
            } finally {
                actionsDestroyCall.invokeExact(actions) as Int
            }
        } finally {
            attrDestroyCall.invokeExact(attributes) as Int
        }
    }

    override suspend fun wait(pid: Int): Int? = withContext(Dispatchers.IO) {
        Arena.ofConfined().use { arena ->
            val captured = arena.allocate(state)
            val status = arena.allocate(int)
            while (waitpidCall.invokeExact(captured, pid, status, 0) as Int != pid) {
                if (captured.get(int, errnoOffset) != 4) error("supervised program wait") // EINTR
            }
            val value = status.get(int, 0)
            if (value and 0x7f == 0) (value shr 8) and 0xff else null
        }
    }

    override fun kill(pid: Int, signal: Int): Int = Arena.ofConfined().use { arena ->
        val captured = arena.allocate(state)
        if (killCall.invokeExact(captured, pid, signal) as Int == 0) 0 else -captured.get(int, errnoOffset)
    }
}
