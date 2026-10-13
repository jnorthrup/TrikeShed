package borg.trikeshed.htx

import borg.trikeshed.lib.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout

/**
 * Common outbound JSON client over the context-owned HTX transport.
 *
 * Every request resolves the TLS-backed [HtxElement] from [HtxKey] and goes
 * through the reactor. There is no platform HTTP client or per-client TLS
 * configuration: TLS belongs to the route service installed by the daemon.
 */
class TrikeHtxHttpClient(
    private val base: String,
    private val defaultHeaders: HtxHeaders = emptyHtxHeaders(),
) {
    suspend fun get(path: String): String = exchange(HtxMethod.GET, path, null)

    suspend fun post(path: String, json: String): String = exchange(HtxMethod.POST, path, json)

    suspend fun delete(path: String): String = exchange(HtxMethod.DELETE, path, null)

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
        htx.requestResult(req).getOrThrow().body.toArray().decodeToString()
    }

    private fun normalizePath(path: String): String =
        if (path.startsWith('/')) path else "/$path"
}
