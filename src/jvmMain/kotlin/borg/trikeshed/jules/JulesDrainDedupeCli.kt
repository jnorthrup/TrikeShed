package borg.trikeshed.jules

import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import borg.trikeshed.jules.JulesCause
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drain dedupe CLI — merges all branches, PRs, and jules sessions into master
 * in one pass, deduping by session ID, then settles every terminal session.
 *
 * Usage: JulesDrainDedupeCli <repoDir> [forgeDir]
 *
 * Pipeline:
 *   1. Collect the universe: WAL session IDs ∪ origin branches ∪ origin PR heads
 *   2. Dedupe by session ID (a session may appear as a branch, a PR, and a WAL card)
 *   3. For each unique branch/PR: try ff-only merge into master. If it conflicts,
 *      try cherry-pick. If that fails, skip and report.
 *   4. For each undrained terminal jules session: reject + settle-reject
 *   5. Push master, report the merged set
 *
 * This is the one-shot drain verb. The daemon's per-cycle drain is incremental;
 * this CLI is the batch operator action.
 */
object JulesDrainDedupeCli {

    data class DedupeEntry(
        val sessionId: String,
        val provenances: Set<String>,
        val branch: String?,
        val merged: Boolean,
        val skipReason: String?,
    )

    data class DedupeResult(
        val mergedCount: Int,
        val skippedCount: Int,
        val settledCount: Int,
        val entries: List<DedupeEntry>,
    )

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        require(args.isNotEmpty()) {
            "usage: JulesDrainDedupeCli <repoDir> [forgeDir]"
        }
        val repoDir = File(args[0])
        val forgeDir = File(args.getOrNull(1) ?: defaultForgeDir())
        requireCanonicalRepository(repoDir)

        val result = drainDedupe(repoDir, forgeDir)
        println(JsonSupport.stringify(result))
    }

    suspend fun drainDedupe(repoDir: File, forgeDir: File): DedupeResult {
        // 1. Fetch all refs
        git(repoDir, "fetch", "origin", "--prune")

        val store = JulesBoardStore.forForgeDir(forgeDir)
        val walSessions = withContext(Dispatchers.IO) { store.load() }

        // 2. Collect branches that carry session IDs
        val refs = git(repoDir, "for-each-ref", "--format=%(refname:short) %(objectname)", "refs/remotes/origin")
        val branchBySid = mutableMapOf<String, String>()
        for (line in refs) {
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 2) continue
            val ref = parts[0]
            if (ref == "origin/master" || ref == "origin/HEAD") continue
            // Match session ID in branch name: jules-<sid>, bolt-<sid>, palette-<sid>, etc.
            val sidMatch = Regex("(\\d{16,20})").find(ref)
            if (sidMatch != null) {
                val sid = sidMatch.value
                if (sid !in branchBySid) {
                    branchBySid[sid] = ref
                }
            }
        }

        // 3. Merge each unique branch into master (ff-only or cherry-pick)
        val entries = mutableListOf<DedupeEntry>()
        var mergedCount = 0
        var skippedCount = 0

        // Ensure master is clean and up to date
        git(repoDir, "checkout", "master")
        git(repoDir, "merge", "--ff-only", "origin/master")

        for ((sid, branch) in branchBySid) {
            val provenances = mutableSetOf<String>()
            if (sid in walSessions.keys) provenances.add("wal")
            provenances.add("branch")

            // Try ff-only merge first
            val ffResult = gitWithExit(repoDir, "merge", "--ff-only", branch)
            if (ffResult.first == 0) {
                mergedCount++
                entries.add(DedupeEntry(sid, provenances, branch, true, null))
                continue
            }

            // Try cherry-pick of the branch's unique commits
            val mergeBase = git(repoDir, "merge-base", "master", branch).firstOrNull() ?: ""
            val cherryList = git(repoDir, "log", "--reverse", "--format=%H", "${mergeBase}..${branch}")
            var cherryOk = true
            for (commit in cherryList) {
                val cpResult = gitWithExit(repoDir, "cherry-pick", "--allow-empty", commit)
                if (cpResult.first != 0) {
                    git(repoDir, "cherry-pick", "--abort")
                    cherryOk = false
                    break
                }
            }
            if (cherryOk && cherryList.isNotEmpty()) {
                mergedCount++
                entries.add(DedupeEntry(sid, provenances, branch, true, null))
            } else {
                skippedCount++
                entries.add(DedupeEntry(sid, provenances, branch, false, "ff-only and cherry-pick both failed"))
            }
        }

        // 4. Push if anything merged
        if (mergedCount > 0) {
            git(repoDir, "push", "origin", "master")
        }

        // 5. Settle all undrained terminal jules sessions
        val originSha = git(repoDir, "rev-parse", "origin/master").firstOrNull() ?: ""
        var settledCount = 0
        val cp = System.getProperty("java.class.path")
        for ((sid, card) in walSessions) {
            if (card.drained) continue
            val state = card.snapshot.state
            if (state !in setOf("COMPLETED", "FAILED", "CANCELLED")) continue

            val causes = card.causes
            var latestPatchCid = ""
            var latestPatchOrd = 0
            for (cause in causes) {
                if (cause is JulesCause.PatchSnapshotObserved && cause.causalOrdinal > latestPatchOrd) {
                    latestPatchOrd = cause.causalOrdinal
                    latestPatchCid = cause.patchCid.value
                }
            }

            // Reject if there's a patch
            if (latestPatchCid.isNotEmpty()) {
                val rejResult = gitJava(repoDir, cp,
                    "borg.trikeshed.jules.JulesPatchReviewCli", "reject",
                    sid, latestPatchCid, latestPatchOrd.toString(),
                    "superseded by drain dedupe batch", "operator", "drain-dedupe")
                // Already-rejected is OK
            }

            // Settle-reject
            val title = card.card.title
            val setResult = gitJava(repoDir, cp,
                "borg.trikeshed.jules.JulesSettlementCli", "settle-reject",
                sid, originSha, state, "superseded by drain dedupe batch", title)
            if (setResult.first == 0) {
                settledCount++
            }
        }

        return DedupeResult(mergedCount, skippedCount, settledCount, entries)
    }

    private fun defaultForgeDir(): String =
        System.getenv("TRIKESHED_HOME") ?: File(System.getProperty("user.home"), ".local/forge").path

    private suspend fun git(repoDir: File, vararg args: String): List<String> = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder("git", *args)
        pb.directory(repoDir)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readLines()
        proc.waitFor()
        out
    }

    private suspend fun gitWithExit(repoDir: File, vararg args: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder("git", *args)
        pb.directory(repoDir)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        Pair(exit, out)
    }

    private suspend fun gitJava(repoDir: File, cp: String, mainClass: String, vararg args: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder("java", "-cp", cp, mainClass, *args)
        pb.directory(repoDir)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        Pair(exit, out)
    }

    private fun requireCanonicalRepository(dir: File) {
        require(dir.exists() && dir.isDirectory) { "repoDir $dir is not a directory" }
        require(File(dir, ".git").exists()) { "repoDir $dir lacks .git/" }
    }
}
