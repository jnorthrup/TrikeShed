package borg.trikeshed.htx.client

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * JVM HTTPS transport handler using java.net.http.HttpClient (Java 11+).
 */
actual fun createHttpsHandler(): HtxRequestHandler = { request ->
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    val builder = HttpRequest.newBuilder()
        .uri(URI.create(request.path))
        .timeout(Duration.ofSeconds(30))

    request.headers.forEach { (k, v) -> builder.header(k, v) }

    builder.method(request.method,
        if (request.body.isEmpty()) HttpRequest.BodyPublishers.noBody()
        else HttpRequest.BodyPublishers.ofString(request.body)
    )

    val httpReq = builder.build()
    val resp = client.send(httpReq, HttpResponse.BodyHandlers.ofByteArray())
    val bytes = resp.body()
    val bodyStr = String(bytes, java.nio.charset.StandardCharsets.UTF_8)

    HtxClientMessage(status = resp.statusCode(), body = bodyStr, binaryBody = bytes)
}
