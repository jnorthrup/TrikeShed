import kotlin.time.measureTime
import java.io.File

fun main() {
    val lines = (1..500_000).map { "This is line number $it of a large file to test write performance." }

    // Warmup
    var warmupSum = 0L
    for(i in 0..5) {
        val f = File("warmup.txt")
        val content = lines.take(1000).joinToString(separator = "\n", postfix = "\n")
        f.writeBytes(content.encodeToByteArray())
        f.delete()
        warmupSum += i
    }

    val f1 = File("test_mem.txt")
    val timeMem = measureTime {
        val content = lines.joinToString(separator = "\n", postfix = "\n")
        val bytes = content.encodeToByteArray()
        f1.writeBytes(bytes)
    }

    val f2 = File("test_buf.txt")
    val timeBuf = measureTime {
        val bufferSize = 8192
        val buffer = ByteArray(bufferSize)
        var offset = 0

        fun flush() {
            if (offset > 0) {
                f2.appendBytes(buffer.copyOfRange(0, offset))
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
            if (offset == bufferSize) flush()
            buffer[offset++] = '\n'.code.toByte()
        }
        flush()
    }

    println("Current (Memory-based string building): $timeMem")
    println("Optimized (Chunked buffer): $timeBuf")

    val improvement = (timeMem.inWholeMilliseconds - timeBuf.inWholeMilliseconds).toDouble() / timeMem.inWholeMilliseconds * 100
    println("Improvement: %.2f%%".format(improvement))

    f1.delete()
    f2.delete()
}
