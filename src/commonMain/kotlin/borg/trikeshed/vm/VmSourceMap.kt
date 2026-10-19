package borg.trikeshed.vm

/** Line numbers name either the emitted guest declaration or the author's function body. */
enum class VmSourceCoordinates { GENERATED, AUTHORED }

/** The wrapper emitter owns this contiguous body span; adapter lines have no authored coordinate. */
data class VmSourceMap(val generatedStartLine: Int, val lineCount: Int, val columnOffset: Int) {
    init {
        require(generatedStartLine > 0 && lineCount > 0 && columnOffset >= 0)
        require(generatedStartLine.toLong() + lineCount - 1 <= Int.MAX_VALUE)
    }

    fun line(generated: Int?): Int? = generated?.takeIf {
        it >= generatedStartLine && it.toLong() < generatedStartLine.toLong() + lineCount
    }?.let { it - generatedStartLine + 1 }

    /** Project the already bounded copied window; no guest reads or separately retained full source. */
    fun observation(snapshot: Map<String, Any?>): Map<String, Any?> {
        val authoredLine = line((snapshot["line"] as? Number)?.toInt())
        val column = (snapshot["column"] as? Number)?.toInt()
        val first = (snapshot["lineStart"] as? Number)?.toInt() ?: 1
        val source = snapshot["sourceLines"] as? List<*> ?: emptyList<Any?>()
        val start = maxOf(first, generatedStartLine)
        val end = minOf(first.toLong() + source.size, generatedStartLine.toLong() + lineCount)
        val lines = if (start.toLong() >= end) emptyList() else (start.toLong() until end).map { at ->
            (source[(at - first).toInt()] as String).drop(columnOffset)
        }
        val breakpoint = (snapshot["breakpoint"] as? Map<*, *>)?.let { hit -> mapOf(
            "hit" to hit["hit"], "requestedLine" to line((hit["requestedLine"] as? Number)?.toInt()),
            "resolvedLine" to line((hit["resolvedLine"] as? Number)?.toInt())) }
        return snapshot + ("authored" to mapOf(
            "generatedStartLine" to generatedStartLine, "lineCount" to lineCount,
            "line" to authoredLine, "column" to if (authoredLine != null && column != null && column > columnOffset)
                column - columnOffset else null,
            "lineStart" to if (lines.isEmpty()) null else start - generatedStartLine + 1,
            "sourceLines" to lines, "breakpoint" to breakpoint))
    }
}
