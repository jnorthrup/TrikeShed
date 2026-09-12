package borg.trikeshed.litebike


/**
 * The HTTP contract the daemon's wire surfaces program against — extracted from
 * the JVM kanban server so wires name the CONTRACT, not the JVM transport.
 * The AIO listener stays JVM; these shapes are what every wire shares.
 */
data class WireHttpResponse(
    val status: Int,
    val body: String,
    val contentType: String = "application/json; charset=utf-8",
    /** Binary payload; when set it is written instead of [body]. */
    val bytes: ByteArray? = null,
    /** Extra response headers (e.g. X-Content-Id on a curation read). */
    val headers: Map<String, String> = emptyMap(),
) {
    val payloadBytes: ByteArray get() = bytes ?: body.encodeToByteArray()
}

/**
 * Extension seam: tried after built-in routes and static assets, first
 * non-null wins (BlackboardWire, VmWire, …).
 */
typealias WireExtraRoute = suspend (method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)?) -> WireHttpResponse?

/** Binary-safe routes: tried after /api built-ins, before static assets. */
typealias WireRawRoute = suspend (method: String, path: String, payload: ByteArray, respond: (suspend (ByteArray) -> Unit)?) -> WireHttpResponse?
