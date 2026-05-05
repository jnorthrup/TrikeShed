package borg.trikeshed.htx.client

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

class HtxJvmTlsTransport(
    private val baseUrl: String,
    private val sslContext: SSLContext? = null,
) {
    suspend fun handle(request: HtxClientRequest): HtxClientMessage =
        if (request.uris.isNotEmpty()) {
            downloadAll(request)
        } else {
            execute(request)
        }

    private fun execute(request: HtxClientRequest): HtxClientMessage {
        val connection = openConnection(resolveUrl(request.path), request.method)
        writeRequestBody(connection, request.body)
        val status = connection.responseCode
        val body = readResponseText(connection)
        connection.disconnect()
        return HtxClientMessage(status = status, body = body)
    }

    private fun downloadAll(request: HtxClientRequest): HtxClientMessage {
        val dir = request.switches?.dir?.let(Path::of)
            ?: error("HTX URI downloads require Aria2Switches.dir")
        Files.createDirectories(dir)
        var lastStatus = 200
        request.uris.forEach { uri ->
            val connection = openConnection(uri, "GET")
            val status = connection.responseCode
            lastStatus = status
            require(status in 200..299) {
                "HTX download failed for $uri with status $status and body ${readResponseText(connection)}"
            }
            val fileName = fileNameOf(uri)
            val target = dir.resolve(fileName)
            connection.inputStream.use { input ->
                Files.newOutputStream(target).use { output -> input.copyTo(output) }
            }
            connection.disconnect()
        }
        return HtxClientMessage(status = lastStatus, body = "downloaded ${request.uris.size} file(s)")
    }

    private fun resolveUrl(path: String): String = when {
        path.startsWith("https://") || path.startsWith("http://") -> path
        else -> baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    private fun openConnection(url: String, method: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.requestMethod = method
        connection.instanceFollowRedirects = true
        if (connection is HttpsURLConnection && sslContext != null) {
            connection.sslSocketFactory = sslContext.socketFactory
        }
        return connection
    }

    private fun writeRequestBody(connection: HttpURLConnection, body: String) {
        if (body.isEmpty()) return
        connection.doOutput = true
        connection.outputStream.use { out -> out.write(body.encodeToByteArray()) }
    }

    private fun readResponseText(connection: HttpURLConnection): String {
        val stream = connection.errorStream ?: connection.inputStream ?: return ""
        return stream.use(InputStream::readBytes).toString(Charsets.UTF_8)
    }

    private fun fileNameOf(uri: String): String =
        Path.of(URI(uri).path).fileName?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: error("Unable to derive file name from $uri")
}
