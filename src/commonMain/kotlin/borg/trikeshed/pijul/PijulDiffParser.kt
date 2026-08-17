package borg.trikeshed.pijul

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

/**
 * Parses unified diffs into [FileChanges] compatible with [PijulChannel].
 */
object PijulDiffParser {

    fun parse(diffText: String): List<FileChanges> {
        val lines = diffText.lines()
        val result = mutableListOf<FileChanges>()
        var currentPath: String? = null
        val inserts = mutableListOf<Join<Int, String>>()
        val deletes = mutableListOf<Join<Int, Int>>()
        var oldLine = 1

        fun flush() {
            val p = currentPath ?: return
            if (inserts.isNotEmpty() || deletes.isNotEmpty()) {
                result.add(FileChanges(p, inserts.toList(), deletes.toList()))
            }
            inserts.clear()
            deletes.clear()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("diff --git") -> {
                    flush()
                    val parts = line.split(" ")
                    if (parts.size >= 4) {
                        currentPath = parts[3].removePrefix("b/").trim()
                    }
                }
                line.startsWith("--- ") -> {
                    val path = line.removePrefix("--- a/").removePrefix("--- ").trim()
                    if (path != "/dev/null" && path.isNotEmpty()) {
                        if (currentPath == null) currentPath = path
                    }
                }
                line.startsWith("+++ ") -> {
                    val path = line.removePrefix("+++ b/").removePrefix("+++ ").trim()
                    if (path != "/dev/null" && path.isNotEmpty()) {
                        currentPath = path
                    }
                }
                line.startsWith("@@") -> {
                    val match = Regex("""@@\s+-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?\s+@@""").find(line)
                    if (match != null) {
                        oldLine = match.groupValues[1].toInt()
                    }
                }
                line.startsWith("+") && !line.startsWith("+++") -> {
                    val content = line.substring(1) + "\n"
                    inserts.add(oldLine j content)
                }
                line.startsWith("-") && !line.startsWith("---") -> {
                    deletes.add(oldLine j 1)
                    oldLine++
                }
                line.startsWith(" ") -> {
                    oldLine++
                }
            }
            i++
        }
        flush()
        return result
    }
}
