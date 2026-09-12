package borg.trikeshed.lcnc

import borg.trikeshed.job.CasStore
import borg.trikeshed.common.File
import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.OroborosAttachmentRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * THE PUBLISHED PROGRAMS' DURABLE PLANE (AutoTools notion, Cut 0).
 *
 * A published program had two homes and both were per boot. Its `panels/<name>`
 * attachment lives in the couch head projection that `CouchStoreFactory.casBacked`
 * builds empty at every construction (the finding written down in
 * `NarsDurableLedgerTest`), and its board entry `lcnc/program/<name>` lives on a
 * blackboard the daemon starts empty. So `GET /api/panels/<name>` answered 404
 * after a restart, `LcncPublisher.load` returned null, and a run naming the
 * program by name answered `no_such_program` — while the program's BYTES sat on
 * disk the whole time, put into CAS by `CouchAttachmentGateway.putAttachment`
 * before the couch doc was ever written.
 *
 * The only missing datum was therefore the mapping name → head cid. This is that
 * mapping and nothing more: one JSON line per published version in
 * `<forgeHome>/programs/ledger.jsonl`, in the same five scalars
 * `lcnc/PromptStore.kt` writes — `{name, cid, previousCid, atMs, actor}`. The
 * ledger NAMES cids; it never re-stores bytes.
 *
 * [thaw] is what a restart re-reads: the last line per name wins, its bytes come
 * back from CAS by cid, the head is re-filed as its attachment, and then the one
 * LCNC writer republishes the whole corpus once ([LcncPublisher.publishAll]) — a
 * composite must be FILED before another program's cables are typed against it,
 * which is why the publish is one call after the loop and not one per name.
 *
 * THE BOARD REMAINS THE AUTHORITY (`LcncPublisher`'s doc comment). This is the
 * board's boot-time memory, never a second authority: a preset owns its name, so
 * a ledger line for a name that has since become a preset is skipped rather than
 * resurrected, and a line whose blob is gone from CAS — or present and no longer
 * hashing to its own id, which the CAS reports by THROWING, not by answering
 * null — is skipped rather than thrown. A thaw that dies would take more than
 * the LCNC surface with it: [thaw] runs sequentially in the daemon's `mainImpl`
 * BEFORE the HTTP server binds, so one rotted blob under a copied or
 * partially-synced `<forgeHome>/cas` would cost the whole daemon its boot. One
 * bad head therefore costs exactly one program, and the boot call site guards
 * the call as well ([LcncPublisher] work is done under the same discipline).
 */
class ProgramLedger(
    private val attachments: CouchAttachmentGateway?,
    private val cas: CasStore,
    private val ledger: File?,
    private val publisher: LcncPublisher?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    data class LedgerLine(val name: String, val cid: String, val previousCid: String?, val atMs: Long, val actor: String) {
        fun toMap(): Map<String, Any?> = linkedMapOf("name" to name, "cid" to cid, "previousCid" to previousCid, "atMs" to atMs, "actor" to actor)
    }

    private val lock = Mutex()
    private val heads = linkedMapOf<String, LedgerLine>()

    /**
     * Record one published version as the head of [name]. Appends the line, then
     * remembers it. A publish whose [cid] is already the head is a byte-identical
     * re-publish and appends nothing (`null`) — reloading a program and pressing
     * Publish must not grow the ledger; the head does not move either way.
     */
    suspend fun record(
        name: String,
        cid: String,
        previousCid: String?,
        actor: String,
        atMs: Long = clock(),
    ): LedgerLine? = lock.withLock {
        if (heads[name]?.cid == cid) return@withLock null
        val line = LedgerLine(name, cid, previousCid, atMs, actor)
        ledger?.let { file ->
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                file.appendText(JsonSupport.stringify(line.toMap()) + "\n")
            }
        }
        heads[name] = line
        line
    }

    /**
     * Boot: replay the ledger (the last line per name is its head), re-file each
     * head's attachment from its CAS bytes, then republish the corpus once.
     * Returns how many heads the ledger restored.
     *
     * IT DOES NOT THROW for a bad ledger, a bad line, a missing or rotted blob, a
     * store that refuses the re-file, or a corpus-wide republish that fails: each
     * of those costs its own head (or, for the republish, only the board's
     * boot-time seeding) and says so on stderr. Cancellation still propagates.
     *
     * CALL IT LATE. The republish is [LcncPublisher.publishAll], i.e. a
     * `lateBound()`, and lateBound resolves every contract against the runner
     * registry AS IT STANDS — so a boot that thaws before its node families are
     * registered publishes an honest board but an under-resolved vocabulary
     * (`LcncFacts.learn` replaces a type's binding rather than stacking a second
     * row, so the answer is corrected by the next resolution rather than
     * stranded, but the first answer is still worse than waiting). The daemon
     * calls this after its last `lcncRunners.putAll(...)` and immediately before
     * the module attach; a rig with no runners at all sees no difference.
     */
    suspend fun thaw(): Int {
        val last = linkedMapOf<String, LedgerLine>()
        for (line in readLedger()) last[line.name] = line
        var restored = 0
        for ((name, line) in last) {
            // TOTAL per-head guard, not one guard per known hazard. Every skip [restoreHead]
            // decides for itself is logged and returns false; this catches what it did NOT
            // foresee (a store that refuses a write, an IO fault under Dispatchers.IO) so
            // that the next head — and the boot — still happen. Cancellation is rethrown:
            // a cancelled boot must stop replaying the ledger, not log its way through it.
            val head = runCatching { restoreHead(name, line) }.getOrElse { why ->
                if (why is CancellationException) throw why
                System.err.println("[OROBOROS] programs: '$name' head ${line.cid.take(19)} could not be restored (${why.message}); it is not restored")
                false
            }
            if (head) restored++
        }
        // ONE publish for the whole corpus, after every head is filed: a program
        // used as a composite by another program's cables must exist in
        // `storedCorpus()` before anything is typed against it.
        //
        // Guarded like `KanbanModule`'s own publishAll(): this reads EVERY `panels/`
        // attachment, including ones no ledger line names, so a fault that has nothing
        // to do with the ledger can surface here. The heads are already re-filed when
        // it fires, so `LcncPublisher.load` still resolves them from their attachments
        // and only the board's boot-time seeding is lost.
        if (restored > 0) {
            runCatching { publisher?.publishAll() }.onFailure { why ->
                if (why is CancellationException) throw why
                System.err.println("[OROBOROS] programs: $restored head(s) re-filed, but the republish after the thaw failed (${why.message})")
            }
        }
        return restored
    }

    /**
     * One head: restored (`true`), or refused with a line on stderr (`false`).
     * Never thrown for a cause this knows about — the caller guards the rest.
     */
    private suspend fun restoreHead(name: String, line: LedgerLine): Boolean {
        val short = line.cid.take(19)
        if (publisher?.isPreset(name) == true) {
            System.err.println("[OROBOROS] programs: '$name' is a preset now; its ledger head $short is not restored")
            return false
        }
        val id = runCatching { ContentId(line.cid) }.getOrNull() ?: return false
        // THE CAS DOES NOT ANSWER null FOR A CORRUPT BLOB. `CasStore.get`
        // (job/CasStore.kt) and the daemon's `FileCasStore.get` (util/oroboros/Sha2CasBus.kt)
        // both re-hash what they read and THROW `digest mismatch` when the bytes are
        // present and no longer their own id; only an ABSENT blob is null. Read
        // unguarded, one blob rotted by a partial sync or an external "repair" of
        // <forgeHome>/cas would throw out of the thaw, out of mainImpl and past the
        // daemon's only catch (CancellationException) — no HTTP surface at all, for a
        // fault whose honest cost is one program.
        val read = withContext(Dispatchers.IO) { runCatching { cas.get(id) } }
        val bytes = read.getOrNull()
        if (bytes == null) {
            val why = read.exceptionOrNull()?.let { "unreadable in the CAS (${it.message})" } ?: "not in the CAS"
            System.err.println("[OROBOROS] programs: '$name' head $short is in the ledger but $why; it is not restored")
            return false
        }
        // Parsed for the refusal, not for the bytes: a blob that no longer reads
        // as a program must not be re-filed as one. The bytes filed are the CAS
        // bytes themselves, so the restored cid is the published cid by identity.
        if (runCatching { LcncProgramConfix.fromJson(name, bytes.decodeToString()) }.getOrNull() == null) {
            System.err.println("[OROBOROS] programs: '$name' head $short does not read as a program; it is not restored")
            return false
        }
        // The re-file is a couch write over a CAS put: a store that refuses either
        // must cost this one program, not the surface. (`putAttachment`'s own
        // `require(cid == ref.contentId)` cannot fire here — these bytes are what the
        // CAS just verified against `id` — so what is caught is the write itself.)
        val filed = withContext(Dispatchers.IO) { runCatching { refile(name, line, id, bytes) } }
        filed.exceptionOrNull()?.let { why ->
            System.err.println("[OROBOROS] programs: '$name' head $short could not be re-filed (${why.message}); it is not restored")
            return false
        }
        lock.withLock { heads[name] = line }
        return true
    }

    /** Every recorded version of [name], oldest first, from the ledger. */
    suspend fun history(name: String): List<LedgerLine> = readLedger().filter { it.name == name }

    /** The head cid of every name the ledger knows — what a restart restored. */
    suspend fun heads(): Map<String, String> = lock.withLock { heads.mapValues { it.value.cid } }

    /** The head line of one name, or null when the ledger has never recorded it. */
    suspend fun head(name: String): LedgerLine? = lock.withLock { heads[name] }

    // Re-file the head as its `panels/<name>` attachment, exactly as the publish
    // route filed it, so a second thaw writes nothing: the same contentId, the
    // recorded actor, the cid's own prefix as the revision and the recorded
    // instant as the sequence make the couch doc identical, and an attachment
    // already at this cid is left alone rather than churning the changes feed.
    private fun refile(name: String, line: LedgerLine, id: ContentId, bytes: ByteArray) {
        val att = attachments ?: return
        val path = ATTACHMENT_PREFIX + name
        if (runCatching { att.getAttachment(path) }.getOrNull()?.first?.contentId == id) return
        att.putAttachment(
            OroborosAttachmentRef(
                path = path, contentType = "application/json", length = bytes.size.toLong(),
                contentId = id, agentId = line.actor, revision = id.hex.take(12), sequence = line.atMs,
            ),
            bytes,
        )
    }

    private suspend fun readLedger(): List<LedgerLine> {
        val file = ledger ?: return emptyList()
        // An unreadable ledger file is a boot with no restored heads, never a boot that
        // dies: `isFile` and `readLines` are two syscalls apart, and the file lives in a
        // home an operator may have copied, chmod'ed or replaced under the daemon.
        val lines = withContext(Dispatchers.IO) {
            runCatching { if (file.isFile()) file.readLines().filter { it.isNotBlank() } else emptyList() }
                .getOrElse { why ->
                    System.err.println("[OROBOROS] programs: the ledger at ${file.path} could not be read (${why.message}); no head is restored")
                    emptyList()
                }
        }
        return lines.mapNotNull { raw ->
            val m = runCatching { JsonSupport.parseMap(raw) }.getOrNull() ?: return@mapNotNull null
            val name = m["name"]?.toString() ?: return@mapNotNull null
            val cid = m["cid"]?.toString() ?: return@mapNotNull null
            LedgerLine(name, cid, m["previousCid"]?.toString(), (m["atMs"] as? Number)?.toLong() ?: 0L, m["actor"]?.toString().orEmpty())
        }
    }

    companion object {
        /** The same namespace the panel save route files under — this ledger re-files, it does not relocate. */
        const val ATTACHMENT_PREFIX = "panels/"

        /** Who the publish route says it is; the ledger records the actor it was given. */
        const val ACTOR = "panels-editor"

        fun ledgerFile(forgeHome: File): File = forgeHome.resolve("programs/ledger.jsonl")
    }
}
