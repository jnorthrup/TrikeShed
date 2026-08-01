package borg.trikeshed.jules

import borg.trikeshed.htx.HtxElement
import borg.trikeshed.htx.HtxKey
import borg.trikeshed.htx.HtxMethod
import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxResponse
import borg.trikeshed.htx.emptyHtxBody
import borg.trikeshed.htx.htxHeaders
import borg.trikeshed.htx.parseHtxRequest
import borg.trikeshed.htx.withHeader
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toArray
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout

/**
 * Typed HTTP error from the Jules REST API. Carries the status code so the
 * flywheel can classify 429 (rate-limit) vs 4xx/5xx and surface per-cycle
 * counters in the TUI. Thrown by [JvmJulesHttpClient] on any status >= 400.
 */
class JulesHttpException(val status: Int, message: String) : RuntimeException(message)

/**
 * JVM actual of [JulesHttpClient] routed through the reactor HTX transport
 * ([HtxElement], keyed by [HtxKey] in the coroutine context).
 *
 * The daemon installs a TLS-backed [HtxElement] (mTLS via [TlsConfig] →
 * [borg.trikeshed.reactor.JvmTlsCodecBackend]) into the flywheel's coroutine
 * context. This client resolves that element and calls [HtxElement.request],
 * so every Jules API call flows through the same TLS + reactor path as
 * ModelMux/KeyMux — no standalone java.net.http.HttpClient, no transport
 * bypass. mTLS (client cert + trust store + ClientAuth) is configured once on
 * the route service, not per-client.
 *
 * 45s hard bound on the whole request: the reactor exchange can park on a
 * half-dead pooled connection without firing a request timeout, and one parked
 * send wedges the flywheel cycle coroutine for hours.
 */
class JvmJulesHttpClient(
    private val apiKey: String,
    private val base: String = "https://jules.googleapis.com/v1alpha",
) : JulesHttpClient {

    override suspend fun get(path: String): String = withTimeout(45_000) {
        exchange("GET", path, null)
    }

    override suspend fun post(path: String, json: String): String = withTimeout(45_000) {
        exchange("POST", path, json)
    }

    override suspend fun delete(path: String): String = withTimeout(45_000) {
        exchange("DELETE", path, null)
    }

    private suspend fun exchange(method: String, path: String, json: String?): String {
        val htx = currentCoroutineContext()[HtxKey]
            ?: error("No HtxKey in coroutine context — daemon must install a TLS-backed HtxElement.")

        val url = "$base${normalizePath(path)}"
        val body = json?.encodeToByteArray() ?: ByteArray(0)
        val htxMethod = when (method) {
            "GET" -> HtxMethod.GET
            "DELETE" -> HtxMethod.DELETE
            else -> HtxMethod.POST
        }
        val headers = htxHeaders(
            "x-goog-api-key" j apiKey,
            "Content-Type" j "application/json",
        )
        val req = (parseHtxRequest(
            url = url,
            method = htxMethod,
            body = if (body.isEmpty()) emptyHtxBody() else ByteSeries(body),
        ) as HtxRequest).copy(headers = headers).withHeader("Content-Length", body.size.toString())

        val resp: HtxResponse = htx.request(req)
        val respBody = resp.body.toArray().decodeToString()
        if (resp.status >= 400) throw JulesHttpException(resp.status, "Jules API ${resp.status}: ${respBody.take(300)}")
        return respBody
    }

    private fun normalizePath(path: String): String =
        if (path.startsWith("/")) path else "/$path"
}
