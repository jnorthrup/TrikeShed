package borg.trikeshed.util.oroboros

import borg.trikeshed.job.ContentId
import borg.trikeshed.pijul.FileChanges
import borg.trikeshed.pijul.PijulChannel

/**
 * The version gateway with no process behind it.
 *
 * [PijulVersionGateway] shells out to a `pijul` binary — `pijul --version`,
 * `pijul init`, `pijul add`, `pijul record`, `pijul log` — which makes the patch
 * algebra an external tool that may or may not be installed, and makes
 * `isAvailable()` a question about the operator's machine. That is the opposite
 * of an analog. `PijulCrdt`, `PijulChannel` and `PatchStorage` ARE the model, in
 * Kotlin, in this process; there is nothing to shell to.
 *
 * So this gateway answers the same three questions from the channel itself:
 *
 *  - `isAvailable()` is always true. The algebra is linked in. It cannot be
 *    missing, cannot be the wrong version, and does not vary per machine — which
 *    is the whole point of an analog over a dependency.
 *  - `init()` is a channel, and a channel is a map. There is no on-disk format to
 *    lay down, because the CAS is where bytes live and the channel is where
 *    change lives.
 *  - `record()` applies a patch and returns its id. The id is the revision:
 *    content-addressed, so recording the same change twice yields the same
 *    revision rather than two.
 *
 * The revision is a Blake3 patch id, not a commit sha, and that is a feature at
 * the boundary: two peers that made the same edit report the same revision
 * without having communicated.
 */
class ChannelVersionGateway(
    private val channel: PijulChannel = PijulChannel(),
) : VersionGateway {

    /** Staged file changes per home, awaiting the next [record]. */
    private val staged = mutableMapOf<String, MutableList<FileChanges>>()

    /** Linked in, not installed. */
    override suspend fun isAvailable(): Boolean = true

    override suspend fun init(home: String): Boolean {
        staged.getOrPut(home) { mutableListOf() }
        return true
    }

    /**
     * Stage a change for the next [record]. The git gateway gets this for free
     * from `git add .` walking the worktree; here the caller says what changed,
     * because the worktree is a render and re-deriving it would be asking the
     * projection what the source is.
     */
    fun stage(home: String, change: FileChanges) {
        staged.getOrPut(home) { mutableListOf() }.add(change)
    }

    override suspend fun record(home: String, author: String, message: String): String? {
        val changes = staged[home] ?: return null
        if (changes.isEmpty()) return null
        val cid = ContentId.of(message.encodeToByteArray())
        channel.applyPatch(
            workId = home,
            sessionId = author,
            patchCid = cid,
            title = message,
            changes = changes.toList(),
        )
        changes.clear()
        return cid.hex
    }
}
