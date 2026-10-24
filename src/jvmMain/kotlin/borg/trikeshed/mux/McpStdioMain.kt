package borg.trikeshed.mux

import borg.trikeshed.agent.keymux.EnvVarSource
import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.modelmux.ChatMessage
import borg.trikeshed.agent.modelmux.ChatReply
import borg.trikeshed.agent.modelmux.ContextReadPolicy
import borg.trikeshed.agent.modelmux.JevaOversight
import borg.trikeshed.agent.modelmux.ModelHttpFailure
import borg.trikeshed.agent.modelmux.ModelInfo
import borg.trikeshed.agent.modelmux.ModelMux
import borg.trikeshed.agent.modelmux.ModelMuxTools
import borg.trikeshed.agent.modelmux.ModelPort
import borg.trikeshed.agent.modelmux.SharedContext
import borg.trikeshed.agent.modelmux.SharedContextElement
import borg.trikeshed.agent.modelmux.SharedContextRoot
import borg.trikeshed.agent.modelmux.SharedContextSettings
import borg.trikeshed.agent.zenith.ZenithTools
import borg.trikeshed.htx.HtxElement
import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.*
import borg.trikeshed.mcp.McpLineServer
import borg.trikeshed.mcp.McpTool
import borg.trikeshed.mcp.McpToolServer
import borg.trikeshed.mcp.InputStreamLineSource
import borg.trikeshed.mcp.OutputStreamLineSink
import borg.trikeshed.mcp.ToolFailure
import borg.trikeshed.narsese.JevaConfig
import borg.trikeshed.narsese.JevaElement
import borg.trikeshed.narsese.JevaRuntime
import borg.trikeshed.userspace.nio.channels.spi.EgressAllowlist
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.System
import kotlin.system.exitProcess

/**
 * ModelMux behind [ModelPort], over explicit lanes only. A lane's key is read from the one environment variable the
 * operator named for it, by KeyMux, on this side of the wire: it is never an argument, a tool result or a log line.
 * Outbound destinations are permitted only because a lane named them.
 */
class MuxModelPort(
    private val lanes: List<ModelLane>,
    private val mux: ModelMux,
    private val transport: HtxElement,
    private val reactor: MuxReactorElement,
    private val timeoutMs: Long = 60_000,
) : ModelPort {
    override suspend fun models(): List<ModelInfo> = lanes.map { ModelInfo(it.model, it.provider, setOf("chat")) }

    override suspend fun chat(model: String, messages: List<ChatMessage>, maxTokens: Int?): ChatReply {
        if (lanes.none { it.model == model }) throw ToolFailure("model is not configured on this server")
        val series = messages.size j { i: Int -> messages[i].role j messages[i].content }
        val result = withContext(transport + reactor) {
            mux.chat(modelId = model, messages = series, maxTokens = maxTokens, timeoutMs = timeoutMs)
        }
        result.exceptionOrNull()?.let { failure ->
            if (failure is CancellationException) throw failure
            if (failure is ModelHttpFailure) throw ToolFailure("chat failed: HTTP ${failure.status}")
            throw failure
        }
        val response = result.getOrThrow()
        return ChatReply(response.a, response.b.a, response.b.b)
    }
}

/**
 * JVM boundary for the stdio MCP server. All policy, protocol and tool logic is commonMain; this file binds
 * stdin/stdout, builds the explicitly requested tools, and owns process lifecycle.
 *
 * stdout is the protocol channel and nothing else may write to it, so the process's `System.out` is pointed at stderr
 * before anything runs. Log lines carry method, tool name, outcome and duration, never arguments or results.
 */
object McpStdioMain {
    const val VERSION: String = "0.1.0"

    @JvmStatic
    fun main(args: Array<String>) {
        val options = try {
            McpStdioOptions.parse(args)
        } catch (bad: IllegalArgumentException) {
            System.err.println("mcp-stdio: ${bad.message}")
            System.err.println("try: mcp-stdio --help")
            exitProcess(64)
        }
        if (options.help) {
            println(McpStdioOptions.USAGE)
            exitProcess(0)
        }
        val protocolIn: InputStream = FileInputStream(FileDescriptor.`in`)
        val protocolOut: OutputStream = FileOutputStream(FileDescriptor.out)
        System.setOut(System.err)

        val status = try {
            runBlocking(Dispatchers.IO) {
                val serving = coroutineContext[Job]!!
                // SIGTERM/SIGINT: cancel in-flight work so child processes are reaped, then let the JVM go.
                Runtime.getRuntime().addShutdownHook(Thread {
                    serving.cancel()
                    runBlocking { withTimeoutOrNull(5_000) { serving.join() } }
                })
                serve(options, protocolIn, protocolOut)
            }
            0
        } catch (cancelled: CancellationException) {
            130
        } catch (failure: Throwable) {
            System.err.println("mcp-stdio: stopped: ${failure::class.simpleName}")
            1
        }
        exitProcess(status)
    }

    private fun printable(text: String): String = buildString { for (c in text.take(80)) append(if (c < ' ' || c == '\u007f') '?' else c) }

    suspend fun serve(options: McpStdioOptions, input: InputStream, output: OutputStream) {
        val nio = NioSupervisor()
        val jevaOwner = SupervisorJob()
        var jeva: JevaElement? = null
        var shared: SharedContextElement? = null
        var transport: HtxElement? = null
        var reactor: MuxReactorElement? = null
        try {
            nio.open()
            val files = checkNotNull(nio.service<FileOperations>()) { "no filesystem service" }
            val cas: CasStore = options.store?.let { FileCasStore(files, it) } ?: CasStore.inMemory()
            // One owner for every request this process serves: concurrent calls for the same missing block become one transfer.
            shared = if (options.scopes.isEmpty()) null
                else SharedContextRoot.open("mcp-shared-context", cas, SharedContextSettings(options.scopes, options.peer), JvmProcessOperations())
            val context: SharedContext? = shared?.context

            val oversight = if (!options.oversightMlx) null else {
                val config = JevaConfig.configured({ null }, { System.getenv(it) })
                check(config.device == "mlx" && config.runtime == JevaRuntime.PYTHON) { "--oversight mlx needs the local MLX Jeva: source config/jeva-mlx.env.sh first" }
                val element = JevaElement(CoroutineScope(currentCoroutineContext() + jevaOwner), config)
                jeva = element
                JevaOversight { state, questions -> element.ask(state, questions) }
            }

            val port = if (options.lanes.isEmpty()) null else {
                val keys = KeyMux { options.lanes.forEach { bind("llm.${it.provider}.key", EnvVarSource(it.keyEnv)) } }
                val mux = ModelMux(keys) {
                    options.lanes.forEach { model(id = it.model, caps = setOf("chat"), baseUrl = it.baseUrl, provider = it.provider) }
                }
                options.lanes.forEach { EgressAllowlist.allowUrl(it.baseUrl) }
                val ownedTransport = openHtxElement(nioSupervisor = nio)
                transport = ownedTransport
                val ownedReactor = MuxReactorElement()
                reactor = ownedReactor
                ownedReactor.open()
                MuxModelPort(options.lanes, mux, ownedTransport, ownedReactor)
            }

            val tools: List<McpTool> =
                ModelMuxTools(context, oversight, options.allowWrite, port).tools() + ZenithTools(options.allowExec, options.journalDir).tools()
            val server = McpToolServer(
                "trikeshed-modelmux-zenith", VERSION, tools,
                instructions = "ModelMux breadcrumbs and Zenith missions. Call zenith_status first: it states what the Zenith port does not do.",
                maxRequestChars = options.maxLineBytes,
                observer = { r -> System.err.println("mcp\t${r.method}\t${r.tool ?: "-"}\t${r.outcome}\t${r.millis}ms") },
            )
            System.err.println(
                "mcp\tstart\tversion=$VERSION\ttools=${server.toolNames.joinToString(",")}\texec=${options.allowExec}\t" +
                    "write=${options.allowWrite}\tscopes=${options.scopes.size}\tstore=${if (options.store != null) "durable" else "memory"}\t" +
                    "oversight=${if (options.oversightMlx) "mlx" else "off"}\tmodels=${options.lanes.size}\tpeer=${if (options.peer != null) "kubo" else "off"}",
            )
            McpLineServer(server, options.maxLineBytes, options.maxInFlight) { kind -> System.err.println("mcp\ttransport\t${printable(kind)}") }
                .serve(InputStreamLineSource(input), OutputStreamLineSink(output))
        } finally {
            withContext(NonCancellable) {
                // Admitted transfers settle first: they read and write the store the supervisor below closes.
                try { shared?.drain() }
                finally {
                    try { jeva?.drain() }
                    finally {
                        jevaOwner.cancel()
                        try { reactor?.drain(); reactor?.close() }
                        finally {
                            try { transport?.drain(); transport?.close() }
                            finally { nio.drain(); nio.close() }
                        }
                    }
                }
            }
        }
    }
}
