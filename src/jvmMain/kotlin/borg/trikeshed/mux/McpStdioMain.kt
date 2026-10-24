package borg.trikeshed.mux

import borg.trikeshed.mcp.InputStreamLineSource
import borg.trikeshed.mcp.OutputStreamLineSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.system.exitProcess

/**
 * JVM boundary for the stdio MCP server. The policy, the protocol and the tools are commonMain ([McpStdio]); this file binds
 * stdin/stdout and owns the process lifecycle.
 *
 * stdout is the protocol channel and nothing else may write to it, so the process's `System.out` is pointed at stderr
 * before anything runs. Log lines carry method, tool name, outcome and duration, never arguments or results.
 */
object McpStdioMain {
    const val VERSION: String = McpStdio.VERSION

    private object JvmHost : McpStdioHost {
        override fun print(text: String) = System.out.println(text)

        override fun diagnose(line: String) = System.err.println(line)

        override fun claimProtocol(): ProtocolChannel {
            val input = FileInputStream(FileDescriptor.`in`)
            val output = FileOutputStream(FileDescriptor.out)
            System.setOut(System.err)
            return ProtocolChannel(InputStreamLineSource(input), OutputStreamLineSink(output))
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val status = try {
            runBlocking(Dispatchers.IO) {
                val serving = coroutineContext[Job]!!
                // SIGTERM/SIGINT: cancel in-flight work so child processes are reaped, then let the JVM go.
                Runtime.getRuntime().addShutdownHook(Thread {
                    serving.cancel()
                    runBlocking { withTimeoutOrNull(5_000) { serving.join() } }
                })
                McpStdio.run(args, JvmHost)
            }
        } catch (cancelled: CancellationException) {
            130
        } catch (failure: Throwable) {
            System.err.println("mcp-stdio: stopped: ${failure::class.simpleName}")
            1
        }
        exitProcess(status)
    }
}
