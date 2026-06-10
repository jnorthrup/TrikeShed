package borg.trikeshed.couch.transport.htx

enum class DispatchMode(val isRequestFactory: Boolean) {
    REQUEST_FACTORY(true),
    STANDARD(false)
}

data class InvocationPlan(
    val requestPath: String,
    val contentType: String,
    val dispatchMode: DispatchMode,
    val allowsRelaxFactoryStyleCouchServices: Boolean,
)

class HtxRequestFactoryBridge {
    fun decode(rawRequest: String): InvocationPlan {
        val lines = rawRequest.split("\r\n")
        val requestLine = lines.firstOrNull() ?: ""
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0) ?: ""
        val requestPath = parts.getOrNull(1) ?: ""

        var contentType = ""
        for (line in lines.drop(1)) {
            if (line.startsWith("Content-Type:", ignoreCase = true)) {
                contentType = line.substringAfter(":").trim()
                break
            }
        }

        val isGwtRequest = method == "POST" && requestPath == "/gwtRequest"
        val dispatchMode = if (isGwtRequest) DispatchMode.REQUEST_FACTORY else DispatchMode.STANDARD

        return InvocationPlan(
            requestPath = requestPath,
            contentType = contentType,
            dispatchMode = dispatchMode,
            allowsRelaxFactoryStyleCouchServices = isGwtRequest,
        )
    }
}
