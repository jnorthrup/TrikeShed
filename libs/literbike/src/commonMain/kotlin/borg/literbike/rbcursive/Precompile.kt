package borg.literbike.rbcursive

/**
 * RBCursive Precompile - Sum of All Parsers at Compile Time.
 * Pre-baked parser patterns for zero-runtime-overhead protocol dispatch.
 * Ported from literbike/src/rbcursive/precompile.rs.
 */

/**
 * Pre-baked byte patterns for protocol detection.
 */
object PrecompiledPatterns {
    val ollamaGenerate: ByteArray = "POST /api/generate".toByteArray()
    val ollamaChat: ByteArray = "POST /api/chat".toByteArray()
    val ollamaTags: ByteArray = "GET /api/tags".toByteArray()
    val ollamaShow: ByteArray = "POST /api/show".toByteArray()
    val openaiChat: ByteArray = "POST /v1/chat/completions".toByteArray()
    val openaiModels: ByteArray = "GET /v1/models".toByteArray()
    val anthropicMessages: ByteArray = "POST /v1/messages".toByteArray()
    val health: ByteArray = "GET /health".toByteArray()
    val metrics: ByteArray = "GET /metrics".toByteArray()
    val httpGet: ByteArray = "GET ".toByteArray()
    val httpPost: ByteArray = "POST ".toByteArray()

    /** Fast prefix match */
    private fun startsWith(data: ByteArray, pattern: ByteArray): Boolean {
        return data.size >= pattern.size && data.copyOf(pattern.size).contentEquals(pattern)
    }

    /** Detect protocol from precompiled patterns */
    fun detectProtocol(data: ByteArray): String = when {
        startsWith(data, ollamaGenerate) -> "ollama/generate"
        startsWith(data, ollamaChat) -> "ollama/chat"
        startsWith(data, ollamaTags) -> "ollama/tags"
        startsWith(data, ollamaShow) -> "ollama/show"
        startsWith(data, openaiChat) -> "openai/chat"
        startsWith(data, openaiModels) -> "openai/models"
        startsWith(data, anthropicMessages) -> "anthropic/messages"
        startsWith(data, health) -> "health"
        startsWith(data, metrics) -> "metrics"
        startsWith(data, httpGet) -> "http/get"
        startsWith(data, httpPost) -> "http/post"
        else -> "unknown"
    }
}
