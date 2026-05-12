package borg.trikeshed.couch.transport.htx

enum class DispatchMode(val isRequestFactory: Boolean) {
    REQUEST_FACTORY(true),
    STANDARD(false)
}

data class InvocationPlan(
    val requestPath: CharSequence,
    val contentType: CharSequence,
    val dispatchMode: DispatchMode,
    val allowsRelaxFactoryStyleCouchServices: Boolean,
    val headers: Map<CharSequence, CharSequence> = emptyMap(),
)

class HtxRequestFactoryBridge {
    fun decode(rawRequest: CharSequence): InvocationPlan {
        val lines = rawRequest.split("\r\n")
        val requestLine = lines.firstOrNull() ?: ""
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0) ?: ""
        val requestPath = parts.getOrNull(1) ?: ""

        // collect header lines until blank line
        val headerLines = lines.drop(1).takeWhile { it.isNotEmpty() }
        val headers = headerLines.mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx == -1) return@mapNotNull null
            val name = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            name to value
        }.toMap()

        val contentType = headers["content-type"] ?: ""

        val isGwtRequest = method == "POST" && requestPath == "/gwtRequest"
        val dispatchMode = if (isGwtRequest) DispatchMode.REQUEST_FACTORY else DispatchMode.STANDARD

        return InvocationPlan(
            requestPath = requestPath,
            contentType = contentType,
            dispatchMode = dispatchMode,
            allowsRelaxFactoryStyleCouchServices = isGwtRequest,
            headers = headers,
        )
    }
}

