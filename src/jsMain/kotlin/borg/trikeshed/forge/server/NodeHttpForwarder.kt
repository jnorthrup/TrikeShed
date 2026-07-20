package borg.trikeshed.forge.server

import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit
import org.khronos.webgl.Int8Array

object NodeHttpForwarder {
    suspend fun send(baseUrl: String, spec: HttpForwarderSpec): HttpForwarderResponse {
        val init = RequestInit(
            method = spec.verb,
            headers = Headers().apply { spec.headers.forEach { (k, v) -> append(k, v) } },
            body = spec.body.decodeToString(),  // treat as UTF-8; binary via base64 in test
        )
        val response = window.fetch("$baseUrl${spec.path}", init).await()
        val buf = response.arrayBuffer().await()
        val bytes = Int8Array(buf).unsafeCast<ByteArray>()
        return HttpForwarderResponse(
            status = response.status.toInt(),
            headers = emptyMap(),
            body = bytes,
        )
    }
}
