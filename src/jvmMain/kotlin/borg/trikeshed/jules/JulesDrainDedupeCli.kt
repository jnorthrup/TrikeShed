package borg.trikeshed.jules

import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

object JulesDrainDedupeCli {
    data class DedupeResult(
        val sessionId: String,
        val provenances: Set<String>
    )

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        require(args.isNotEmpty()) {
            "usage: JulesDrainDedupeCli <repoDir> [forgeDir]"
        }
        val repoDir = File(args[0])
        val forgeDir = File(args.getOrNull(1) ?: defaultForgeDir())
        requireCanonicalRepository(repoDir)

        val store = JulesBoardStore.forForgeDir(forgeDir)
        val walSessions = withContext(Dispatchers.IO) {
            store.load().keys
        }

        val provenances = mutableMapOf<String, MutableSet<String>>()

        // 1. Add WAL provenances
        for (sid in walSessions) {
            provenances.getOrPut(sid) { mutableSetOf() }.add("wal")
        }

        // 2. Query git for branches and tags
        val gitRefs = git(repoDir, "ls-remote", "origin")
        for (line in gitRefs) {
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 2) continue
            val ref = parts[1]

            // refs/heads/jules-<sid>-*
            // refs/tags/flywheel/jules-<sid>-*
            val match = Regex(".*/jules-([a-f0-9]+)-.*").find(ref)
            if (match != null) {
                val sid = match.groupValues[1]
                if (ref.startsWith("refs/pull/")) {
                    provenances.getOrPut(sid) { mutableSetOf() }.add("merged-pr-head")
                } else if (ref.startsWith("refs/tags/")) {
                    provenances.getOrPut(sid) { mutableSetOf() }.add("tag")
                } else {
                    provenances.getOrPut(sid) { mutableSetOf() }.add("branch")
                }
            }
        }

        val results = provenances.map { DedupeResult(it.key, it.value) }
        val json = JsonSupport.stringify(results)
        println(json)
    }

    private fun defaultForgeDir(): String =
        System.getenv("TRIKESHED_HOME") ?: File(System.getProperty("user.home"), ".local/forge").path

    private suspend fun git(repoDir: File, vararg args: String): List<String> = withContext(Dispatchers.IO) {
        val allowlist = setOf("ls-remote", "branch")
        require(args[0] in allowlist) { "git command not in allowlist: ${args[0]}" }
        val pb = ProcessBuilder("git", *args)
        pb.directory(repoDir)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readLines()
        val exit = proc.waitFor()
        if (exit != 0) {
            System.err.println("git ${args.joinToString(" ")} failed with exit $exit")
        }
        out
    }

    private fun requireCanonicalRepository(dir: File) {
        require(dir.exists() && dir.isDirectory) { "repoDir $dir is not a directory" }
        require(File(dir, ".git").exists()) { "repoDir $dir lacks .git/" }
    }
}
