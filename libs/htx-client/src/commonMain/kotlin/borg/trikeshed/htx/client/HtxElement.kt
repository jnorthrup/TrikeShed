package borg.trikeshed.htx.client

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState

// Compromise: this standalone lib does not depend on the root project to avoid composite-build cycles.
data class HtxClientMessage(val status: Int = 200, val body: String = "ok")
/**
 * aria2c switch equivalents for the HTX client.
 *
 * Maps to the most commonly used aria2c options from BinancePipelineMacos:
 *   -Z  --force-serialization        (always on for this pipeline)
 *   -c  --continue                   (resume partial download)
 *   --save-not-found=false           (404 is not a retryable error)
 *   -x15 --max-connection-per-server=15
 *   -j15 --max-concurrent-downloads=15
 *   -s15 --split=15
 *   -d  --dir=<dir>
 */
data class Aria2Switches(
    val continueDownload: Boolean = true,
    val saveNotFound: Boolean = false,
    val maxConnectionsPerServer: Int = 15,
    val maxConcurrentDownloads: Int = 15,
    val split: Int = 15,
    val dir: String? = null,
) {
    /** Render as an aria2c argument list. Uris are appended separately. */
    fun toArgs(): List<String> = buildList {
        add("-Z")  // --force-serialization (always on for this pipeline)
        if (continueDownload) add("-c")
        if (!saveNotFound) add("--save-not-found=false")
        add("-x$maxConnectionsPerServer")
        add("-j$maxConcurrentDownloads")
        add("-s$split")
        dir?.let { add("-d"); add(it) }
    }
}

data class HtxClientRequest(
    val method: String,
    val path: String,
    val body: String = "",
    /** aria2c switch equivalents. When present, the transport layer dispatches via aria2c. */
    val switches: Aria2Switches? = null,
    /** URI(s) to fetch — only used when [switches] is set (aria2c dispatch). */
    val uris: List<String> = emptyList(),
)

typealias HtxRequestHandler = suspend (HtxClientRequest) -> HtxClientMessage

val HtxKey: AsyncContextKey<HtxElement> = HtxElement.Key
suspend fun defaultHtxRequestHandler(request: HtxClientRequest): HtxClientMessage =
    when {
        request.method.isBlank() || request.path.isBlank() -> HtxClientMessage(status = 400, body = "invalid request")
        request.method == "GET" && request.path == "/health" -> HtxClientMessage(status = 200, body = "ok")
        request.path == "/health" -> HtxClientMessage(status = 405, body = "method not allowed")
        else -> HtxClientMessage(status = 404, body = "not found")
    }

suspend fun openHtxElement(
    requestHandler: HtxRequestHandler = ::defaultHtxRequestHandler,
): HtxElement =
    HtxElement(requestHandler).also { it.open() }

class HtxElement(
   val requestHandler: HtxRequestHandler = ::defaultHtxRequestHandler,
) : AsyncContextElement() {
    companion object Key : AsyncContextKey<HtxElement>()

    override val key: AsyncContextKey<HtxElement>
        get() = Key

    suspend fun request(
        method: String = "GET",
        path: String = "/",
        body: String = "",
        switches: Aria2Switches? = null,
        uris: List<String> = emptyList(),
    ): HtxClientMessage {
        requireState(ElementState.OPEN)
        return requestHandler(
            HtxClientRequest(
                method = method.trim().uppercase(),
                path = path,
                body = body,
                switches = switches,
                uris = uris,
            ),
        )
    }
}
