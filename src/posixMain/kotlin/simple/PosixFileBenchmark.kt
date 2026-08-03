package simple

import kotlin.system.getTimeMillis
import kotlin.random.Random

fun main() {
    val lines = List(100000) { "This is a line of text, specifically line number $it" }
    val filename = "benchmark_output.txt"
    val startTime = getTimeMillis()
    PosixFile.writeLines(filename, lines)
    val endTime = getTimeMillis()
    println("Time taken: ${endTime - startTime} ms")
}
