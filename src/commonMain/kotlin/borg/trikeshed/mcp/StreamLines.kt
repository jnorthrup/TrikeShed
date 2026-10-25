package borg.trikeshed.mcp

/**
 * Newline-framed reads over any chunked byte source: a process's stdin, a socket handed to us as fd 0 by tcpd, a pipe, a test script.
 * The platform supplies [readChunk] and nothing else. It fills the front of the buffer it is given and returns how many bytes it wrote:
 * a negative number is the end of the input, and zero means nothing arrived this time and the read is asked again.
 *
 * A final line without a terminator is still a line, so `printf '{...}' | server` works. At most `maxBytes + 1` bytes of a line are
 * ever held (the extra one is the CR of a CRLF); a longer line is skipped to its terminator without being buffered and reported as
 * [LineRead.TooLong].
 *
 * Whether a blocked [readChunk] can be interrupted is the platform's property, not this class's: a blocking `read(2)` or
 * `InputStream.read` is not cancellable, so cancelling the caller does not unblock it. End of input or the end of the process does.
 */
class ChunkedLineSource(private val readChunk: suspend (ByteArray) -> Int, bufferSize: Int = 8_192) : LineSource {
    private val buffer = ByteArray(bufferSize.also { require(it > 0) { "buffer must hold at least one byte" } })
    private var position = 0
    private var limit = 0
    private var exhausted = false

    override suspend fun read(maxBytes: Int): LineRead {
        require(maxBytes > 0)
        val line = Accumulator()
        var overflow = false
        var sawAny = false
        while (true) {
            if (position == limit) {
                val n = if (exhausted) -1 else readChunk(buffer)
                check(n <= buffer.size) { "the byte source returned more than it was given room for" }
                if (n < 0) {
                    exhausted = true
                    return when {
                        overflow -> LineRead.TooLong
                        sawAny -> finish(line, maxBytes)
                        else -> LineRead.Eof
                    }
                }
                if (n == 0) continue
                position = 0
                limit = n
            }
            var end = position
            while (end < limit && buffer[end] != NEWLINE) end++
            val chunk = end - position
            if (chunk > 0 || end < limit) sawAny = true
            if (!overflow) {
                if (line.size.toLong() + chunk > maxBytes.toLong() + 1) overflow = true
                else line.append(buffer, position, chunk)
            }
            if (end < limit) {
                position = end + 1
                return if (overflow) LineRead.TooLong else finish(line, maxBytes)
            }
            position = limit
        }
    }

    private fun finish(line: Accumulator, maxBytes: Int): LineRead {
        var bytes = line.toByteArray()
        if (bytes.isNotEmpty() && bytes[bytes.size - 1] == CARRIAGE_RETURN) bytes = bytes.copyOf(bytes.size - 1)
        return if (bytes.size > maxBytes) LineRead.TooLong else LineRead.Line(bytes)
    }

    /** Grows only while the caller has already checked the bound, so it never holds more than `maxBytes + 1`. */
    private class Accumulator {
        private var bytes = ByteArray(64)
        var size = 0
            private set

        fun append(source: ByteArray, from: Int, count: Int) {
            if (size + count > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + count))
            source.copyInto(bytes, size, from, from + count)
            size += count
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)
    }

    private companion object {
        const val NEWLINE: Byte = '\n'.code.toByte()
        const val CARRIAGE_RETURN: Byte = '\r'.code.toByte()
    }
}

/**
 * Writes each reply as one line plus a terminator in a single [writeAll], which must deliver the whole frame and flush it. The sink is
 * the framing authority: a line that contains a terminator would be read back as two, so it is refused instead of sent.
 */
class FramedLineSink(private val writeAll: suspend (ByteArray) -> Unit) : LineSink {
    override suspend fun write(line: ByteArray) {
        for (b in line) check(b != NEWLINE && b != CARRIAGE_RETURN) { "reply contains a line break" }
        val framed = ByteArray(line.size + 1)
        line.copyInto(framed)
        framed[line.size] = NEWLINE
        writeAll(framed)
    }

    private companion object {
        const val NEWLINE: Byte = '\n'.code.toByte()
        const val CARRIAGE_RETURN: Byte = '\r'.code.toByte()
    }
}
