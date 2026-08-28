package muxcontract

import borg.trikeshed.htx.HtxExchangeLifecycle
import borg.trikeshed.htx.HtxExchangeResult
import borg.trikeshed.htx.HtxExchangeState
import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxResponse
import borg.trikeshed.htx.HtxRouteService
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.nio.file.spi.FileOperations

/**
 * Self-contained fakes for the mux contract suite. Everything the muxes touch —
 * transport, filesystem, key sources — is mocked here. No network, no disk, no env.
 * If the production sources are ever mangled, these fakes plus the tests that use
 * them are the reconstruction spec (docs/mux-repair-contract.md).
 */

/** Scripted HTX transport: records every request body; responds via [handler]. */
class FakeTransport(
    val handler: (body: String, callIndex: Int) -> HtxResponse,
) : HtxRouteService {
    val bodies = mutableListOf<String>()
    var calls = 0
        private set

    override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
        calls++
        val body = request.body.toArray().decodeToString()
        bodies.add(body)
        val response = handler(body, calls)
        return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
    }

    companion object {
        /** Canonical chat-completions payload. */
        fun chatJson(content: String, promptTokens: Int = 5, completionTokens: Int = 5): HtxResponse =
            HtxResponse(200, ByteSeries("""{"choices":[{"message":{"content":"$content"}}],"usage":{"prompt_tokens":$promptTokens,"completion_tokens":$completionTokens}}""".encodeToByteArray()))

        fun status(code: Int, body: String = ""): HtxResponse =
            HtxResponse(code, ByteSeries(body.encodeToByteArray()))

        /** SSE stream body from delta tokens. */
        fun sse(vararg deltas: String): HtxResponse {
            val body = deltas.joinToString("\n") { """data: {"choices":[{"delta":{"content":"$it"}}]}""" } + "\ndata: [DONE]\n"
            return HtxResponse(200, ByteSeries(body.encodeToByteArray()))
        }

        /** Embeddings payload: one vector per input. */
        fun embeddings(dim: Int = 2): HtxResponse =
            HtxResponse(200, ByteSeries("""{"data":[{"embedding":[0.5,0.25]}]}""".encodeToByteArray()))
    }
}

/** In-memory FileOperations. Paths are plain strings joined by '/'. */
class FakeFiles(val files: MutableMap<String, ByteArray> = mutableMapOf()) : FileOperations {
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = FileOperations.Key
    override fun open(path: String, readOnly: Boolean): Int = 0
    override fun readAllLines(filename: String): List<String> = readString(filename).lineSequence().toList()
    override fun readAllBytes(filename: String): ByteArray = files[filename] ?: ByteArray(0)
    override fun readString(filename: String): String = readAllBytes(filename).decodeToString()
    override fun write(filename: String, bytes: ByteArray) { files[filename] = bytes }
    override fun write(filename: String, lines: List<String>) { write(filename, lines.joinToString("\n").encodeToByteArray()) }
    override fun write(filename: String, string: String) { write(filename, string.encodeToByteArray()) }
    override fun cwd(): String = "/"
    override fun exists(filename: String): Boolean = files.containsKey(filename)
    override fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> = emptySequence()
    override fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> = emptyList()
    override fun listDir(path: String): List<String> {
        val prefix = if (path.endsWith("/")) path else "$path/"
        val out = LinkedHashSet<String>()
        for (k in files.keys) {
            if (k.startsWith(prefix)) {
                val rest = k.removePrefix(prefix)
                val seg = rest.substringBefore('/')
                if (seg.isNotEmpty()) out.add(seg)
            }
        }
        return out.toList()
    }
    override fun isDir(path: String): Boolean {
        val prefix = if (path.endsWith("/")) path else "$path/"
        return files.keys.any { it.startsWith(prefix) }
    }
    override fun isFile(path: String): Boolean = files.containsKey(path)
    override fun mkdirs(path: String) {}
    override fun deleteRecursively(path: String) {}
    override fun resolvePath(vararg parts: String): String = parts.joinToString("/")
    override fun readZip(path: String): List<Join<String, ByteArray>> = emptyList()
    override fun createTempDir(prefix: String): String = ""
    override fun close(fd: Int): Int = 0
    override fun size(fd: Int): Long = 0L
}
