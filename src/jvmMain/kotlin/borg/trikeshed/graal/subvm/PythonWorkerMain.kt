package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.Teleported
import borg.trikeshed.vm.field
import borg.trikeshed.vm.int
import borg.trikeshed.vm.str
import borg.trikeshed.userspace.nio.process.ProcessCalls
import borg.trikeshed.userspace.nio.process.ProcessFrameChannel
import borg.trikeshed.userspace.nio.channels.spi.processHostCall
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessProtocolSocket
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessProtocolStream
import kotlinx.coroutines.*
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.EnvironmentAccess
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Stock Python worker. Its only language context is built once and retired with the process. */
object PythonWorkerMain {
    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        val id = args[0]
        val configuration = PythonWorker.decode(args[1])
        val transport = JvmProcessProtocolSocket.connect(args[2].toInt(), args[3])
        val terminalInput = System.`in`
        val terminalOutput = System.out
        val terminalError = System.err
        val replies = ConcurrentHashMap<Int, ArrayBlockingQueue<Teleported.Obj>>()
        val delegates = ConcurrentHashMap.newKeySet<String>()
        val sequence = AtomicInteger()
        val connected = AtomicBoolean(true)
        val handler = CompletableDeferred<suspend (String) -> Unit>()
        val frames = ProcessFrameChannel(coroutineContext + Dispatchers.Default,
            JvmProcessProtocolStream.socket(transport),
            onLine = { handler.await()(it) },
            onEnd = {
                connected.set(false)
                for (reply in replies.values) reply.offer(Teleported.obj("error" to "Python worker parent closed"))
                processHostCall { terminalInput.close() }
            })
        suspend fun reply(message: Teleported.Obj) = frames.send(SubVmProtocol.encode(message))
        fun send(message: Teleported.Obj) = runBlocking { reply(message) }
        java.security.Security.addProvider(PythonBlake2Provider)
        val builder = Context.newBuilder("python")
            .allowHostAccess(HostAccess.NONE)
            .allowHostClassLookup { false }
            .allowPolyglotAccess(PolyglotAccess.NONE)
            .allowIO(IOAccess.ALL)
            .allowNativeAccess(true)
            .allowCreateThread(true)
            .allowCreateProcess(true)
            .allowEnvironmentAccess(EnvironmentAccess.INHERIT)
            .option("engine.WarnInterpreterOnly", "false")
            .option("python.ForceImportSite", "true")
            .option("python.PosixModuleBackend", "native")
            .`in`(terminalInput).out(terminalOutput).err(terminalError)
            .arguments("python", configuration.arguments.toTypedArray())
        configuration.workingDirectory?.let { builder.currentWorkingDirectory(Path.of(it)) }
        if (configuration.pythonPath.isNotEmpty())
            builder.option("python.PythonPath", configuration.pythonPath.joinToString(File.pathSeparator))
        configuration.executable?.let { builder.option("python.Executable", it) }
        configuration.pythonHome?.let { builder.option("python.PythonHome", it) }

        // No InProcessIsolate, native-import guard, standard-library replacement, listener,
        // or guest statement limit participates in this context.
        val context = builder.build()
        var retired = false
        try {
            context.getBindings("python").putMember("host", ProxyObject.fromMap(mapOf(
                "call" to ProxyExecutable { arguments ->
                    val name = arguments.firstOrNull()?.takeIf { it.isString }?.asString()
                        ?: error("host.call requires a delegate name")
                    check(name in delegates) { "no host delegate named '$name'" }
                    val requestId = sequence.incrementAndGet()
                    val reply = ArrayBlockingQueue<Teleported.Obj>(1)
                    replies[requestId] = reply
                    try {
                        check(connected.get()) { "Python worker parent closed" }
                        send(Teleported.obj("op" to "host", "id" to requestId, "name" to name,
                            "args" to Teleported.Arr(arguments.drop(1).map { Teleported.of(it) })))
                        // The parent owns the callback deadline and joins cancellation cleanup before replying.
                        // A second lease timer here would let Python proceed while that host effect still ran.
                        val response = reply.poll(configuration.timeoutMillis, TimeUnit.MILLISECONDS)
                            ?: error("host delegate '$name' transport exceeded ${configuration.timeoutMillis}ms")
                        response.str("error")?.let { error(it) }
                        (response.field("value") ?: Teleported.Null).toGuest()
                    } finally { replies.remove(requestId) }
                },
            )))

            // SQLite and guest thread locals retain the process main thread across operations.
            val operations = ProcessCalls(coroutineContext, concurrency = 1) { response ->
                reply(response.b.fold(
                    { Teleported.obj("id" to response.a, "ok" to true, "value" to it) },
                    { failure ->
                        val kind = when {
                            failure is PolyglotException && failure.isCancelled -> GuestFailure.INTERRUPTED
                            failure is PolyglotException && failure.isExit -> GuestFailure.DEAD
                            else -> GuestFailure.GUEST_ERROR
                        }
                        Teleported.obj("id" to response.a, "ok" to false,
                            "kind" to kind.name, "error" to (failure.message ?: failure.toString()))
                    },
                ))
            }
            var accepting = true
            suspend fun execute(message: Teleported.Obj, operation: () -> Teleported) {
                if (operations.trySubmit(message.int("id") ?: -1, message.str("op") ?: "", null) { operation() } == null) {
                    reply(Teleported.obj("id" to message.int("id"), "ok" to false,
                        "kind" to GuestFailure.GUEST_ERROR.name,
                        "error" to "guest operation was not admitted: capacity exhausted or draining"))
                }
            }
            try {
                handler.complete(dispatch@{ line ->
                    if (line.isBlank()) return@dispatch
                    val message = SubVmProtocol.decode(line)
                    val operation = message.str("op")
                    if (operation == null) {
                        message.int("id")?.let { replies[it]?.offer(message) }
                        return@dispatch
                    }
                    if (operation == "interrupt") {
                        accepting = false
                        processHostCall { context.interrupt(java.time.Duration.ofSeconds(2)) }
                        reply(Teleported.obj("id" to message.int("id"), "ok" to true, "value" to true))
                        return@dispatch
                    }
                    if (!accepting) {
                        reply(Teleported.obj("id" to message.int("id"), "ok" to false,
                            "kind" to GuestFailure.DEAD.name, "error" to "Python worker is draining"))
                        return@dispatch
                    }
                    when (operation) {
                        "eval" -> execute(message) {
                            Teleported.of(context.eval(Source.newBuilder("python", message.str("source") ?: "",
                                message.str("name") ?: "<eval>").build()))
                        }
                        "call" -> execute(message) {
                            val root = message.str("root") ?: ""
                            val function = context.getBindings("python").getMember(root)
                            check(function != null && function.canExecute()) { "no guest root '$root'" }
                            val arguments = (message.field("args") as? Teleported.Arr)?.v.orEmpty()
                            Teleported.of(function.execute(*arguments.map { it.toGuest() }.toTypedArray()))
                        }
                        "delegate" -> execute(message) {
                            val timeout = (message.field("timeout") as? Teleported.Num)?.v ?: configuration.timeoutMillis
                            require(timeout > 0)
                            delegates.add(message.str("name") ?: error("missing delegate name"))
                            Teleported.Null
                        }
                        "close" -> {
                            accepting = false
                            processHostCall { terminalInput.close() }
                            checkNotNull(operations.finish(message.int("id") ?: -1, "close") {
                                context.close(); retired = true; Teleported.Null
                            })
                        }
                        else -> reply(Teleported.obj("id" to message.int("id"), "ok" to false,
                            "kind" to GuestFailure.GUEST_ERROR.name, "error" to "unknown operation '$operation'"))
                    }
                })
                // Await peer EOF; do not close protocol output before the queued close reply.
                frames.awaitClosed()
            } finally { operations.drain() }
        } finally {
            try { if (!retired) context.close() }
            finally { terminalOutput.flush(); terminalError.flush() }
        }
    }
}
