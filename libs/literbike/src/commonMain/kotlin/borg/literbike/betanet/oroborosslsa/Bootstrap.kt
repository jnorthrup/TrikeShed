package borg.literbike.betanet.oroborosslsa

import borg.literbike.betanet.oroborosslsa.canonicalizer.canonicalize

/**
 * Bootstrap - Scan files and prepare bootstrap JSON for oroboros.
 * Ported from literbike/src/betanet/oroboros_slsa/bootstrap.rs.
 *
 * Note: The Rust version runs `git ls-files` and reads files from disk.
 * This Kotlin version provides the same logic but works with provided file contents
 * (since Kotlin Multiplatform doesn't have direct git/FS access in commonMain).
 */

/**
 * A bootstrap document entry.
 */
data class BootstrapDoc(
    val id: String,
    val row: List<String>
)

/**
 * Bootstrap from a list of file entries (path + content).
 * Mirrors the Rust bootstrap_from_git logic but accepts pre-read content.
 *
 * @param files list of (path, content) pairs
 * @return list of BootstrapDoc
 */
fun bootstrapFromFiles(files: List<Pair<String, String>>): List<BootstrapDoc> {
    val docs = mutableListOf<BootstrapDoc>()

    for ((path, content) in files) {
        val ext = path.substringAfterLast('.', "").lowercase()

        if (ext == "json") {
            val canonical = canonicalize(content)
            if (canonical.isNotEmpty()) {
                docs.add(BootstrapDoc(path, listOf(canonical)))
            }
        } else if (ext == "jsonl" || ext == "ndjson") {
            for ((i, line) in content.lineSequence().withIndex()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val canonical = canonicalize(trimmed)
                if (canonical.isEmpty()) continue
                docs.add(BootstrapDoc("$path:$i", listOf(canonical)))
            }
        }
    }

    return docs
}

/**
 * Serialize bootstrap docs to JSON string.
 */
fun bootstrapDocsToJson(docs: List<BootstrapDoc>): String {
    return docs.joinToString(
        prefix = "[",
        postfix = "]",
        separator = ","
    ) { doc ->
        val idStr = escapeJson(doc.id)
        val rowStr = doc.row.joinToString(
            prefix = "[",
            postfix = "]",
            separator = ","
        ) { "\"${escapeJson(it)}\"" }
        "{\"id\":\"$idStr\",\"row\":$rowStr}"
    }
}

private fun escapeJson(s: String): String = s
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")
