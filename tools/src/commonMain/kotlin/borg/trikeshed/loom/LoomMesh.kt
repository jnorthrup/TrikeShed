package borg.trikeshed.loom

import borg.trikeshed.litebike.LitebikeListenerElement
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select

/**
 * cocaine-rats `crates/loom-mesh/src/main.rs`. Failures exit 1 behind `loom-mesh: `. Ported: `--config`, `--serverless`,
 * `--confix-check`, `--settle-release` and `--uring-self-test`; each other mode is a TODO naming what it waits on.
 * [supervisor] is the platform's seam for the serving modes; the result is the process's exit status.
 */
fun loomMesh(args: Array<String>, supervisor: Supervisor): Int {
    when (args.firstOrNull()) {
        "--runpod-identity-diagnostic-stdin", "--runpod-identity-diagnostic" ->
            TODO("loom-mesh ${args[0]} waits on the Runpod API client (customer::provider_cost::runpod_api) over an HTTPS client")
        "--customer-legacy-preview", "--customer-legacy-import" ->
            TODO("loom-mesh ${args[0]} waits on customer::migration: the legacy customer book and the GCS client")
        "--confix-check" -> confixCheck(args.copyOfRange(1, args.size))
        "--uring-self-test" -> {
            val name = args.getOrNull(1) ?: "auto"
            if (args.size > 2) error("unexpected self-test arguments")
            println(jsonText(runBlocking(Dispatchers.Default) { self_test(policy(name)) }))
            return 0
        }
        "--inference-worker" -> TODO("loom-mesh --inference-worker waits on inference::worker: the HTTP listener and HMAC-SHA256")
        "--inn" -> TODO("loom-mesh --inn waits on inn::serve: the HTTP listener, the sealed customer book (HKDF, ChaCha20-Poly1305) and the HTTPS client")
        "--settle", "--settle-public", "--settle-host" ->
            TODO("loom-mesh ${args[0]} waits on inn::serve_cloud_run: the HTTP listener and GCS settlement storage")
        "--inn-proxy" -> TODO("loom-mesh --inn-proxy waits on inn::proxy::serve: the HTTP listener and the HTTPS client")
        "--settle-held", "--settle-recovery-page", "--settle-init" ->
            TODO("loom-mesh ${args[0]} waits on CustomerStore over GCS settlement storage")
        "--settle-reconcile" -> TODO("loom-mesh --settle-reconcile waits on inn::reconcile_provider_file over GCS settlement storage")
        "--settle-pod-reconcile" -> TODO("loom-mesh --settle-pod-reconcile waits on the Runpod Pod billing client and GCS settlement storage")
        // Quiescence alone does not prove a zero-cost outcome; no hold is released without reconciliation.
        "--settle-release" -> error("provider-cost reconciliation required; automatic hold release is disabled")
        "--serverless" -> return serverless(args, supervisor)
        "--config" -> return config(args, supervisor)
        else -> error(
            "usage: loom-mesh --config PATH [-- PROGRAM ARGS...] | --serverless | --inference-worker PATH | --inn PATH | --inn-proxy PATH | --settle PATH | --settle-public PATH POLICY | --settle-host PATH POLICY | --settle-init PATH | --settle-held PATH | --settle-recovery-page PATH [AFTER] | --settle-reconcile PATH BUNDLE | --settle-pod-reconcile PATH POD_CONFIG RESERVATION | --runpod-identity-diagnostic RECONCILE_CONFIG | --settle-release PATH RESERVATION | --customer-legacy-preview MIGRATION | --customer-legacy-import MIGRATION GCS | --uring-self-test [auto|native|emulated]",
        )
    }
    return 0
}

/** `--confix-check PATH...`: the preflight report of each configuration, `<path> <report>`. */
fun confixCheck(paths: Array<String>) {
    if (paths.isEmpty()) error("configuration paths required")
    for (path in paths) {
        val bytes = readFile(path, 128 * 1024 + 1, "configuration unavailable", "configuration unreadable")
        // zeroize::Zeroizing: the configuration bytes are cleared when they leave scope.
        try {
            println("$path ${assertConfix(bytes)}")
        } finally {
            bytes.fill(0)
        }
    }
}

const val SIGHUP: Int = 1
const val SIGINT: Int = 2
const val SIGKILL: Int = 9
const val SIGTERM: Int = 15
const val ESRCH: Int = 3

/** main.rs's tokio and libc seams, supplied by the platform entry. */
interface Supervisor {
    /** `TcpListener::bind(listen)` for [listener]; the returned function serves it until cancelled. Refused: "cannot bind mesh listener". */
    suspend fun bind(listener: LitebikeListenerElement, listen: SocketAddr): suspend () -> Unit

    /** `signal(SignalKind)` for SIGTERM, SIGINT and SIGHUP: the number of each delivery. Refused: "signal handler". */
    fun signals(): ReceiveChannel<Int>

    /**
     * `Command::new(program).args(args).process_group(0).spawn()`, stdio and environment inherited: the child's pid,
     * which is also its process group. Refused: "cannot start supervised program".
     */
    fun spawn(program: String, args: List<String>): Int

    /** `Child::wait`: the exit code, null when a signal ended the child. */
    suspend fun wait(pid: Int): Int?

    /** libc `kill(2)`: 0, or the negated errno. */
    fun kill(pid: Int, signal: Int): Int
}

/** main.rs `End`: what stopped the supervisor first. */
sealed class End {
    object Mesh : End()
    class Child(val status: Result<Int?>) : End()
    class Signal(val signum: Int) : End()
}

/**
 * main.rs `--config PATH [-- PROGRAM ARGS...]`: the mesh node until SIGTERM, SIGINT or SIGHUP (exit 0), and with a
 * program, that program in its own process group; when it exits the node stops and its exit code is the status (1
 * when a signal ended it). The node and the program are stopped together, the program by [stop_child].
 */
fun config(args: Array<String>, supervisor: Supervisor): Int = runBlocking(Dispatchers.Default) {
    val path = args.getOrNull(1) ?: error("configuration path required")
    val rest = args.drop(2)
    if (rest.isNotEmpty() && (rest[0] != "--" || rest.size < 2)) error("invalid supervisor arguments")
    val (config, key) = Config.load(path)
    serve(Node.open(config, key), config.listen, rest, supervisor)
}

/** main.rs `--serverless`: the node of the RunPod bootstrap in the environment, until a shutdown signal; no program. */
fun serverless(args: Array<String>, supervisor: Supervisor): Int = runBlocking(Dispatchers.Default) {
    if (args.size > 1) error("serverless does not supervise a child")
    val bootstrap = Bootstrap.from_environment()
    serve(Node.open_serverless(bootstrap), bootstrap.config.listen, emptyList(), supervisor)
}

/** main.rs after the mode: [node] on [listen] beside the program [rest] names, until either ends or a shutdown signal. */
suspend fun serve(node: Node, listen: SocketAddr, rest: List<String>, supervisor: Supervisor): Int = coroutineScope {
    node.open()
    val serve = supervisor.bind(node.listener, listen)
    val signals = supervisor.signals()
    val child = if (rest.isEmpty()) null else supervisor.spawn(rest[1], rest.drop(2))
    val status = child?.let { async { runCatching { supervisor.wait(it) } } }
    try {
        val stop = CompletableDeferred<Unit>()
        val mesh = async { runCatching { node.run(serve) { stop.await() } } }
        val end = select<End> {
            mesh.onJoin { End.Mesh }
            if (status != null) status.onAwait { End.Child(it) }
            signals.onReceive { End.Signal(it) }
        }
        stop.complete(Unit)
        if (child != null && status != null) stop_child(supervisor, child, status, (end as? End.Signal)?.signum ?: SIGTERM)
        when (end) {
            End.Mesh -> {
                mesh.await().getOrThrow()
                error("mesh stopped unexpectedly")
            }
            is End.Child -> {
                mesh.await().getOrThrow()
                end.status.getOrElse { error("supervised program wait") } ?: 1
            }
            is End.Signal -> {
                mesh.await().getOrThrow()
                0
            }
        }
    } finally {
        // kill_on_drop: a child left unreaped is killed.
        if (child != null && status?.isCompleted != true) supervisor.kill(child, SIGKILL)
    }
}

/**
 * main.rs stop_child: [signal] to the child's group, then up to 5 s for the child and every process of its group to
 * stop; otherwise SIGKILL the group and reap the child. Reaping the leader does not establish that its descendants
 * stopped.
 */
suspend fun stop_child(supervisor: Supervisor, group: Int, status: Deferred<Result<Int?>>, signal: Int) {
    send_group(supervisor, group, signal)
    var failure: IllegalStateException? = null
    val graceful = try {
        withTimeoutOrNull(5_000) {
            status.await().getOrElse { error("supervised program shutdown") }
            while (send_group(supervisor, group, 0)) delay(25)
        } != null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: IllegalStateException) {
        failure = failed
        false
    }
    if (!graceful) {
        // Escalate even if the direct child was already reaped or wait failed.
        send_group(supervisor, group, SIGKILL)
        status.await().getOrElse { error("supervised program shutdown") }
    }
    failure?.let { throw it }
}

/** main.rs send_group: [signal] to the process group [id] alone, which the child owns; false once no process of it remains. */
fun send_group(supervisor: Supervisor, id: Int, signal: Int): Boolean {
    if (id <= 1) error("invalid supervised PID")
    return when (supervisor.kill(-id, signal)) {
        0 -> true
        -ESRCH -> false
        else -> error("supervised program signal")
    }
}
