package borg.trikeshed.jules

import borg.trikeshed.jules.JulesRestClient.SessionInfo
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * QA-Laguna — external conflict resolver, runs OUTSIDE the flywheel.
 *
 * The flywheel commits conflict markers as-is and moves on. This component
 * reads the committed conflicts with panorama scope (all sessions in the
 * batch, their includes/imports for merge facts) and resolves them.
 *
 * n=2+ distance: reads both sides' dependency links (imports, referenced
 * symbols) to understand what each side depends on before resolving.
 *
 * Invoked by the daemon after the flywheel cycle, or as a standalone CLI.
 */
object QaLaguna {

    /**
     * Resolve conflict markers in the working tree using the Laguna brain.
     *
     * @param repoDir the git working tree
     * @param brain the Laguna brain client (null = no resolution, markers stay)
     * @param panorama the batch context: session titles + touched files
     * @return list of (file, resolved) pairs — true means markers were removed
     */
    suspend fun resolveConflicts(
        repoDir: File,
        brain: BrainClient?,
        panorama: List<SessionPanorama>,
    ): List<Pair<String, Boolean>> {
        if (brain == null) {
            println("[QA-LAGUNA] no brain configured — conflict markers stay as committed")
            return emptyList()
        }

        val conflicts = conflictFiles(repoDir)
        if (conflicts.isEmpty()) return emptyList()

        println("[QA-LAGUNA] resolving ${conflicts.size} conflicted files with ${panorama.size} sessions in panorama")

        val results = mutableListOf<Pair<String, Boolean>>()
        for (file in conflicts) {
            val resolved = resolveOne(repoDir, brain, file, panorama)
            results.add(file to resolved)
        }

        val resolvedCount = results.count { it.second }
        val stuckCount = results.count { !it.second }
        println("[QA-LAGUNA] resolved=$resolvedCount stuck=$stuckCount")
        return results
    }

    private suspend fun resolveOne(
        repoDir: File,
        brain: BrainClient,
        file: String,
        panorama: List<SessionPanorama>,
    ): Boolean {
        val f = File(repoDir, file)
        if (!f.exists()) return false
        val content = f.readText()
        if (!content.contains("<<<<<<<")) return false

        // n=2+ distance: read the includes/imports to gather merge facts.
        // These tell us what symbols each side depends on.
        val imports = content.lines()
            .filter { it.startsWith("import ") || it.startsWith("package ") }
            .joinToString("\n")

        // Follow import links: read referenced files for additional scope.
        val additionalScope = readReferencedFiles(repoDir, file, imports)

        val panoramaText = panorama.joinToString("\n") { p ->
            "session ${p.sessionId.takeLast(6)}: ${p.title.take(60)} [files: ${p.touchedFiles.joinToString(", ")}]"
        }

        val prompt = buildString {
            appendLine("You are QA resolving a git conflict from an octopus merge of ${panorama.size} Jules sessions.")
            appendLine()
            appendLine("Panorama — the sessions in this batch:")
            appendLine(panoramaText)
            appendLine()
            appendLine("File: $file")
            appendLine("Imports (merge facts from includes):")
            appendLine(imports)
            appendLine()
            if (additionalScope.isNotEmpty()) {
                appendLine("Additional scope from referenced files:")
                appendLine(additionalScope.take(2000))
                appendLine()
            }
            appendLine("Conflicted file content:")
            appendLine(content.take(4000))
            appendLine()
            appendLine("Resolve ALL conflict markers (<<<<<<<, =======, >>>>>>>).")
            appendLine("Read both sides and the imports to understand what each side depends on.")
            appendLine("Output the COMPLETE resolved file.")
        }.trim()

        return try {
            val resolved = withTimeoutOrNull(90_000L) {
                brain.chat(messages = listOf("user" to prompt), maxTokens = 4000, temperature = 0.1)
            }
            if (resolved != null && resolved.isNotEmpty() && !resolved.contains("<<<<<<<")) {
                f.writeText(resolved)
                println("[QA-LAGUNA] resolved $file (${resolved.length} chars)")
                true
            } else {
                println("[QA-LAGUNA] could not resolve $file — markers stay")
                false
            }
        } catch (t: Throwable) {
            println("[QA-LAGUNA] error on $file: ${t.message}")
            false
        }
    }

    /** Files with unresolved conflict markers. */
    private fun conflictFiles(repoDir: File): List<String> {
        val p = ProcessBuilder("git", "diff", "--name-only", "--diff-filter=U")
            .directory(repoDir)
            .redirectErrorStream(true)
            .start()
        p.waitFor()
        return p.inputStream.bufferedReader().readText().trim().lines()
            .filter { it.isNotBlank() }
    }

    /**
     * Follow import links to read referenced files for additional scope.
     * n=2+ distance: the merge facts come from reading the dependency graph.
     */
    private fun readReferencedFiles(repoDir: File, confliictFile: String, imports: String): String {
        val srcRoot = File(repoDir, "src/commonMain/kotlin")
        val referenced = mutableListOf<String>()
        for (line in imports.lines()) {
            if (!line.startsWith("import ")) continue
            val fqn = line.removePrefix("import ").trim().removeSuffix(".*")
            // Convert package.Class to package/Class.kt
            val relPath = fqn.replace(".", "/") + ".kt"
            val f = File(srcRoot, relPath)
            if (f.exists() && f.absolutePath != File(repoDir, confliictFile).absolutePath) {
                referenced.add("=== ${f.relativeTo(repoDir)} ===\n${f.readText().take(500)}")
            }
        }
        return referenced.joinToString("\n\n")
    }

    /** One session's panorama context. */
    data class SessionPanorama(
        val sessionId: String,
        val title: String,
        val touchedFiles: List<String>,
    )
}
