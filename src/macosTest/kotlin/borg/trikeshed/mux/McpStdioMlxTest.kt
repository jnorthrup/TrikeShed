@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.mux

import borg.trikeshed.mcp.*
import borg.trikeshed.lib.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*
import kotlin.test.*

/** Startup and framing only; this does not qualify a real MLX checkpoint. */
class McpStdioMlxTest {
    private class Host : McpStdioHost {
        val diagnostics = mutableListOf<String>()
        val replies = mutableListOf<String>()
        override fun print(text: String) {}
        override fun diagnose(line: String) { diagnostics.add(line) }
        override fun claimProtocol(): ProtocolChannel {
            var read = false
            return ProtocolChannel(object : LineSource {
                override suspend fun read(maxBytes: Int): LineRead = if (read) LineRead.Eof else {
                    read = true
                    LineRead.Line("""{"jsonrpc":"2.0","id":1,"method":"ping"}""".encodeToByteArray())
                }
            }, object : LineSink {
                override suspend fun write(line: ByteArray) { replies.add(line.decodeToString()) }
            })
        }
    }

    private fun withRuntime(runtime: String?, block: () -> Unit) {
        val names = _l["TRIKESHED_JEVA_DEVICE", "TRIKESHED_JEVA_RUNTIME"]
        val previous = names.associateWith { getenv(it)?.toKString() }
        try {
            setenv(names[0], "mlx", 1)
            if (runtime == null) unsetenv(names[1]) else setenv(names[1], runtime, 1)
            block()
        } finally {
            previous.forEach { (name, value) ->
                if (value == null) unsetenv(name) else setenv(name, value, 1)
            }
        }
    }

    @Test fun explicitKotlinStartsAndServesPing() = withRuntime("kotlin") {
        val host = Host()
        assertEquals(0, runBlocking { McpStdio.run(_a["--oversight", "mlx"], host) }, host.diagnostics.toString())
        assertTrue(host.diagnostics.any { "unqualified" in it }, host.diagnostics.toString())
        assertTrue(host.replies.single().contains("\"id\":1"), host.replies.toString())
    }

    @Test fun explicitPythonStillStartsAndServesPing() = withRuntime("python") {
        val host = Host()
        assertEquals(0, runBlocking { McpStdio.run(_a["--oversight", "mlx"], host) }, host.diagnostics.toString())
        assertTrue(host.replies.single().contains("\"id\":1"), host.replies.toString())
    }

    @Test fun defaultKotlinIsNotImplicitlyEnabled() = withRuntime(null) {
        val host = Host()
        assertEquals(1, runBlocking { McpStdio.run(_a["--oversight", "mlx"], host) })
        assertTrue(host.diagnostics.any { "TRIKESHED_JEVA_RUNTIME" in it }, host.diagnostics.toString())
    }
}
