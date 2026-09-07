package borg.trikeshed.forge.server

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncPublisher
import borg.trikeshed.lcnc.PromptStore
import borg.trikeshed.lcnc.WorkspaceSnapshot
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.OroborosAttachmentRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The workspace snapshot citizen (Forge genesis, Cut C): composed from the blackboard and
 * the stores, written once to CAS and as the attachment `snapshots/<cid>`, chained through
 * a JSONL ledger under the forge home (the prompt ledger's precedent), and announced as
 * `lcnc/snapshot/head` through the one writer. `restore()` at boot re-reads the ledger so
 * the head survives a restart; `register()` offers the `workspace.snapshot` lego.
 */
class WorkspaceSnapshotService(
    private val blackboard: ConfixBlackboard,
    private val cas: CasStore,
    private val attachments: CouchAttachmentGateway?,
    private val prompts: PromptStore?,
    private val projectDbs: ProjectDbRegistry?,
    private val publisher: LcncPublisher?,
    private val ledger: File?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class Taken(val cid: String, val previousCid: String?, val atMs: Long, val note: String, val counts: Map<String, Int>, val json: String) {
        fun headEntry(actor: String): Map<String, Any?> = linkedMapOf("cid" to cid, "previousCid" to previousCid, "atMs" to atMs, "note" to note, "counts" to counts, "actor" to actor)
    }

    private val lock = Mutex()
    @Volatile private var headTaken: Taken? = null
    private val lineage = ArrayList<Map<String, Any?>>()

    val head: Taken? get() = headTaken

    /** The snapshot as it would be taken now; pure over the current state, nothing written. */
    fun compose(note: String): WorkspaceSnapshot = WorkspaceSnapshot.compose(
        atMs = clock(),
        note = note,
        previousCid = headTaken?.cid,
        entries = blackboard.snapshot().values,
        prompts = prompts?.heads()?.associate { it.name to it.cid } ?: emptyMap(),
        projectDbs = projectDbs?.all()?.map { linkedMapOf<String, Any?>("name" to it.name, "kind" to it.kind, "path" to it.path, "updateSeq" to it.db.updateSeq, "docs" to it.docCount) } ?: emptyList(),
    )

    suspend fun take(note: String, actor: String): Taken = lock.withLock {
        val snapshot = compose(note)
        val json = snapshot.canonicalJson()
        val bytes = json.encodeToByteArray()
        val cid = cas.put(bytes)
        check(cid.value == snapshot.cid) { "snapshot identity drifted between compose and put" }
        attachments?.putAttachment(
            OroborosAttachmentRef(path = "snapshots/${cid.value}", contentType = "application/json", length = bytes.size.toLong(), contentId = cid, agentId = actor, revision = cid.hex.take(12), sequence = snapshot.atMs),
            bytes,
        )
        val taken = Taken(cid.value, snapshot.previousCid, snapshot.atMs, snapshot.note, snapshot.counts(), json)
        val line = linkedMapOf<String, Any?>("cid" to taken.cid, "previousCid" to taken.previousCid, "atMs" to taken.atMs, "note" to taken.note, "actor" to actor, "counts" to taken.counts)
        ledger?.let { f -> withContext(Dispatchers.IO) { f.parentFile?.mkdirs(); f.appendText(JsonSupport.stringify(line) + "\n") } }
        synchronized(lineage) { lineage.add(0, line) }
        headTaken = taken
        publisher?.publishSnapshot(taken.headEntry(actor))
        taken
    }

    /** The lineage, newest first, as the ledger recorded it. */
    fun list(): List<Map<String, Any?>> = synchronized(lineage) { lineage.toList() }

    /** One snapshot's canonical bytes, only when they still hash to the cid asked for. */
    fun open(cid: String): ByteArray? {
        val id = runCatching { ContentId(cid) }.getOrNull() ?: return null
        // The mismatch this method already refuses (`takeIf` below) is one the CAS reports by
        // THROWING, not by answering null — `CasStore.get` / `FileCasStore.get` re-hash what
        // they read and raise `digest mismatch`, and only an ABSENT blob is null. Unwrapped,
        // a rotted snapshot blob threw out of `restore()`, which `mainImpl` calls sequentially
        // before the HTTP server binds. Unreadable now reads as absent, which is what the
        // caller's own "in the ledger but not in the CAS" branch is written for.
        val bytes = runCatching { cas.get(id) }.getOrNull() ?: return null
        return bytes.takeIf { ContentId.of(it) == id }
    }

    /** Boot: re-read the ledger (last line is the head), re-announce the head on the board. */
    suspend fun restore(): Int {
        val f = ledger ?: return 0
        val lines = withContext(Dispatchers.IO) { if (f.isFile) f.readLines().filter { it.isNotBlank() } else emptyList() }
        val parsed = lines.mapNotNull { runCatching { JsonSupport.parseMap(it) }.getOrNull() }
        synchronized(lineage) { lineage.clear(); lineage.addAll(parsed.reversed()) }
        val last = parsed.lastOrNull() ?: return 0
        val cid = last["cid"]?.toString() ?: return 0
        val bytes = open(cid)
        if (bytes == null) {
            System.err.println("[OROBOROS] snapshot head $cid is in the ledger but not in the CAS; the lineage is served, the head is not restored")
            return parsed.size
        }
        val snap = WorkspaceSnapshot.fromJson(bytes.decodeToString()) ?: return parsed.size
        headTaken = Taken(cid, snap.previousCid, snap.atMs, snap.note, snap.counts(), bytes.decodeToString())
        publisher?.publishSnapshot(headTaken!!.headEntry(last["actor"]?.toString() ?: "restore"))
        return parsed.size
    }

    /** The `workspace.snapshot` lego: a wired `note?` wins over the param. */
    fun register(ctx: borg.trikeshed.module.ModuleContext) {
        ctx.lcncRunners[LEGO] = LcncNodeRunner { node, inputs ->
            val note = (inputs["note"] ?: inputs["note?"])?.toString()?.takeIf { it.isNotBlank() } ?: node.params["note"].orEmpty()
            val taken = take(note, actor = LEGO)
            mapOf("cid" to taken.cid, "previousCid" to taken.previousCid, "snapshot" to JsonSupport.parse(taken.json))
        }
    }

    companion object {
        const val LEGO = "workspace.snapshot"
        fun ledgerFile(forgeHome: File): File = File(forgeHome, "snapshots/ledger.jsonl")
    }
}
