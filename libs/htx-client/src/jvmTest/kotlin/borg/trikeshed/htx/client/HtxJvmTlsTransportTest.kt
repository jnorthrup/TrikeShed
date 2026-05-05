package borg.trikeshed.htx.client

import borg.trikeshed.htx.client.generated.api.DefaultHtxGeneralApi
import kotlinx.coroutines.test.runTest
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtxJvmTlsTransportTest {
    @Test
    fun generatedHealthCallUsesTlsTransport() = runTest {
        tlsServer(
            mapOf("/health" to TestResponse(200, "text/plain", "ok".encodeToByteArray())),
        ).use { server ->
            val element = openHtxElement(HtxJvmTlsTransport(server.baseUrl, server.clientSslContext)::handle)
            val api = DefaultHtxGeneralApi(element.generatedCall())
            assertEquals("ok", api.getHealth())
        }
    }

    @Test
    fun zipUriDownloadUsesTlsTransportAndAriaSwitchDirectory() = runTest {
        val zipBytes = zipOf(
            "BTCUSDT-1m-2021-05.csv",
            "1619827200000,57697.25000000,57697.26000000,57533.75000000,57545.06000000,106.99194300,1619827259999,6165380.96980849,4248,40.81299200,2351783.49801049,0\n",
        )
        tlsServer(
            mapOf("/archive.zip" to TestResponse(200, "application/zip", zipBytes)),
        ).use { server ->
            val downloadDir = Files.createTempDirectory("htx-zip-download")
            val element = openHtxElement(HtxJvmTlsTransport(server.baseUrl, server.clientSslContext)::handle)

            val response = element.request(
                method = "GET",
                path = "/download",
                switches = Aria2Switches(dir = downloadDir.toString()),
                uris = listOf("${server.baseUrl}/archive.zip"),
            )

            val downloaded = downloadDir.resolve("archive.zip")
            assertEquals(200, response.status)
            assertTrue(Files.exists(downloaded))
            assertEquals(zipBytes.size.toLong(), Files.size(downloaded))
        }
    }
}

private data class TestResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
)

private class TestTlsServer(
    val baseUrl: String,
    val clientSslContext: SSLContext,
    private val serverSocket: SSLServerSocket,
    private val running: AtomicBoolean,
    private val worker: Thread,
) : AutoCloseable {
    override fun close() {
        running.set(false)
        runCatching { serverSocket.close() }
        worker.join(2_000)
    }
}

private fun tlsServer(routes: Map<String, TestResponse>): TestTlsServer {
    val tempDir = Files.createTempDirectory("htx-tls-server")
    val keystorePath = generateKeystore(tempDir)
    val password = "changeit".toCharArray()
    val serverContext = sslContextFromKeystore(keystorePath, password)
    val clientContext = trustContextFromKeystore(keystorePath, password)
    val serverSocket = serverContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
    val running = AtomicBoolean(true)
    val worker = thread(start = true, isDaemon = true, name = "htx-jvm-tls-test-server") {
        while (running.get()) {
            val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
            socket.use { conn ->
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val requestLine = reader.readLine() ?: return@use
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = requestLine.split(" ")[1]
                val response = routes[path] ?: TestResponse(404, "text/plain", "missing".encodeToByteArray())
                val header = buildString {
                    append("HTTP/1.1 ${response.status} ")
                    append(if (response.status == 200) "OK" else "Not Found")
                    append("\r\n")
                    append("Content-Type: ${response.contentType}\r\n")
                    append("Content-Length: ${response.body.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }.encodeToByteArray()
                conn.outputStream.use { out ->
                    out.write(header)
                    out.write(response.body)
                    out.flush()
                }
            }
        }
    }
    val baseUrl = "https://localhost:${serverSocket.localPort}"
    return TestTlsServer(baseUrl, clientContext, serverSocket, running, worker)
}

private fun generateKeystore(tempDir: Path): Path {
    val keystore = tempDir.resolve("test-server.p12")
    val process = ProcessBuilder(
        "keytool",
        "-genkeypair",
        "-alias", "test",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-storetype", "PKCS12",
        "-keystore", keystore.toString(),
        "-storepass", "changeit",
        "-keypass", "changeit",
        "-dname", "CN=localhost",
        "-validity", "1",
        "-ext", "SAN=dns:localhost",
        "-noprompt",
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { output }
    return keystore
}

private fun sslContextFromKeystore(path: Path, password: CharArray): SSLContext {
    val keystore = KeyStore.getInstance("PKCS12")
    Files.newInputStream(path).use { keystore.load(it, password) }
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(keystore, password)
    return SSLContext.getInstance("TLS").apply {
        init(kmf.keyManagers, null, null)
    }
}

private fun trustContextFromKeystore(path: Path, password: CharArray): SSLContext {
    val keystore = KeyStore.getInstance("PKCS12")
    Files.newInputStream(path).use { keystore.load(it, password) }
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(keystore)
    return SSLContext.getInstance("TLS").apply {
        init(null, tmf.trustManagers, null)
    }
}

private fun zipOf(entryName: String, csv: String): ByteArray =
    ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(csv.encodeToByteArray())
            zip.closeEntry()
        }
        bytes.toByteArray()
    }
