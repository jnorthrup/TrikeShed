package borg.trikeshed.userspace.containment

/**
 * Legion Doc 04 Layer 4 — Deterministic Formatter.
 * Simulates a deterministic code formatter (like black, prettier, or gofmt)
 * by applying deterministic whitespace and ordering normalization to source code.
 */
object DeterministicFormatter {

    /**
     * Applies normalization:
     * - Converts \r\n and \r to \n
     * - Expands tabs to 4 spaces
     * - Strips trailing whitespace
     * - Lexicographically sorts contiguous import blocks
     * - Collapses multiple consecutive blank lines into a single blank line
     * - Ensures a single trailing newline at EOF
     */
    fun format(source: String): String {
        if (source.isBlank()) return ""

        val lines = source.replace("\r\n", "\n").replace("\r", "\n").lines()
        val formattedLines = mutableListOf<String>()
        val importBlock = mutableListOf<String>()

        fun flushImports() {
            if (importBlock.isNotEmpty()) {
                importBlock.sort()
                formattedLines.addAll(importBlock)
                importBlock.clear()
            }
        }

        for (line in lines) {
            val normalizedLine = line.replace("\t", "    ").trimEnd()
            
            if (normalizedLine.startsWith("import ")) {
                importBlock.add(normalizedLine)
            } else {
                flushImports()
                formattedLines.add(normalizedLine)
            }
        }
        flushImports()

        val collapsed = mutableListOf<String>()
        var previousWasBlank = false

        for (line in formattedLines) {
            if (line.isEmpty()) {
                if (!previousWasBlank) {
                    collapsed.add(line)
                    previousWasBlank = true
                }
            } else {
                collapsed.add(line)
                previousWasBlank = false
            }
        }

        while (collapsed.isNotEmpty() && collapsed.first().isEmpty()) {
            collapsed.removeAt(0)
        }
        while (collapsed.isNotEmpty() && collapsed.last().isEmpty()) {
            collapsed.removeAt(collapsed.size - 1)
        }

        if (collapsed.isEmpty()) return "\n"

        return collapsed.joinToString("\n") + "\n"
    }
}
