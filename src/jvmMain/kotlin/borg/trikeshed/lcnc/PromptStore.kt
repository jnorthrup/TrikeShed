package borg.trikeshed.lcnc

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.OroborosAttachmentRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * THE STORED SET OF PROMPTS — the write side, on the JVM daemon.
 *
 * A save puts the document's canonical bytes into CAS (the version's identity),
 * files the head as the attachment `prompts/<name>` exactly as a saved panel is
 * filed, appends one line to `<forgeHome>/prompts/ledger.jsonl`, and publishes
 * the head to the blackboard at `lcnc/prompt/<name>` through the one LCNC
 * writer. The ledger is the durable plane: the couch id→rev index is rebuilt
 * per boot (see NarsDurableLedger), so [thaw] replays the ledger, re-reads each
 * head's bytes from CAS by cid, re-files it, and re-publishes it.
 *
 * Seeds yield to people: a seed installs as a head only when its name has none.
 * Its bytes always enter CAS so a receipt that cites the seed's cid resolves.
 */
class PromptStore(
    private val attachments: CouchAttachmentGateway?,
    private val cas: CasStore,
    private val ledger: File?,
    private val publisher: LcncPublisher?,
    private val clock: () -> Long,
) : PromptReads {

    /** A save whose `baseCid` is no longer the head: refused, never silently overwritten. */
    class StaleBase(val name: String, val baseCid: String, val currentCid: String) :
        IllegalStateException("stale_base: $name is at $currentCid, not $baseCid")

    data class Saved(val cid: String, val previousCid: String?, val document: PromptDocument, val changed: Boolean)

    data class LedgerLine(val name: String, val cid: String, val previousCid: String?, val atMs: Long, val actor: String) {
        fun toMap(): Map<String, Any?> = linkedMapOf("name" to name, "cid" to cid, "previousCid" to previousCid, "atMs" to atMs, "actor" to actor)
    }

    private val lock = Mutex()
    private val heads = linkedMapOf<String, PromptDocument>()
    private val savedAt = linkedMapOf<String, Long>()
    private val versions = linkedMapOf<String, PromptDocument>()

    /**
     * Save [doc] as the head of its name. A save whose text, role and tags equal
     * the head is a no-op (`changed = false`, the head's cid). [baseCid], when
     * given, must be the current head or the save is refused with [StaleBase].
     */
    suspend fun save(doc: PromptDocument, actor: String, baseCid: String? = null): Saved = lock.withLock {
        require(PromptDocument.isValidName(doc.name)) { "bad prompt name: '${doc.name}'" }
        require(doc.text.length <= PromptDocument.MAX_CHARS) { "prompt text over ${PromptDocument.MAX_CHARS} chars" }
        require(doc.role in PromptDocument.ROLES) { "bad prompt role: '${doc.role}'" }
        val head = heads[doc.name]
        if (baseCid != null && head != null && head.cid != baseCid) throw StaleBase(doc.name, baseCid, head.cid)
        if (head != null && head.text == doc.text && head.role == doc.role && head.tags == doc.tags) {
            return Saved(head.cid, head.previousCid, head, changed = false)
        }
        val candidate = doc.copy(previousCid = head?.cid)
        install(candidate, actor, clock(), record = true)
        Saved(candidate.cid, candidate.previousCid, candidate, changed = true)
    }

    override suspend fun get(name: String): PromptDocument? = lock.withLock { heads[name] }

    override suspend fun byCid(cid: String): PromptDocument? {
        lock.withLock { versions[cid] }?.let { return it }
        val id = runCatching { ContentId(cid) }.getOrNull() ?: return null
        val bytes = withContext(Dispatchers.IO) { cas.get(id) } ?: return null
        val doc = runCatching { PromptDocument.fromJson(bytes.decodeToString()) }.getOrNull() ?: return null
        lock.withLock { versions[cid] = doc }
        return doc
    }

    override fun heads(): List<PromptHead> = heads.values.toList().map {
        PromptHead(it.name, it.cid, it.previousCid, it.role, it.variables, it.tags, it.text.length, savedAt[it.name] ?: 0L)
    }

    /** The heads, newest save first. */
    fun list(): List<PromptHead> = heads().sortedByDescending { it.savedAtMs }

    /** Every recorded version of [name], oldest first, from the ledger. */
    suspend fun history(name: String): List<LedgerLine> = readLedger().filter { it.name == name }

    /**
     * Boot: replay the ledger (the last line per name is its head), re-file and
     * re-publish each head from its CAS bytes, then install every seed whose name
     * has no head. Returns how many heads the ledger restored.
     */
    suspend fun thaw(seeds: List<PromptDocument> = emptyList()): Int {
        val last = linkedMapOf<String, LedgerLine>()
        for (line in readLedger()) last[line.name] = line
        var restored = 0
        for ((_, line) in last) {
            val id = runCatching { ContentId(line.cid) }.getOrNull() ?: continue
            val bytes = withContext(Dispatchers.IO) { cas.get(id) } ?: continue
            val doc = runCatching { PromptDocument.fromJson(bytes.decodeToString()) }.getOrNull() ?: continue
            lock.withLock { install(doc, line.actor, line.atMs, record = false) }
            restored++
        }
        for (seed in seeds) {
            withContext(Dispatchers.IO) { cas.put(seed.canonicalJson().encodeToByteArray()) }
            lock.withLock {
                versions[seed.cid] = seed
                if (!heads.containsKey(seed.name)) install(seed, SEED_ACTOR, clock(), record = false)
            }
        }
        return restored
    }

    /** `prompt.save`, the one write lego, registered beside the read legos. */
    fun register(ctx: borg.trikeshed.module.ModuleContext) {
        ctx.lcncRunners[PromptNodes.SAVE] = LcncNodeRunner { node, inputs ->
            val text = (inputs["text"] as? String) ?: node.params["text"]
                ?: throw IllegalArgumentException("prompt.save: no text wired in")
            val name = node.params["name"]?.trim().orEmpty()
            require(PromptDocument.isValidName(name)) { "prompt.save: bad name '$name' (lowercase, digits, dots, dashes)" }
            val role = node.params["role"]?.takeIf { it.isNotBlank() } ?: PromptDocument.ROLE_USER
            val tags = node.params["tags"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val saved = save(PromptDocument(name, text, role, tags), actor = PromptNodes.SAVE)
            mapOf("cid" to saved.cid, "previousCid" to saved.previousCid, "name" to name, "changed" to saved.changed)
        }
    }

    // Caller holds [lock].
    private suspend fun install(doc: PromptDocument, actor: String, atMs: Long, record: Boolean) {
        val bytes = doc.canonicalJson().encodeToByteArray()
        val cid = ContentId.of(bytes)
        withContext(Dispatchers.IO) {
            cas.put(bytes)
            attachments?.putAttachment(
                OroborosAttachmentRef(
                    path = ATTACHMENT_PREFIX + doc.name, contentType = "application/json", length = bytes.size.toLong(),
                    contentId = cid, agentId = actor, revision = cid.hex.take(12), sequence = atMs,
                ),
                bytes,
            )
            if (record) ledger?.let { file ->
                file.parentFile?.mkdirs()
                file.appendText(JsonSupport.stringify(LedgerLine(doc.name, cid.value, doc.previousCid, atMs, actor).toMap()) + "\n")
            }
        }
        heads[doc.name] = doc
        versions[cid.value] = doc
        savedAt[doc.name] = atMs
        publisher?.publishPrompt(LcncBlackboard.promptEntry(doc, atMs, actor))
    }

    private suspend fun readLedger(): List<LedgerLine> {
        val file = ledger ?: return emptyList()
        val lines = withContext(Dispatchers.IO) { if (file.exists()) file.readLines() else emptyList() }
        return lines.mapNotNull { raw ->
            val m = runCatching { JsonSupport.parseMap(raw) }.getOrNull() ?: return@mapNotNull null
            val name = m["name"]?.toString() ?: return@mapNotNull null
            val cid = m["cid"]?.toString() ?: return@mapNotNull null
            LedgerLine(name, cid, m["previousCid"]?.toString(), (m["atMs"] as? Number)?.toLong() ?: 0L, m["actor"]?.toString().orEmpty())
        }
    }

    companion object {
        const val ATTACHMENT_PREFIX = "prompts/"
        const val SEED_ACTOR = "seed"
        fun ledgerFile(forgeHome: File): File = File(forgeHome, "prompts/ledger.jsonl")
    }
}
