package borg.trikeshed.lcnc

import borg.trikeshed.context.ElementState
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.GuestModules
import borg.trikeshed.graal.subvm.TikaRuntime
import borg.trikeshed.htx.HtxElement
import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.size
import borg.trikeshed.narsese.DocumentAppendLog
import borg.trikeshed.narsese.DocumentCasStore
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.reactor.JvmTlsCodecBackend
import borg.trikeshed.reactor.JvmTlsCodecBackendTest
import borg.trikeshed.reactor.TlsApplicationProtocol
import borg.trikeshed.reactor.TlsConfig
import borg.trikeshed.reactor.TlsConnectionState
import borg.trikeshed.reactor.TlsFlowStage
import borg.trikeshed.reactor.TlsFrames
import borg.trikeshed.reactor.TlsProtocol
import borg.trikeshed.reactor.openTlsElement
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.sockaddrIpv4
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Both HTTPS peers move ciphertext through common userspace SQEs; fixtures never touch the live daemon home. */
@Timeout(90)
class HeadhunterHttpsTest {
    @Test
    fun persistedCookieAcquiresHttpsAndSavedCorrectionsReplayAfterRestart() = runBlocking {
        withContext(Dispatchers.Default + fileIoContext) {
            withTimeout(60_000) {
                assertTrue(GuestModules.isInstalled(TikaRuntime.MODULE), "Install the existing managed Tika module")
                val directory = Files.createTempDirectory("headhunter-https-")
                val certificate = directory.resolve("certificate.pem")
                val key = directory.resolve("key.pem")
                val casPath = directory.resolve("evidence.cas").toString()
                val logPath = directory.resolve("evidence.wal").toString()
                val parent = directory.toString()
                // This follows the existing uring TLS fixture: reserve a port only; all payload IO is SQE-driven.
                val port = ServerSocket(0).use { it.localPort }
                val url = "https://localhost:$port/jobs/42"
                val cookie = "session=isolated-https-session-7421"
                val html = "<html><body><nav>Source navigation</nav><p>[[listing]]Kotlin engineer source 7421[[/listing]]</p></body></html>"
                Files.writeString(certificate, JvmTlsCodecBackendTest.TEST_CERTIFICATE_PEM)
                Files.writeString(key, JvmTlsCodecBackendTest.TEST_PRIVATE_KEY_PEM)
                val ready = CompletableDeferred<Unit>(coroutineContext[Job])
                val authenticated = mutableListOf<Boolean>()
                val server = launch { sourceServer(port, certificate, key, cookie, html, ready, authenticated) }
                val reactor = HtxReactorElement(JvmChannelOperations(), JvmTlsCodecBackend(),
                    tlsConfig = TlsConfig(trustStore = certificate.toString(), hostnameVerification = true,
                        protocols = s_[TlsProtocol.TLS13], alpnProtocols = s_[TlsApplicationProtocol.HTTP_1_1]),
                    parentJob = coroutineContext[Job]).also { it.open() }
                val htx = HtxElement(routeService = reactor, parentJob = coroutineContext[Job]).also { it.open() }
                try {
                    ready.await()
                    var sourceId = ""
                    var originalCid = ""
                    var originalTextCid = ""
                    var extractedTextCid = ""
                    var credentialRef = ""
                    DocumentCasStore.open(casPath, parent).use { cas ->
                        DocumentAppendLog.open(logPath, parent).use { log ->
                            HeadhunterCredentials.open(parent).use { credentials ->
                                val store = HeadhunterStore(ConfixBlackboard.empty(), cas, log) { 1_000L }
                                store.restore()
                                val sources = HeadhunterSources(cas, credentials.keys)
                                val blocked = withContext(htx) { sources.capture(mapOf("url" to url)) }
                                assertEquals("blocked", blocked["status"])
                                assertEquals(403, blocked["httpStatus"])
                                assertNull(blocked["text"])
                                val saved = sources.saveCredential(mapOf("url" to url, "value" to cookie, "pathPrefix" to "/jobs"))
                                credentialRef = saved["credentialRef"] as String
                                val source = store.save("source", null, null, mapOf(
                                    "title" to "Isolated authenticated source", "origin" to url, "mode" to "http",
                                    "credentialRef" to credentialRef,
                                    "extraction" to mapOf("startAfter" to "[[listing]]", "endBefore" to "[[/listing]]"),
                                ))
                                sourceId = source["id"] as String
                                val captured = withContext(htx) { sources.capture(mapOf("url" to url), config(source)) }
                                assertEquals("ready", captured["status"], captured["error"] as? String)
                                assertEquals(200, captured["httpStatus"])
                                assertEquals("Kotlin engineer source 7421", captured["text"])
                                assertEquals("tika-${TikaRuntime.TIKA_VERSION}", captured["extractor"])
                                originalCid = captured["originalCid"] as String
                                originalTextCid = captured["textCid"] as String
                                extractedTextCid = captured["extractedTextCid"] as String
                                assertContentEquals(html.encodeToByteArray(), cas.get(ContentId(originalCid)))
                                assertNoCredential(cookie, saved, captured, store.board.snapshot().values)
                            }
                        }
                    }
                    DocumentCasStore.open(casPath, parent).use { cas ->
                        DocumentAppendLog.open(logPath, parent).use { log ->
                            HeadhunterCredentials.open(parent).use { credentials ->
                                val store = HeadhunterStore(ConfixBlackboard.empty(), cas, log) { 2_000L }
                                assertEquals(1, store.restore())
                                val prior = assertNotNull(store.record(sourceId))
                                assertEquals(credentialRef, HeadhunterStore.fieldsOf(prior)["credentialRef"])
                                val corrected = store.save("source", sourceId, prior["cid"] as String,
                                    HeadhunterStore.fieldsOf(prior) + ("extraction" to mapOf(
                                        "startAfter" to "[[listing]]", "endBefore" to "[[/listing]]",
                                        "replacements" to mapOf("engineer" to "developer"),
                                    )))
                                val sources = HeadhunterSources(cas, credentials.keys)
                                val repeated = withContext(htx) { sources.capture(mapOf("url" to url), config(corrected)) }
                                assertEquals("ready", repeated["status"], repeated["error"] as? String)
                                assertEquals("Kotlin developer source 7421", repeated["text"])
                                assertEquals(originalCid, repeated["originalCid"])
                                assertEquals(extractedTextCid, repeated["extractedTextCid"])
                                assertNotEquals(originalTextCid, repeated["textCid"])
                                assertEquals(2, store.history(sourceId).size)
                                assertEquals(prior["cid"], corrected["previousCid"])
                                val privateRevision = credentials.database.docJson("source-credential:$credentialRef")?.get("_rev") as String
                                assertNull(cas.get(ContentId(privateRevision.substringAfter('-'))))
                                assertTrue(credentials.keys.listProviders().isEmpty())
                                assertNoCredential(cookie, repeated, store.board.snapshot().values)
                            }
                        }
                    }
                    server.join()
                    assertEquals(listOf(false, true, true), authenticated)
                } finally {
                    withContext(NonCancellable) {
                        try { reactor.drain() } finally { htx.close() }
                        server.cancel()
                        server.join()
                        Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
                        Files.deleteIfExists(directory)
                    }
                }
            }
        }
    }

    fun config(record: HeadhunterRecord): Map<String, Any?> = HeadhunterStore.fieldsOf(record)
        .filterKeys { it in setOf("mode", "credentialRef", "extraction") }

    fun assertNoCredential(cookie: String, vararg values: Any?) {
        for (value in values) {
            val serialized = JsonSupport.stringify(value)
            assertFalse(serialized.contains(cookie))
            assertFalse(serialized.contains(cookie.substringAfter('=')))
        }
    }

    suspend fun sourceServer(
        port: Int, certificate: Path, key: Path, cookie: String, html: String,
        ready: CompletableDeferred<Unit>, authenticated: MutableList<Boolean>,
    ): Unit = coroutineScope {
        val ring = FunctionalUringFacade.create(this, 16)
        val tls = openTlsElement(TlsConfig(certificateFile = certificate.toString(), privateKeyFile = key.toString(),
            protocols = s_[TlsProtocol.TLS13], alpnProtocols = s_[TlsApplicationProtocol.HTTP_1_1], hostnameVerification = true),
            JvmTlsCodecBackend(), parentJob = coroutineContext[Job])
        val descriptors = mutableListOf<Int>()
        var admitted = 0L
        var settled = 0L
        suspend fun submit(op: UringOp, fd: Int, buffer: ByteBuffer? = null, length: Int = buffer?.remaining() ?: 0, offset: Long = 0): Int {
            val id = ++admitted
            val result = ring.batchEnqueue(1 j { UringSubmission(op, fd, 0, length, offset, userData = id, buffer = buffer) })
            assertEquals(1, result.size)
            assertEquals(id, result[0].userData)
            settled++
            return result[0].res
        }
        try {
            val listener = submit(UringOp.SOCKET, 2, length = 6, offset = 1)
            assertTrue(listener >= 0)
            descriptors += listener
            assertEquals(0, submit(UringOp.BIND, listener, ByteBuffer(sockaddrIpv4(byteArrayOf(127, 0, 0, 1), port))))
            assertEquals(0, submit(UringOp.LISTEN, listener, length = 4))
            ready.complete(Unit)
            repeat(3) {
                var fd: Int
                do { fd = submit(UringOp.ACCEPT, listener); if (fd == -11) delay(2) } while (fd == -11)
                assertTrue(fd >= 0, "TLS fixture accept failed")
                descriptors += fd
                val endpoint = tls.serverEndpoint("127.0.0.1", port)
                val plaintext = java.io.ByteArrayOutputStream()
                suspend fun emit(frames: TlsFrames) {
                    for (i in 0 until frames.size) {
                        val frame = frames[i]
                        when (frame.stage) {
                            TlsFlowStage.DOWNSTREAM_PLAINTEXT -> {
                                assertTrue(plaintext.size() + frame.payload.rem <= 65_536)
                                plaintext.write(frame.payload.toArray())
                            }
                            TlsFlowStage.UPSTREAM_CIPHERTEXT, TlsFlowStage.CLOSE_NOTIFY -> {
                                val bytes = ByteBuffer(frame.payload.toArray())
                                while (bytes.hasRemaining()) {
                                    val count = submit(UringOp.WRITE, fd, bytes, offset = -1)
                                    if (count == -11) delay(2) else assertTrue(count > 0, "TLS fixture ciphertext write failed")
                                }
                            }
                            else -> Unit
                        }
                    }
                }
                suspend fun receive() {
                    val bytes = ByteBuffer(4096)
                    var count: Int
                    do { count = submit(UringOp.READ, fd, bytes, offset = -1); if (count == -11) delay(2) } while (count == -11)
                    assertTrue(count > 0, "TLS fixture reached EOF before close_notify")
                    emit(endpoint.downstream(ByteSeries(bytes.array().copyOf(count))))
                }
                emit(endpoint.handshake())
                while (!endpoint.isHandshakeComplete) receive()
                assertEquals(TlsProtocol.TLS13, endpoint.session?.protocol)
                while (!plaintext.toString(Charsets.UTF_8).contains("\r\n\r\n")) receive()
                val request = plaintext.toString(Charsets.UTF_8)
                assertEquals("GET /jobs/42 HTTP/1.1", request.substringBefore("\r\n"))
                val accepted = request.lineSequence().any { it.substringBefore(':').equals("Cookie", ignoreCase = true) && it.substringAfter(':').trim() == cookie }
                authenticated += accepted
                val body = (if (accepted) html else "Sign in required").encodeToByteArray()
                val status = if (accepted) "200 OK" else "403 Forbidden"
                val response = "HTTP/1.1 $status\r\nContent-Type: ${if (accepted) "text/html; charset=utf-8" else "text/plain"}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".encodeToByteArray() + body
                emit(endpoint.upstream(ByteSeries(response)))
                while (endpoint.flowState.lifecycle != TlsConnectionState.CLOSED) receive()
                emit(endpoint.close())
                assertEquals(0, submit(UringOp.CLOSE, fd))
                descriptors.remove(fd)
            }
        } finally {
            withContext(NonCancellable) {
                var cleanupFailure: Throwable? = null
                suspend fun release(action: suspend () -> Unit) {
                    try { action() } catch (failure: Throwable) {
                        cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
                    }
                }
                release { tls.drain(); assertEquals(ElementState.CLOSED, tls.state) }
                for (fd in descriptors.asReversed()) release { assertEquals(0, submit(UringOp.CLOSE, fd)) }
                release { ring.drain() }
                release { assertEquals(admitted, settled, "Every TLS fixture SQE settles before completion") }
                cleanupFailure?.let { throw it }
            }
        }
    }
}
