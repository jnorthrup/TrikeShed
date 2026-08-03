import kotlin.time.measureTime
import java.io.File
import java.nio.file.Files

fun main() {
    val lines = (1..100000).map { "Line number $it with some extra text to make it longer and more realistic for writing." }
    val f1 = File("test1.txt")
    val f2 = File("test2.txt")

    val timeMem = measureTime {
        val content = lines.joinToString(separator = "\n", postfix = "\n")
        val bytes = content.encodeToByteArray()
        f1.writeBytes(bytes)
    }

    val timeBuf = measureTime {
        f2.outputStream().buffered(8192).use { out ->
            lines.forEach { line ->
                out.write(line.encodeToByteArray())
                out.write('\n'.code)
            }
        }
    }

    println("Memory-based: $timeMem")
    println("Buffered: $timeBuf")
    f1.delete()
    f2.delete()
}
