package borg.trikeshed.htx.client

import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlin.js.json

actual fun createHttpsHandler(): HtxRequestHandler = { request ->
    val headers: dynamic = json()
    request.headers.forEach { (k, v) -> headers[k] = v }
    val options: dynamic = json(
        "method" to request.method,
        "headers" to headers,
    )
    if (request.body.isNotEmpty()) {
        options["body"] = request.body
    }
    val response: dynamic = (js("fetch(request.path, options)") as Promise<dynamic>).await()
    val body: String = (response.text() as Promise<String>).await<String>()
    HtxClientMessage(status = response.status as Int, body = body)
}
