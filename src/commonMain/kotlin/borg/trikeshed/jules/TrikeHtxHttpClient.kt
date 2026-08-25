package borg.trikeshed.jules

import borg.trikeshed.htx.HtxElement
import borg.trikeshed.htx.HtxHeaders
import borg.trikeshed.htx.HtxKey
import borg.trikeshed.htx.HtxMethod
import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxResponse
import borg.trikeshed.htx.emptyHtxBody
import borg.trikeshed.htx.emptyHtxHeaders
import borg.trikeshed.htx.htxHeaders
import borg.trikeshed.htx.parseHtxRequest
import borg.trikeshed.htx.withHeader
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.toArray
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout

/** Typed reactor-HTX response failure with the remote status preserved. */
class HtxHttpException(val status: Int, message: String) : RuntimeException(message)

/** Jules retains this name for its cycle HTTP-status accounting API. */
typealias JulesHttpException = HtxHttpException

/**
 * Common outbound HTTP client for Jules/Brain callers.
 *
 * Every request resolves the TLS-backed [HtxElement] from [HtxKey] and goes
 * through the reactor. There is no platform HTTP client or per-client TLS
 * configuration: TLS belongs to the route service installed by the daemon.
 */
class TrikeHtxHttpClient(
    private val base: String,
    private val defaultHeaders: HtxHeaders = emptyHtxHeaders(),
) : JulesHttpClient {
    override suspend fun get(path: String): String = exchange(HtxMethod.GET, path, null)

    override suspend fun post(path: String, json: String): String = exchange(HtxMethod.POST, path, json)

    override suspend fun delete(path: String): String = exchange(HtxMethod.DELETE, path, null)

    private suspend fun exchange(method: HtxMethod, path: String, json: String?): String = withTimeout(45_000) {
        val htx = currentCoroutineContext()[HtxKey]
            ?: error("No HtxKey in coroutine context — install a TLS-backed HtxElement around this client call.")
        val bytes = json?.encodeToByteArray() ?: ByteArray(0)
        val req = (parseHtxRequest(
            url = "$base${normalizePath(path)}",
            method = method,
            body = if (bytes.isEmpty()) emptyHtxBody() else ByteSeries(bytes),
        ) as HtxRequest).copy(headers = htxHeaders(
            *defaultHeaders.toArray(),
        )).withHeader("Content-Type", "application/json")
            .withHeader("Content-Length", bytes.size.toString())
        val response: HtxResponse = htx.request(req)
        val body = response.body.toArray().decodeToString()
        if (response.status >= 400) {
            throw HtxHttpException(response.status, "HTTP ${response.status}: ${body.take(300)}")
        }
        body
    }

    private fun normalizePath(path: String): String =
        if (path.startsWith('/')) path else "/$path"
}
