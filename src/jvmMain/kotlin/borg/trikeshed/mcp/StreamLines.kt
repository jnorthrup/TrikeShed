package borg.trikeshed.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Newline-framed reads over a blocking [InputStream] (stdin, a socket handed to us as fd 0 by tcpd, a pipe).
 *
 * A final line without a terminator is still a line, so `printf '{...}' | server` works. At most `maxBytes + 1` bytes
 * of a line are ever held (the extra one is the CR of a CRLF); a longer line is skipped to its terminator without
 * being buffered and reported as [LineRead.TooLong].
 *
 * Blocking reads run on the IO dispatcher and are not interruptible: cancelling the caller does not unblock a read
 * that is waiting for input. Closing the stream (EOF) or ending the process does.
 */
class InputStreamLineSource(private val input: InputStream, bufferSize: Int = 8_192) : LineSource {
    private val buffer = ByteArray(bufferSize)
    private var position = 0
    private var limit = 0
    private var exhausted = false

    override suspend fun read(maxBytes: Int): LineRead = withContext(Dispatchers.IO) { readBlocking(maxBytes) }

    private fun readBlocking(maxBytes: Int): LineRead {
        require(maxBytes > 0)
        val line = ByteArrayOutputStream()
        var overflow = false
        var sawAny = false
        while (true) {
            if (position == limit) {
                val n = if (exhausted) -1 else input.read(buffer, 0, buffer.size)
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
                if (line.size().toLong() + chunk > maxBytes.toLong() + 1) overflow = true
                else line.write(buffer, position, chunk)
            }
            if (end < limit) {
                position = end + 1
                return if (overflow) LineRead.TooLong else finish(line, maxBytes)
            }
            position = limit
        }
    }

    private fun finish(line: ByteArrayOutputStream, maxBytes: Int): LineRead {
        var bytes = line.toByteArray()
        if (bytes.isNotEmpty() && bytes[bytes.size - 1] == CARRIAGE_RETURN) bytes = bytes.copyOf(bytes.size - 1)
        return if (bytes.size > maxBytes) LineRead.TooLong else LineRead.Line(bytes)
    }

    private companion object {
        const val NEWLINE: Byte = '\n'.code.toByte()
        const val CARRIAGE_RETURN: Byte = '\r'.code.toByte()
    }
}

/**
 * Writes each reply as one line plus a terminator in a single `write`, then flushes. The sink is the framing
 * authority: a line that contains a terminator would be read back as two, so it is refused instead of sent.
 */
class OutputStreamLineSink(private val output: OutputStream) : LineSink {
    override suspend fun write(line: ByteArray) {
        for (b in line) check(b != NEWLINE && b != CARRIAGE_RETURN) { "reply contains a line break" }
        val framed = ByteArray(line.size + 1)
        line.copyInto(framed)
        framed[line.size] = NEWLINE
        withContext(Dispatchers.IO) {
            output.write(framed)
            output.flush()
        }
    }

    private companion object {
        const val NEWLINE: Byte = '\n'.code.toByte()
        const val CARRIAGE_RETURN: Byte = '\r'.code.toByte()
    }
}
