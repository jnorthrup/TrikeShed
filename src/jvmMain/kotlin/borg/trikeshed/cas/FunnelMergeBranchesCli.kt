package borg.trikeshed.cas

import borg.trikeshed.pijul.GitPijulGateway
import borg.trikeshed.userspace.containment.EntropyPathScanner
import java.io.File

/**
 * N-way Pijul merge of every unmerged origin branch — the one-shot CLI from
 * d5061224b (flywheel/cli/FunnelMergeBranchesCli, deleted with the flywheel in
 * 4790932e5) brought back on the [GitPijulGateway], branch arms only.
 *
 * What stayed from the original: arms = unmerged `origin/<branch>` branches, one
 * channel seeded from master, the isolated `git worktree` preflight, the
 * conflict-marker scan, the `./gradlew jvmMainClasses` gate, one commit,
 * `merge --ff-only` into master, push. What went: the WAL/CAS arms, the
 * per-session tags and WorkDrained receipts (the flywheel's), and the
 * CrossInstanceCollusionDetector (never re-added — overlapping files are the
 * CRDT's job). What is new: the gate does the coordinate conversion and
 * content-addressing, and same-locus divergence is POSTED and must be
 * resolved by a table before anything lands.
 *
 * Usage:
 *   FunnelMergeBranchesCli <repoDir> --report [--since YYYY-MM-DD]
 *   FunnelMergeBranchesCli <repoDir> --resolve <table> [--since …] [--subject "…"] [--push]
 *
 * `--report` never writes to the repo. `--resolve` lands only when every
 * posted locus has a line in the table and the build gate is green.
 */
fun main(args: Array<String>) {
    val positional = args.filter { !it.startsWith("--") }
    val repoDir = File(positional.firstOrNull() ?: ".")
    require(repoDir.isDirectory && File(repoDir, ".git").exists()) { "usage: FunnelMergeBranchesCli <repoDir> …: $repoDir is not a git repo" }
    fun opt(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val since = opt("--since") ?: "2026-08-01"
    val table = opt("--resolve")?.let { File(it) }
    val report = args.contains("--report") || table == null
    val push = args.contains("--push")
    val subject = opt("--subject")
    val exit = FunnelMergeBranchesCli(repoDir, since).run(report, table, subject, push)
    if (exit != 0) kotlin.system.exitProcess(exit)
}

class FunnelMergeBranchesCli(private val repoDir: File, private val since: String) {

    companion object { const val MASTER = "master" }

    private data class ArmSource(val ref: String, val date: String, val dropped: List<String>)

    fun run(report: Boolean, table: File?, subject: String?, push: Boolean): Int {
        git("fetch", "origin", "--prune")
        val baseSha = git("rev-parse", "HEAD").second.trim()
        val branch = git("rev-parse", "--abbrev-ref", "HEAD").second.trim()
        require(branch == "master") { "not on master (on $branch)" }
        val dirty = git("status", "--porcelain").second.lineSequence().filter { it.length > 3 }.map { it.substring(3).trim().substringAfter(" -> ") }.toSet()

        // ── arms ──────────────────────────────────────────────────────────
        val refs = git("for-each-ref", "--sort=committerdate", "--format=%(committerdate:short) %(refname:short)", "refs/remotes/origin").second
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .map { it.substringBefore(' ') to it.substringAfter(' ') }
            .filter { (d, r) -> d >= since && r != "origin/master" && r != "origin/HEAD" && r != "origin" }
            .toList()
        val arms = ArrayList<GitPijulGateway.Arm>()
        val sources = LinkedHashMap<String, ArmSource>()
        var skippedMerged = 0
        val live = refs.filter { (_, ref) -> git("rev-list", "--count", "master..$ref").second.trim() != "0" }
        skippedMerged = refs.size - live.size
        if (live.isEmpty()) { println("[FUNNEL-MERGE] since=$since refs=${refs.size} — every branch is already in master"); return 0 }
        // The Pijul rule: every arm is a patch against ONE base. The base is the common
        // ancestor of master and every live branch; master's own changes since then are
        // an arm too, so a bot hunk that master has since rewritten collides with master's
        // and is resolved (master wins by default) instead of landing on stale coordinates.
        val base = git("merge-base", "--octopus", "master", *live.map { it.second }.toTypedArray()).second.trim()
        require(base.length >= 40) { "no common base for master and ${live.size} branches" }
        for ((date, ref) in live) {
            val diff = git("diff", "--no-color", base, ref)
            if (diff.first != 0 || diff.second.isBlank()) continue
            val all = GitPijulGateway.hunksOf(diff.second)
            val dropped = all.map { it.path }.distinct().filterNot(GitPijulGateway::isProductionPath)
            val hunks = all.filter { GitPijulGateway.isProductionPath(it.path) }
            val label = ref.removePrefix("origin/")
            // Steganographic path entropy is a NEW-path threat; an edit to a file master
            // already has (CouchDatabase.kt scores 3.6 bits on its own name) is not one.
            val newPaths = hunks.map { it.path }.distinct().filter { !File(repoDir, it).exists() }
            val quarantined = EntropyPathScanner.scanTouchedPaths(newPaths).map { it.path }.toSet()
            if (quarantined.isNotEmpty()) println("[FUNNEL-MERGE] QUARANTINE $label: new paths with entropy > 3.5 dropped: $quarantined")
            // Resurrection guard (Jim's rule: kept merges must not bring owner-deleted files back).
            // A path the branch presents as NEW relative to the base, absent from master today,
            // that master's history has deleted, is a deletion being undone — refused.
            val resurrected = newPaths.filter { it !in quarantined }.filter { path ->
                git("cat-file", "-e", "$base:$path").first != 0 &&
                    git("log", "--diff-filter=D", "-1", "--format=%h", "master", "--", path).second.isNotBlank()
            }.toSet()
            if (resurrected.isNotEmpty()) println("[FUNNEL-MERGE] RESURRECTION refused for $label: $resurrected")
            val kept = hunks.filter { it.path !in quarantined && it.path !in resurrected }
            sources[label] = ArmSource(ref, date, dropped + quarantined + resurrected)
            if (kept.isEmpty()) { println("[FUNNEL-MERGE] $label: only scratch/quarantined paths — nothing to merge"); continue }
            arms.add(GitPijulGateway.Arm(label, kept))
        }
        val branchTouched = arms.flatMap { a -> a.hunks.map { it.path } }.distinct().sorted()
        if (branchTouched.isNotEmpty()) {
            val md = git("diff", "--no-color", base, "master", "--", *branchTouched.toTypedArray())
            val masterHunks = GitPijulGateway.hunksOf(md.second)
            if (masterHunks.isNotEmpty()) arms.add(0, GitPijulGateway.Arm(MASTER, masterHunks))
        }
        println("[FUNNEL-MERGE] since=$since refs=${refs.size} alreadyMerged=$skippedMerged arms=${arms.size} (incl. master) head=${baseSha.take(12)} base=${base.take(12)}")

        // ── plan ──────────────────────────────────────────────────────────
        val explicit = table?.let { GitPijulGateway.parseResolutions(it.readText()) } ?: emptyMap()
        val first = GitPijulGateway.plan(arms, explicit)
        val defaults = first.unresolved.filter { g -> g.variants.any { MASTER in it.arms } }
            .associate { it.locus to (GitPijulGateway.Resolution.Accept(MASTER) as GitPijulGateway.Resolution) }
        val resolutions = defaults + explicit
        val plan = GitPijulGateway.plan(arms, resolutions)
        if (defaults.isNotEmpty()) println("[FUNNEL-MERGE] ${defaults.size} loci where master already rewrote the lines → master wins (superseded): ${defaults.keys.joinToString(" ")}")
        printReport(plan, sources)
        if (report) return 0
        if (plan.unresolved.isNotEmpty()) {
            System.err.println("[FUNNEL-MERGE] ${plan.unresolved.size} posted loci without a resolution — nothing lands")
            return 2
        }
        val unknown = resolutions.keys - plan.groups.map { it.locus }.toSet()
        if (unknown.isNotEmpty()) { System.err.println("[FUNNEL-MERGE] resolutions name loci that do not exist: $unknown"); return 2 }

        // ── render into an isolated worktree ─────────────────────────────
        val tempRoot = java.nio.file.Files.createTempDirectory("funnel-merge-").toFile()
        val worktree = File(tempRoot, "worktree")
        try {
            if (git("worktree", "add", "--detach", worktree.absolutePath, baseSha).first != 0) { System.err.println("[FUNNEL-MERGE] worktree add failed"); return 3 }
            val touched = plan.survivors.keys.sorted()
            for (path in touched) {
                val target = File(worktree, path)
                val shown = git("show", "$base:$path")
                val baseText = if (shown.first == 0) shown.second else ""
                val rendered = GitPijulGateway.render(baseText, plan.survivors.getValue(path))
                if (rendered.isEmpty() && target.isFile) target.delete()
                else { target.parentFile?.mkdirs(); target.writeText(rendered) }
            }
            gitIn(worktree, "add", "-A", "--", *touched.toTypedArray())
            val staged = gitIn(worktree, "diff", "--cached", "--unified=0").second
            if (staged.lineSequence().any { it.startsWith("+<<<<<<< ") || it == "+=======" || it.startsWith("+>>>>>>> ") }) {
                System.err.println("[FUNNEL-MERGE] rendered result contains conflict markers — aborting"); return 3
            }
            val names = gitIn(worktree, "diff", "--cached", "--name-only").second.trim()
            if (names.isBlank()) { println("[FUNNEL-MERGE] no delta vs master — everything already present"); return 0 }
            val stagedPaths = names.lines().map { it.trim() }.filter { it.isNotEmpty() }
            // Render-drift gate: a path may differ from master ONLY if a branch hunk survived on it.
            // Every other path is master reproduced through the CRDT from the base and must be byte-identical.
            val branchPaths = plan.survivors.filter { (_, hs) -> hs.any { h -> plan.groups.any { g -> g.path == h.path && g.variants.any { v -> v.hunk.id == h.id && v.arms.any { it != MASTER } } } } }.keys
            val drift = stagedPaths.filter { it !in branchPaths }
            if (drift.isNotEmpty()) { System.err.println("[FUNNEL-MERGE] RENDER DRIFT — master-only files came out different, aborting: $drift"); return 3 }
            val overlap = stagedPaths.filter { it in dirty }
            if (overlap.isNotEmpty()) { System.err.println("[FUNNEL-MERGE] working tree has uncommitted edits in paths this merge changes: $overlap — commit or park them first"); return 3 }
            println("[FUNNEL-MERGE] staged (${stagedPaths.size}):\n" + stagedPaths.joinToString("\n") { "  $it" })

            // ── build gate ────────────────────────────────────────────────
            val gate = gradle(worktree, "jvmMainClasses")
            if (gate.first != 0) { System.err.println("[FUNNEL-MERGE] BUILD RED — nothing landed:\n" + gate.second.takeLast(4000)); return 4 }
            println("[FUNNEL-MERGE] build gate green")

            // ── commit, fast-forward, push ────────────────────────────────
            val msg = buildString {
                append(subject ?: "funnel ${arms.size}-way merge — ${plan.groups.count { it.converged }} loci converged, ${plan.groups.size - plan.groups.count { it.converged }} resolved")
                append("\n\nEvery unmerged origin branch since $since, absorbed through the Pijul gateway against\n")
                append("common base ${base.take(12)} with master's own changes since then as one more arm.\n")
                append("Through the\n")
                append("Pijul gateway (GitPijulGateway): hunks content-addressed, identical edits applied\n")
                append("once, same-locus divergence resolved by the table below, scratch paths refused.\n")
                append("\nArms (${arms.size}):\n")
                for (a in arms) append("  ${a.label}  (${sources[a.label]?.date ?: "head"}, ${a.hunks.size} hunks)\n")
                val nonProd = sources.filter { it.value.dropped.isNotEmpty() }
                if (nonProd.isNotEmpty()) {
                    append("\nRefused as scratch / gh-pages (never committed):\n")
                    for ((l, s) in nonProd) append("  $l: ${s.dropped.joinToString(", ")}\n")
                }
                if (resolutions.isNotEmpty()) {
                    append("\nResolutions:\n")
                    for ((locus, r) in resolutions) append("  $locus → " + when (r) { is GitPijulGateway.Resolution.Accept -> "accept ${r.arm}"; GitPijulGateway.Resolution.Reject -> "reject" } + "\n")
                }
                append("\nKnown gap left open: the three @Ignore commutativity laws in PijulCrdtPropertyTest;\nthe gateway applies survivors bottom-up per path so the render is a function of the set.\n")
            }
            val commit = gitIn(worktree, "commit", "--no-verify", "-q", "-m", msg)
            if (commit.first != 0) { System.err.println("[FUNNEL-MERGE] commit failed: ${commit.second.take(400)}"); return 5 }
            val revision = gitIn(worktree, "rev-parse", "HEAD").second.trim()
            if (git("rev-parse", "HEAD").second.trim() != baseSha) {
                System.err.println("[FUNNEL-MERGE] master moved during preflight — aborting"); return 6
            }
            val ff = git("merge", "--ff-only", revision)
            if (ff.first != 0) { System.err.println("[FUNNEL-MERGE] ff failed: ${ff.second.take(400)}"); return 6 }
            println("[FUNNEL-MERGE] LANDED $revision on master (${arms.size} arms, one commit)")
            if (push) {
                val p = git("push", "origin", "master")
                println(if (p.first == 0) "[FUNNEL-MERGE] pushed origin/master" else "[FUNNEL-MERGE] push failed: ${p.second.take(400)}")
                if (p.first != 0) return 7
            }
            return 0
        } finally {
            git("worktree", "remove", "--force", worktree.absolutePath)
            git("worktree", "prune")
            tempRoot.deleteRecursively()
        }
    }

    private fun printReport(plan: GitPijulGateway.Plan, sources: Map<String, ArmSource>) {
        val byPath = plan.groups.groupBy { it.path }
        for ((path, gs) in byPath) {
            val conv = gs.filter { it.converged }
            val posted = gs.filterNot { it.converged }
            if (posted.isEmpty()) {
                val arms = conv.flatMap { g -> g.variants.flatMap { it.arms } }.distinct()
                println("CONVERGED $path  hunks=${conv.size} arms=${arms.size} [${arms.joinToString(" ")}]")
            } else {
                if (conv.isNotEmpty()) println("CONVERGED $path  hunks=${conv.size} (outside the posted loci)")
                for (g in posted) {
                    println("POSTED ${g.locus}  variants=${g.variants.size}")
                    for (v in g.variants) {
                        println("   ${v.hunk.hex}  arms=[${v.arms.joinToString(" ")}]")
                        if (v.hunk.deleteCount > 0) println("      -L${v.hunk.deleteStart}" + (if (v.hunk.deleteCount > 1) "..${v.hunk.deleteStart + v.hunk.deleteCount - 1}" else "") + " (${v.hunk.deleteCount} lines)")
                        for (l in v.hunk.inserted.take(12)) println("      + $l")
                        if (v.hunk.inserted.size > 12) println("      + … ${v.hunk.inserted.size - 12} more")
                    }
                }
            }
        }
        val refused = sources.filter { it.value.dropped.isNotEmpty() }
        if (refused.isNotEmpty()) {
            println("REFUSED paths (scratch / gh-pages), never merged:")
            for ((l, s) in refused) println("   $l: ${s.dropped.joinToString(", ")}")
        }
        println("[FUNNEL-MERGE] groups=${plan.groups.size} converged=${plan.groups.count { it.converged }} posted=${plan.unresolved.size} rejected=${plan.rejected.size} filesToWrite=${plan.survivors.size}")
    }

    private fun git(vararg args: String): Pair<Int, String> = gitIn(repoDir, *args)

    private fun gitIn(dir: File, vararg args: String): Pair<Int, String> {
        val pb = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        return proc.waitFor() to out
    }

    private fun gradle(dir: File, task: String): Pair<Int, String> {
        val gradlew = File(dir, "gradlew")
        val pb = ProcessBuilder(if (gradlew.canExecute()) "./gradlew" else "gradle", task, "--console=plain", "-q")
            .directory(dir).redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        return proc.waitFor() to out
    }
}
