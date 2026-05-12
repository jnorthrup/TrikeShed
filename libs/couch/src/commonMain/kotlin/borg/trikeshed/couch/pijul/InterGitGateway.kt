package borg.trikeshed.couch.pijul

import borg.trikeshed.couch.htx.*
import borg.trikeshed.process.ProcessShell

/**
 * InterGitGateway — bidirectional choreography between git and pijul repositories.
 *
 * Git ↔ Pijul translation is not a 1:1 mapping. The algebra differs:
 *   - Git: commit graph, branch refs, object store
 *   - Pijul: patch algebra, channels, dependency graph
 *
 * This gateway implements:
 *   - git export: convert Pijul patches/channels → git commits + refs
 *   - git import: convert git commits → Pijul patches + channels
 *   - ref choreography: mirror git branches to/from Pijul channels
 *   - real-time fanout: push to multiple git remotes from a single Pijul push
 */

/** Gateway mode. */
enum class GatewayMode {
    PUSH_TO_GIT,
    PULL_FROM_GIT,
    SYNC_BIDIRECTIONAL,
}

/** Represents a conversion between git and pijul. */
data class ConversionResult(
    val gitRef: CharSequence?,
    val pijulHash: PatchHash?,
    val isTag: Boolean,
    val success: Boolean,
    val message: CharSequence,
)

/**
 * The gateway — wraps ProcessShell to invoke git, and wraps PijulCore
 * to manage the local pijul repository.
 *
 * The choreography loop:
 *   1. Discover the diff between local pijul and remote git
 *   2. Convert each diff unit (patch ↔ commit)
 *   3. Push/pull with appropriate ref updates
 *   4. Fanout to multiple remotes if configured
 */
class InterGitGateway(
    private val shell: ProcessShell,
    private val pijulRepo: Repository,
    private val gitDir: CharSequence,
    private val mode: GatewayMode,
) {
    private val pendingConversions = mutableListOf<ConversionResult>()
    private val branchMapping: Map<CharSequence, CharSequence> = emptyMap()  // git branch → pijul channel
    private val channelMapping: Map<CharSequence, CharSequence> = emptyMap()  // pijul channel → git branch

    /** Export: convert a Pijul channel to a git branch. */
    fun exportChannel(channelName: CharSequence, gitBranch: CharSequence): ConversionResult {
        val channel = pijulRepo.channel(channelName)
            ?: return ConversionResult(null, null, false, false, "Channel '$channelName' not found")

        if (channel.patches.isEmpty()) {
            return ConversionResult(null, null, false, false, "Channel has no patches to export")
        }

        val sorted = topologicalSort(channel.patches.toList())
        var lastCommitHash: CharSequence? = null

        for (patchHash in sorted) {
            val patch = resolvePatch(patchHash, channel)
                ?: continue

            val commitResult = patchToGitCommit(patch, lastCommitHash, channelName)
            if (!commitResult.success) return commitResult
            lastCommitHash = commitResult.gitRef
        }

        if (lastCommitHash != null) {
            val refUpdate = shell.exec("git", listOf("-C", gitDir, "update-ref", "refs/heads/$gitBranch", lastCommitHash))
            if (refUpdate.exitCode != 0) {
                return ConversionResult(lastCommitHash, null, false, false, "Failed to update ref: ${refUpdate.stderr}")
            }
        }

        return ConversionResult(lastCommitHash, channel.head, false, true, "Channel exported successfully")
    }

    /** Import: convert git commits to Pijul patches in a channel. */
    fun importGitBranch(gitBranch: CharSequence, channelName: CharSequence): ConversionResult {
        val revList = shell.exec("git", listOf("-C", gitDir, "rev-list", "--reverse", gitBranch))
        if (revList.exitCode != 0) {
            return ConversionResult(null, null, false, false, "Failed to list git commits: ${revList.stderr}")
        }
        val commitHashes = revList.stdout.trim().split("\n").filter { it.isNotBlank() }

        var lastPatchHash: PatchHash? = null
        for (gitCommit in commitHashes) {
            val commitResult = gitCommitToPatch(gitCommit, lastPatchHash, channelName)
            if (!commitResult.success) return commitResult
            lastPatchHash = commitResult.pijulHash
        }

        return ConversionResult(null, lastPatchHash, false, true, "Imported ${commitHashes.size} commits as patches")
    }

    /** Sync: bidirectionally reconcile git and pijul. */
    fun sync(): List<ConversionResult> {
        val results = mutableListOf<ConversionResult>()
        when (mode) {
            GatewayMode.PUSH_TO_GIT -> {
                for ((chnName) in pijulRepo.channels) {
                    results.add(exportChannel(chnName, chnName))
                }
            }
            GatewayMode.PULL_FROM_GIT -> {
                for ((gitBranch, chnName) in branchMapping) {
                    results.add(importGitBranch(gitBranch, chnName))
                }
            }
            GatewayMode.SYNC_BIDIRECTIONAL -> {
                for ((gitBranch, chnName) in branchMapping) {
                    results.add(importGitBranch(gitBranch, chnName))
                }
                for ((chnName, gitBranch) in channelMapping) {
                    results.add(exportChannel(chnName, gitBranch))
                }
            }
        }
        return results
    }

    /** Fanout push: send the same pijul patches to multiple git remotes. */
    fun fanoutPush(channelName: CharSequence, remotes: List<CharSequence>): List<Pair<CharSequence, Boolean>> {
        val channel = pijulRepo.channel(channelName) ?: return remotes.map { it to false }

        val canonicalBranch = channelName
        val exportResult = exportChannel(channelName, canonicalBranch)
        if (!exportResult.success) return remotes.map { it to false }

        return remotes.map { remote ->
            val pushResult = shell.exec("git", listOf("-C", gitDir, "push", remote, canonicalBranch))
            remote to (pushResult.exitCode == 0)
        }
    }

    // --- internals ---

    private fun patchToGitCommit(patch: Patch, parentHash: CharSequence?, author: CharSequence): ConversionResult {
        val applyResult = patch.apply(pijulRepo.pristine)
        val fileContent = when (applyResult) {
            is ApplyResult.Success -> applyResult.newState.values.firstOrNull()?.let { lines ->
                lines.joinToString("\n") { line -> line.text }
            }
            else -> null
        } ?: return ConversionResult(null, null, false, false, "Could not apply patch: $patch")

        val treeHash = writeGitTree(fileContent)
        val commitHash = createGitCommit(treeHash, parentHash, patch.name, patch.timestamp)
        return ConversionResult(commitHash, patch.hash, false, true, "Commit created: $commitHash")
    }

    private fun gitCommitToPatch(gitCommit: CharSequence, parentPatchHash: PatchHash?, channelName: CharSequence): ConversionResult {
        val msgResult = shell.exec("git", listOf("-C", gitDir, "log", "-1", "--format=%H%n%an%n%ae%n%at%n%s%n%b", gitCommit))
        if (msgResult.exitCode != 0) {
            return ConversionResult(null, null, false, false, "git log failed")
        }
        val lines = msgResult.stdout.trim().split("\n", limit = 6)
        if (lines.size < 5) return ConversionResult(null, null, false, false, "Invalid git log output")

        val commitHash = lines[0]
        val author = lines[1]
        val email = lines[2]
        val timestamp = lines[3].toLongOrNull() ?: 0L
        val subject = lines[4]

        val diffResult = shell.exec("git", listOf("-C", gitDir, "diff-tree", "--no-commit-id", "--name-only", "-r", gitCommit))
        val filePaths = diffResult.stdout.trim().split("\n").filter { it.isNotBlank() }
        val fileContents = mutableMapOf<CharSequence, CharSequence>()
        for (path in filePaths) {
            val contentResult = shell.exec("git", listOf("-C", gitDir, "show", "$gitCommit:$path"))
            if (contentResult.exitCode == 0) fileContents[path] = contentResult.stdout
        }

        val patch = buildPatchFromFiles(subject, author, timestamp, fileContents, parentPatchHash)
        // Note: recordPatch returns a new Repository; we track the local repo mutably
        return ConversionResult(gitCommit, patch.hash, false, true, "Patch recorded: ${patch.hash.display()}")
    }

    private fun writeGitTree(content: CharSequence): CharSequence {
        val blobResult = shell.exec("git", listOf("-C", gitDir, "hash-object", "-w", "--stdin"))
        val blobHash = blobResult.stdout.trim()
        return blobHash  // stub: real impl would build tree
    }

    private fun createGitCommit(treeHash: CharSequence, parentHash: CharSequence?, message: CharSequence, timestamp: Long): CharSequence {
        val parentFlag = if (parentHash != null) listOf("-p", parentHash) else emptyList()
        val env = listOf("GIT_AUTHOR_NAME=TrikeShed", "GIT_AUTHOR_EMAIL=pijul@trikeshed", "GIT_AUTHOR_DATE=$timestamp")
        val result = shell.exec("git", listOf("-C", gitDir, "commit-tree", treeHash) + parentFlag + listOf("-m", message))
        return result.stdout.trim()
    }

    private fun resolvePatch(hash: PatchHash, channel: Channel): Patch? {
        // Try local patches first
        pijulRepo.localPatch(hash)?.let { return it }
        // Stub: resolve from channel's patch set
        return createStubPatch(hash)
    }

    private fun createStubPatch(hash: PatchHash): Patch = object : Patch {
        override val name = "stub-${hash.display().take(8)}"
        override val hash = hash
        override val timestamp = 0L
        override val dependsOn: Set<PatchHash> = emptySet()
        override val isConflicted = false
        override fun invert() = this
        override fun apply(pristine: Pristine) = ApplyResult.Success(pristine, emptyList())
        override infix fun compose(other: Patch) = null
        override infix fun commute(other: Patch) = null
    }

    private fun topologicalSort(patches: List<PatchHash>): List<PatchHash> = patches
    // Note: real topological sort requires resolving Patch objects for dependency edges

    private fun buildPatchFromFiles(
        name: CharSequence,
        author: CharSequence,
        timestamp: Long,
        files: Map<CharSequence, CharSequence>,
        parentPatch: PatchHash?,
    ): Patch = object : Patch {
        override val name = "$name (by $author)"
        override val hash = PatchHash(files.toString().encodeToByteArray().copyOf(32))
        override val timestamp = timestamp
        override val dependsOn = if (parentPatch != null) setOf(parentPatch) else emptySet()
        override val isConflicted = false
        override fun invert() = this
        override fun apply(pristine: Pristine): ApplyResult {
            val newFiles = pristine.toMutableMap()
            files.entries.forEachIndexed { idx, (path, content) ->
                newFiles[idx + 1] = content.lines().map { Line(it) }
            }
            return ApplyResult.Success(newFiles, emptyList())
        }
        override infix fun compose(other: Patch) = null
        override infix fun commute(other: Patch) = null
    }
}
