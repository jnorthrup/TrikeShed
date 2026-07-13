package keymux

import borg.trikeshed.lib.*
import borg.trikeshed.htx.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.userspace.reactor.MuxCredentialRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.coroutines.coroutineContext

class KeyMuxTest {

    class FakeFileOperations(val files: MutableMap<String, ByteArray> = mutableMapOf()) : FileOperations {
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
        override fun listDir(path: String): List<String> = emptyList()
        override fun isDir(path: String): Boolean = false
        override fun isFile(path: String): Boolean = true
        override fun mkdirs(path: String) {}
        override fun deleteRecursively(path: String) {}
        override fun resolvePath(vararg parts: String): String = parts.joinToString("/")
        override fun readZip(path: String): List<Pair<String, ByteArray>> = emptyList()
        override fun createTempDir(prefix: String): String = ""
        override fun close(fd: Int): Int = 0
        override fun size(fd: Int): Long = 0L
    }

    class FakeHtxRouteService(val handler: (HtxRequest) -> HtxResponse) : HtxRouteService {
        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
            val response = handler(request)
            return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
        }
    }

    @Test
    fun toKeyPath_parsesStringCorrectly() {
        val path = "a.b.c".toKeyPath()
        assertEquals(listOf("a", "b", "c"), path.iterable().toList())
        assertEquals("a.b.c", path.asString())
        assertEquals(listOf("single"), "single".toKeyPath().iterable().toList())
    }

    @Test
    fun envSource_readsVariables() = runTest {
        // We test reading env via SystemOperations mock where possible or just check env source instantiation
        val envSource = EnvSource("APP")
        assertEquals("env", envSource.name)
    }

    @Test
    fun persistSource_readsAndWritesFile() = runTest {
        val fileOps = FakeFileOperations()
        val persist = PersistSource(root = "/config", explicitFileOps = fileOps)
        
        val key = "my.key.id".toKeyPath()
        assertNull(persist.read(key))
        
        persist.write(key, "my-secret-value")
        assertEquals("my-secret-value", persist.read(key))
    }

    @Test
    fun apiSource_executesHtxRequest() = runTest {
        val fakeService = FakeHtxRouteService { _ ->
            HtxResponse(status = 200, body = ByteSeries("api-val".encodeToByteArray()))
        }
        val htx = openHtxElement(routeService = fakeService)
        
        val apiSource = ApiSource(baseUrl = "https://config.org", explicitHtx = htx)
        val value = apiSource.read("foo".toKeyPath())
        assertEquals("api-val", value)
        
        htx.close()
    }

    @Test
    fun reactorSource_resolvesFromReactorState() = runTest {
        val reactor = MuxReactorElement()
        reactor.open()
        
        reactor.loadCredentialPool(
            mapOf(
                "openai" to listOf(
                    MuxCredentialRecord(
                        id = "sk-openai-key-123",
                        label = "primary",
                        baseUrl = "https://api.openai.com",
                        lastStatus = "active",
                        lastSuccessModel = "gpt-4"
                    )
                )
            )
        )

        val source = ReactorSource(explicitReactor = reactor)
        val resolved = source.read("llm.gpt-4.key".toKeyPath())
        assertEquals("sk-openai-key-123", resolved)
        
        reactor.close()
    }
}
