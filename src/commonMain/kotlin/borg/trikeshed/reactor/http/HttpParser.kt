package borg.trikeshed.reactor.http

import borg.trikeshed.reactor.ByteBuffer

fun parseHeaders(buffer: ByteBuffer): Map<String, String> {
    val headers = mutableMapOf<String, String>()
    var lineStart = 0

    while (buffer.hasRemaining()) {
        val char = buffer.get().toInt().toChar()

        if (char == '\n') {
            val lineEnd = buffer.position() - 1
            val line = extractLineFromBuffer(buffer, lineStart, lineEnd)

            if (line.isNullOrEmpty()) {
                break
            }

            val colonIndex = line.indexOf(':')
            if (colonIndex != -1) {
                val key = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                headers[key] = value
            }

            lineStart = buffer.position()
        }
    }

    return headers
}

fun extractLineFromBuffer(buffer: ByteBuffer, start: Int, end: Int): String? {
    if (end <= start) return null
    val lineBytes = ByteArray(end - start)
    val oldPosition = buffer.position()
    buffer.position(start)
    buffer.get(lineBytes)
    buffer.position(oldPosition)
    return String(lineBytes).trim()
}
