import kotlin.time.measureTime

fun main() {
    val lines = (1..100000).map { "Line number $it with some extra text to make it longer and more realistic for writing." }

    val timeMem = measureTime {
        val content = lines.joinToString(separator = "\n", postfix = "\n")
        val bytes = content.encodeToByteArray()
    }

    val timeBuf = measureTime {
        val bufferSize = 8192
        val buffer = ByteArray(bufferSize)
        var offset = 0

        fun flush() {
            if (offset > 0) {
                // simulate write
                offset = 0
            }
        }

        lines.forEach { line ->
            val bytes = line.encodeToByteArray()
            var bytesWritten = 0
            while (bytesWritten < bytes.size) {
                val space = bufferSize - offset
                val toCopy = minOf(space, bytes.size - bytesWritten)
                bytes.copyInto(buffer, offset, bytesWritten, bytesWritten + toCopy)
                offset += toCopy
                bytesWritten += toCopy

                if (offset == bufferSize) {
                    flush()
                }
            }
            // Now add newline character
            if (offset == bufferSize) {
                flush()
            }
            buffer[offset++] = '\n'.code.toByte()
        }
        flush()
    }

    println("Memory-based: $timeMem")
    println("Buffered: $timeBuf")
}
