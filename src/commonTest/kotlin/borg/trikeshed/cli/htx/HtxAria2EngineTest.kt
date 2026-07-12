package borg.trikeshed.cli.htx

import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtxAria2EngineTest {

    class MockFileOps : FileOperations {
        val files = mutableMapOf<String, ByteArray>()
        override fun open(path: String, readOnly: Boolean) = 1
        override fun readAllLines(filename: String) = emptyList<String>()
        override fun readAllBytes(filename: String) = files[filename] ?: ByteArray(0)
        override fun readString(filename: String) = ""
        override fun write(filename: String, bytes: ByteArray) { files[filename] = bytes }
        override fun write(filename: String, lines: List<String>) {}
        override fun write(filename: String, string: String) {}
        override fun cwd() = "/"
        override fun exists(filename: String) = files.containsKey(filename)
        override fun streamLines(fileName: String, bufsize: Int) = emptySequence<Join<Long, ByteArray>>()
        override fun iterateLines(fileName: String, bufsize: Int) = emptyList<Join<Long, Series<Byte>>>()
        override fun listDir(path: String) = emptyList<String>()
        override fun isDir(path: String) = false
        override fun isFile(path: String) = files.containsKey(path)
        override fun mkdirs(path: String) {}
        override fun deleteRecursively(path: String) { files.remove(path) }
        override fun resolvePath(vararg parts: String) = parts.joinToString("/")
        override fun readZip(path: String) = emptyList<Pair<String, ByteArray>>()
        override fun createTempDir(prefix: String) = "/tmp/dir"
        override fun close(fd: Int) = 0
        override fun size(fd: Int) = 0L
    }

    class MockHtxClientReactor : HtxClientReactorElement(
        routeService = object : HtxRouteService {
            override val key = HtxRouteService
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val contentLength = 100L
                val range = request.range
                val response = if (request.method == HtxMethod.HEAD) {
                    HtxResponse(200, headers = borg.trikeshed.htx.htxHeaders(
                        "Content-Length" j contentLength.toString(),
                        "Accept-Ranges" j "bytes"
                    ))
                } else if (range != null) {
                    val size = (range.endInclusive - range.startInclusive + 1).toInt()
                    val body = ByteArray(size) { (range.startInclusive + it).toByte() }
                    HtxResponse(206, body = borg.trikeshed.lib.ByteSeries(body))
                } else {
                    val body = ByteArray(contentLength.toInt()) { it.toByte() }
                    HtxResponse(200, body = borg.trikeshed.lib.ByteSeries(body))
                }
                return HtxExchangeResult(state.copy(response = response, lifecycle = HtxExchangeLifecycle.RESPONDED))
            }
        },
        parentJob = null,
        options = HtxClientOptions()
    )

    @Test
    fun testMultiRangeDownload() = runTest {
        val options = Aria2Options(
            dir = "/tmp",
            out = "file.bin",
            split = 4,
            minSplitSize = 10,
            uris = listOf("http://example.com/file.bin")
        )

        val fileOps = MockFileOps()
        val reactor = MockHtxClientReactor()
        reactor.open()

        val engine = HtxAria2Engine(options, reactor, fileOps)
        engine.execute()

        val written = fileOps.readAllBytes("/tmp/file.bin")
        assertEquals(100, written.size)
        for (i in 0 until 100) {
            assertEquals(i.toByte(), written[i])
        }

        reactor.close()
    }
}
