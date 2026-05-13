package borg.trikeshed.htx.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private val jvmHttpClient: HttpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

actual fun createHttpsHandler(): HtxRequestHandler = { request: HtxClientRequest ->
    withContext(Dispatchers.IO) {
        val uri = URI(request.path.toString())
        val builder = HttpRequest.newBuilder(uri)
            .method(
                request.method.toString(),
                if (request.body.isEmpty()) HttpRequest.BodyPublishers.noBody()
                else HttpRequest.BodyPublishers.ofString(request.body.toString())
            )

        for ((k, v) in request.headers) {
            if (k.toString() != "X-Query-Params") {
                builder.header(k.toString(), v.toString())
            }
        }

        val response = jvmHttpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val headers: Map<CharSequence, CharSequence> = response.headers().map().entries.associate { (k, values) ->
            k as CharSequence to values.joinToString(",") as CharSequence
        }

        HtxClientMessage(
            status = response.statusCode(),
            body = response.body(),
            headers = headers,
        )
    }
}
