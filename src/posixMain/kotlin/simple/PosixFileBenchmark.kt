package simple

import kotlin.time.TimeSource

fun main() {
    val lines = List(100000) { "This is a line of text, specifically line number $it" }
    val filename = "benchmark_output.txt"
    val mark = TimeSource.Monotonic.markNow()
    PosixFile.writeLines(filename, lines)
    println("Time taken: ${mark.elapsedNow().inWholeMilliseconds} ms")
}
