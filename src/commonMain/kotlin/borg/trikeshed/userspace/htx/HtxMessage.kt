package borg.trikeshed.userspace.htx

/**
 * HTX message — complete HTTP message in internal format.
 * Blocks metadata at END; payloads at BEGINNING (ring buffer layout).
 */
class HtxMessage(
    val blocks: MutableList<HtxBlockData> = mutableListOf(),
    var flags: UInt = HtxFlags.NONE,
) {
    fun isEmpty(): Boolean = blocks.isEmpty()
    fun size(): Int = blocks.size

    fun addStartLine(sl: HtxStartLine) { blocks.add(HtxBlockData.StartLine(sl)) }
    fun addHeader(name: ByteArray, value: ByteArray) {
        blocks.add(HtxBlockData.Header(name, value))
    }
    fun addData(data: ByteArray) { blocks.add(HtxBlockData.Data(data)) }
    fun addTrailer(name: ByteArray, value: ByteArray) {
        blocks.add(HtxBlockData.Trailer(name, value))
    }
    fun addEndHeaders() { blocks.add(HtxBlockData.EndHeaders) }
    fun addEndTrailers() { blocks.add(HtxBlockData.EndTrailers) }
    fun setEom() { flags = HtxFlags.EOM.mask }

    fun startLine(): HtxStartLine? = blocks
        .filterIsInstance<HtxBlockData.StartLine>()
        .firstOrNull()?.sl

    fun headers(): Sequence<Pair<ByteArray, ByteArray>> = sequence {
        for (b in blocks) {
            if (b is HtxBlockData.Header) yield(b.name to b.value)
        }
    }

    companion object {
        /**
         * Parse HTTP/1.x text into an HTX message.
         */
        fun parseHttp1(input: ByteArray): HtxMessage? {
            val text = input.decodeToString()
            val msg = HtxMessage()
            var state = ParseState.RequestLine

            for (rawLine in text.lines()) {
                val line = rawLine.trimEnd('\r')
                when (state) {
                    ParseState.RequestLine -> {
                        val req = parseRequestLine(line)
                        if (req != null) {
                            val (method, uri, version) = req
                            msg.addStartLine(HtxStartLine.request(method, uri.encodeToByteArray(), version.first, version.second))
                            state = ParseState.Headers
                            continue
                        }
                        val resp = parseStatusLine(line)
                        if (resp != null) {
                            val (status, reason, version) = resp
                            msg.addStartLine(HtxStartLine.response(status, reason.encodeToByteArray(), version.first, version.second))
                            state = ParseState.Headers
                            continue
                        }
                        return null
                    }
                    ParseState.Headers -> {
                        if (line.isEmpty()) {
                            msg.addEndHeaders()
                            state = ParseState.Body
                            continue
                        }
                        val (name, value) = parseHeader(line) ?: continue
                        msg.addHeader(name.encodeToByteArray(), value.encodeToByteArray())
                    }
                    ParseState.Body -> {
                        if (line.isNotEmpty()) msg.addData(line.encodeToByteArray())
                    }
                }
            }
            msg.setEom()
            return msg
        }

        /**
         * Normalize bytes to HTX, auto-detecting protocol.
         */
        fun normalizeToHtx(input: ByteArray): HtxMessage {
            val preview = input.copyOf(minOf(input.size, 1024))
            val text = preview.decodeToString()

            if (text.startsWith("HTTP/") || text.startsWith("GET ") || text.startsWith("POST ") ||
                text.startsWith("PUT ") || text.startsWith("DELETE ") || text.startsWith("HEAD ") ||
                text.startsWith("OPTIONS ") || text.startsWith("PATCH ") || text.startsWith("CONNECT ") ||
                text.startsWith("TRACE ")
            ) {
                return parseHttp1(input) ?: HtxMessage()
            }
            // HTTP/2 connection preface
            if (input.size >= 24 && input.copyOfRange(0, 24).contentEquals("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".encodeToByteArray())) {
                // TODO: HTTP/2 frame parsing
            }
            return HtxMessage()
        }

        private fun parseRequestLine(line: String): Triple<HttpMethod, String, Pair<Int, Int>>? {
            val parts = line.split(' ', limit = 3)
            if (parts.size < 3) return null
            val method = HttpMethod.fromString(parts[0]) ?: return null
            val uri = parts[1]
            val version = parseVersion(parts[2]) ?: return null
            return Triple(method, uri, version)
        }

        private fun parseStatusLine(line: String): Triple<Int, String, Pair<Int, Int>>? {
            val parts = line.split(' ', limit = 3)
            if (parts.size < 2 || !parts[0].startsWith("HTTP/")) return null
            val version = parseVersion(parts[0]) ?: return null
            val status = parts[1].toIntOrNull() ?: return null
            val reason = parts.getOrElse(2) { "" }
            return Triple(status, reason, version)
        }

        private fun parseVersion(s: String): Pair<Int, Int>? {
            if (!s.startsWith("HTTP/")) return null
            val rest = s.substring(5)
            val parts = rest.split('.', limit = 2)
            if (parts.size < 2) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts[1].toIntOrNull() ?: return null
            return major to minor
        }

        private fun parseHeader(line: String): Pair<String, String>? {
            val colonPos = line.indexOf(':')
            if (colonPos == -1) return null
            return line.substring(0, colonPos).trim() to line.substring(colonPos + 1).trim()
        }
    }
}

private enum class ParseState { RequestLine, Headers, Body }
