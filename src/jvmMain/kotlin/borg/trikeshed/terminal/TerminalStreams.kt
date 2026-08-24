package borg.trikeshed.terminal

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/** Blocking terminal stdin: block for the first byte, then drain only what is already queued. */
class TerminalInputStream : InputStream() {
    private val queue = LinkedBlockingQueue<Int>()
    @Volatile private var closed = false

    fun push(text: String) = push(text.encodeToByteArray())

    fun push(bytes: ByteArray) {
        check(!closed) { "terminal input is closed" }
        for (byte in bytes) queue.put(byte.toInt() and 0xff)
    }

    override fun read(): Int = if (closed && queue.isEmpty()) -1 else queue.take().let { if (it == EOF) -1 else it }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        if (closed && queue.isEmpty()) return -1
        val first = queue.take()
        if (first == EOF) return -1
        bytes[offset] = first.toByte()
        var count = 1
        while (count < length) {
            val next = queue.poll() ?: break
            if (next == EOF) { closed = true; break }
            bytes[offset + count] = next.toByte()
            count++
        }
        return count
    }

    override fun close() { closed = true; queue.offer(EOF) }

    companion object { private const val EOF = -1 }
}

/**
 * Incremental UTF-8 decoder and virtual PTY output line discipline. Graal's OutputStream is not a
 * PTY: multibyte characters can straddle writes and bare LF needs OPOST/ONLCR before VT parsing.
 */
class TerminalOutputStream(
    private val onlcr: Boolean = true,
    private val onText: (String) -> Unit,
) : OutputStream() {
    private val pending = ByteArrayOutputStream()
    private var previousWasCr = false

    @Synchronized override fun write(value: Int) {
        pending.write(value)
        emitComplete()
    }

    @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
        pending.write(bytes, offset, length)
        emitComplete()
    }

    @Synchronized override fun flush() = emitComplete(force = true)
    @Synchronized override fun close() = emitComplete(force = true)

    private fun emitComplete(force: Boolean = false) {
        val bytes = pending.toByteArray()
        if (bytes.isEmpty()) return
        var index = 0
        var complete = 0
        while (index < bytes.size) {
            val unsigned = bytes[index].toInt() and 0xff
            val width = when {
                unsigned < 0x80 -> 1
                unsigned in 0xC2..0xDF -> 2
                unsigned in 0xE0..0xEF -> 3
                unsigned in 0xF0..0xF4 -> 4
                else -> 1
            }
            if (index + width > bytes.size) break
            complete = index + width
            index += width
        }
        if (force) complete = bytes.size
        if (complete == 0) return
        val decoded = bytes.copyOfRange(0, complete).toString(Charsets.UTF_8)
        val terminalText = if (!onlcr) decoded else buildString(decoded.length + 8) {
            for (char in decoded) {
                if (char == '\n' && !previousWasCr) append('\r')
                append(char)
                previousWasCr = char == '\r'
            }
        }
        if (terminalText.isNotEmpty()) onText(terminalText)
        pending.reset()
        if (complete < bytes.size) pending.write(bytes, complete, bytes.size - complete)
    }
}
