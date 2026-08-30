package borg.trikeshed.wiki

import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * WikiSkill (arXiv 2608.27454) as two ordinary LCNC legos.
 *
 * `wiki.consolidate` is the **Wiki Maintainer**: one consolidation pass k —
 * prior wiki W(k-1) + a sample of successful AND failing execution traces in,
 * PATCH-based edits to the `wiki/patterns/` pages + `index.md` + `logs.md` out.
 * `wiki.propose` is the **Skill Proposer**: multi-turn ReAct whose ONLY
 * initial context is `index.md`, `skill-impact.md`, and an outcome summary;
 * it asks for page/trace reads which the RUNNER performs and logs, and emits
 * exactly ONE atomic proposal.
 *
 * Cans-and-atoms: neither lego hides an iteration loop. One invocation is one
 * pass; the loop over iterations lives in the caller (a program document or
 * successive `/api/lcnc/run` posts), exactly as the paper's outer training
 * loop does.
 *
 * Every seam that could spend tokens or touch the world is injected:
 * [WikiDialog] (the daemon wires BrainClient.chatSeat — the ONE spend seam),
 * [WikiTraceLoader] (the daemon wires CAS + the hermes profile), and the wiki
 * home directory (the daemon wires the FORGE home, never the repo worktree).
 * Tests wire fakes and spend nothing.
 *
 * Two claim-check gates keep authorship honest, in the spirit of
 * [borg.trikeshed.narsese.LegalNodes.groundList] — the model proposes, code
 * verifies:
 *  - a created/edited pattern page must NAME at least one of the sampled
 *    transcript cids (provenance is not optional);
 *  - a proposal must target exactly ONE skill, and its PURPOSE.md must name
 *    at least one existing pattern page.
 * A failing gate REFUSES the edit and reports it; the runner never authors
 * replacement prose of its own.
 */
object WikiNodes {

    const val CONSOLIDATE = "wiki.consolidate"
    const val PROPOSE = "wiki.propose"

    // ── seams ──────────────────────────────────────────────────────────

    /** One model turn. The daemon wires BrainClient.chatSeat; tests wire a fake. */
    fun interface WikiDialog {
        suspend fun ask(call: WikiCall): WikiReply
    }

    data class WikiCall(
        val system: String,
        val prompt: String,
        val maxTokens: Int,
        val temperature: Double,
        val contextId: String,
        val preferredModel: String? = null,
    )

    /** A model reply; [model] is the model id that actually answered. */
    data class WikiReply(val content: String, val model: String)

    /** Resolve one execution trace by content id. */
    fun interface WikiTraceLoader {
        suspend fun load(cid: String): WikiTrace?
    }

    /** A resolved trace: the cid it was asked for, its text, and where it came from. */
    data class WikiTrace(val cid: String, val text: String, val source: String)

    // ── wiki layout ────────────────────────────────────────────────────

    /** The three-layer wiki under the forge home. Never the repo worktree. */
    class WikiHome(val root: File) {
        val patterns = File(root, "patterns")
        val skills = File(root, "skills")
        val rawResponses = File(root, "raw-responses")
        val readLogs = File(root, "read-log")
        val index = File(root, "index.md")
        val logs = File(root, "logs.md")
        val skillImpact = File(root, "skill-impact.md")

        fun ensure() {
            patterns.mkdirs(); skills.mkdirs(); rawResponses.mkdirs(); readLogs.mkdirs()
        }

        fun patternFiles(): List<File> =
            patterns.listFiles()?.filter { it.isFile && it.name.endsWith(".md") }?.sortedBy { it.name } ?: emptyList()

        /** Resolve a wiki-relative path, refusing anything that escapes the wiki root. */
        fun resolve(rel: String): File? {
            val cleaned = rel.trim().removePrefix("./").removePrefix("wiki/")
            if (cleaned.isEmpty() || cleaned.startsWith("/") || cleaned.contains("..")) return null
            val f = File(root, cleaned)
            val canonicalRoot = root.canonicalFile.path + File.separator
            return if ((f.canonicalFile.path + File.separator).startsWith(canonicalRoot)) f else null
        }
    }

    // ── wiki.consolidate — the Wiki Maintainer ─────────────────────────

    /**
     * ONE Maintainer iteration. Inputs (`cids` wire or param) name the sampled
     * transcripts; the prior wiki is read off disk; ONE model call produces a
     * JSON edit script; the edits are staged, gated, and written atomically.
     *
     * Response capture is MANDATORY and happens here, not in the caller:
     * `raw-responses/<contextId>.json` carries request metadata + the full
     * response text + its cid, and `logs.md` records that cid — so a pattern's
     * text is always traceable back to the model turn that authored it.
     */
    fun consolidateRunner(
        dialog: WikiDialog,
        wikiRoot: () -> File,
        traces: WikiTraceLoader,
        casPut: (ByteArray) -> String = { ContentId.of(it).value },
        clock: () -> Long = { System.currentTimeMillis() },
    ): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val home = WikiHome(wikiRoot())
        withContext(Dispatchers.IO) { home.ensure() }

        val cids = cidList(inputs["cids"] ?: inputs["cids?"] ?: node.params["cids"])
        require(cids.isNotEmpty()) { "$CONSOLIDATE: no transcript cids (wire `cids` or set the param)" }
        val iteration = node.params["iteration"]?.toIntOrNull() ?: 1
        val maxTokens = node.params["maxTokens"]?.toIntOrNull() ?: 4096
        val temperature = node.params["temperature"]?.toDoubleOrNull() ?: 0.2
        val perTrace = node.params["traceChars"]?.toIntOrNull() ?: 9000
        val contextId = node.params["contextId"]?.takeIf { it.isNotBlank() }
            ?: "wiki.consolidate/iter$iteration/${clock()}"

        // ── W(k-1): the FULL prior wiki, per the paper ──
        val priorPatterns = withContext(Dispatchers.IO) {
            home.patternFiles().map { it.name to it.readText() }
        }
        val priorIndex = withContext(Dispatchers.IO) { if (home.index.isFile) home.index.readText() else "" }
        val priorLogs = withContext(Dispatchers.IO) { if (home.logs.isFile) home.logs.readText() else "" }

        // ── the sampled traces ──
        val loaded = ArrayList<WikiTrace>()
        val missing = ArrayList<String>()
        for (cid in cids) {
            val t = traces.load(cid)
            if (t == null) missing.add(cid) else loaded.add(t)
        }
        require(loaded.isNotEmpty()) { "$CONSOLIDATE: none of ${cids.size} cids resolved (missing: $missing)" }

        val prompt = buildConsolidatePrompt(iteration, priorIndex, priorPatterns, priorLogs, loaded, perTrace)
        val promptCid = ContentId.of(prompt.encodeToByteArray()).value

        val reply = dialog.ask(
            WikiCall(
                system = MAINTAINER_SYSTEM,
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature,
                contextId = contextId,
                preferredModel = node.params["model"]?.takeIf { it.isNotBlank() },
            ),
        )

        // ── MANDATORY response capture, BEFORE any parse can fail ──
        val responseCid = casPut(reply.content.encodeToByteArray())
        val capture = linkedMapOf<String, Any?>(
            "pass" to "wiki.consolidate",
            "iteration" to iteration,
            "contextId" to contextId,
            "atMs" to clock(),
            "model" to reply.model,
            "maxTokens" to maxTokens,
            "temperature" to temperature,
            "promptCid" to promptCid,
            "promptChars" to prompt.length,
            "sampledCids" to cids,
            "resolvedCids" to loaded.map { it.cid },
            "unresolvedCids" to missing,
            "responseCid" to responseCid,
            "responseChars" to reply.content.length,
            "response" to reply.content,
        )
        val captureFile = File(home.rawResponses, "${safeName(contextId)}.json")
        withContext(Dispatchers.IO) { captureFile.writeText(JsonSupport.stringify(capture)) }

        // ── parse the edit script; a non-JSON reply is a LOUD no-op ──
        val script = lastBalancedObject(reply.content)
        if (script == null || script["edits"] !is List<*>) {
            val line = "- iteration $iteration | ${isoish(clock())} | contextId=$contextId | model=${reply.model} " +
                "| responseCid=$responseCid | REFUSED: no parsable {\"edits\":[...]} object in the response\n"
            withContext(Dispatchers.IO) { home.logs.appendText(line) }
            return@LcncNodeRunner mapOf(
                "report" to linkedMapOf<String, Any?>(
                    "ok" to false,
                    "error" to "unparsable_edit_script",
                    "iteration" to iteration,
                    "contextId" to contextId,
                    "model" to reply.model,
                    "responseCid" to responseCid,
                    "responsePath" to captureFile.absolutePath,
                ),
            )
        }

        // ── stage every edit in memory; nothing hits disk until all pass ──
        val staged = LinkedHashMap<String, String>()   // wiki-relative path -> new content
        val applied = ArrayList<Map<String, Any?>>()
        val refused = ArrayList<Map<String, Any?>>()
        val resolvedCids = loaded.map { it.cid }

        suspend fun current(rel: String): String? = staged[rel]
            ?: withContext(Dispatchers.IO) { home.resolve(rel)?.takeIf { it.isFile }?.readText() }

        for (raw in (script["edits"] as List<*>)) {
            val edit = (raw as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: continue
            val op = (edit["op"] as? String)?.trim()?.lowercase() ?: "append"
            val relRaw = (edit["file"] as? String)?.trim().orEmpty()
            // logs.md is the RUNNER's machine ledger (precise-ms timestamps, real
            // response cids). A model asked for "a narrative line" writes lines that
            // IMITATE that format — mission-002 caught three with round 00.000Z
            // stamps reusing an earlier iteration's cid, and one citing a
            // raw-responses file that never existed. Nothing about the model is at
            // fault; a shared file simply cannot be a trustworthy machine ledger.
            // Model narrative is therefore redirected to its own file, so a reader
            // can tell provenance by which file a line lives in.
            val relRedirected = relRaw.removePrefix("./").removePrefix("wiki/")
            val rel = if (relRedirected == "logs.md") "logs-narrative.md" else relRedirected
            val text = (edit["text"] as? String).orEmpty()
            val target = home.resolve(rel)
            if (rel.isEmpty() || target == null || !rel.endsWith(".md")) {
                refused.add(mapOf("op" to op, "file" to relRaw, "reason" to "path outside the wiki or not a .md file"))
                continue
            }
            val before = current(rel)
            val after: String? = when (op) {
                "create" -> if (before != null) null.also {
                    refused.add(mapOf("op" to op, "file" to rel, "reason" to "create on an existing file (use replace/insert/append)"))
                } else text
                "append" -> (before ?: "") + (if (before != null && !before.endsWith("\n")) "\n" else "") + text
                "prepend" -> text + (if (text.endsWith("\n")) "" else "\n") + (before ?: "")
                "replace", "patch" -> {
                    val find = (edit["find"] as? String).orEmpty()
                    when {
                        before == null -> null.also { refused.add(mapOf("op" to op, "file" to rel, "reason" to "replace on a file that does not exist")) }
                        find.isEmpty() -> null.also { refused.add(mapOf("op" to op, "file" to rel, "reason" to "replace without a `find` span")) }
                        !before.contains(find) -> null.also { refused.add(mapOf("op" to op, "file" to rel, "reason" to "`find` span not present in the file")) }
                        else -> before.replaceFirst(find, text)
                    }
                }
                "insert" -> {
                    val anchor = ((edit["after"] as? String) ?: (edit["find"] as? String)).orEmpty()
                    when {
                        before == null -> null.also { refused.add(mapOf("op" to op, "file" to rel, "reason" to "insert on a file that does not exist")) }
                        anchor.isEmpty() -> null.also { refused.add(mapOf("op" to op, "file" to rel, "reason" to "insert without an `after` anchor")) }
                        !before.contains(anchor) -> null.also { refused.add(mapOf("op" to op, "file" to rel, "reason" to "`after` anchor not present in the file")) }
                        else -> {
                            val at = before.indexOf(anchor) + anchor.length
                            before.substring(0, at) + (if (text.startsWith("\n")) "" else "\n") + text + before.substring(at)
                        }
                    }
                }
                else -> null.also { refused.add(mapOf("op" to op, "file" to rel, "reason" to "unknown op")) }
            }
            if (after == null) continue

            // Provenance gate. A NEW pattern page must name a transcript cid
            // from THIS iteration's sample — it derives from those traces or
            // it has no business existing. An EDIT to an existing page must
            // leave at least one transcript cid standing: an incremental patch
            // may cite a fresh trace, but it may never strip the provenance a
            // previous iteration recorded (the wiki is never rolled back).
            if (rel.startsWith("patterns/")) {
                val fromSample = resolvedCids.any { cid ->
                    after.contains(cid) || after.contains(cid.removePrefix("sha256:"))
                }
                val anyCid = CID_TOKEN.containsMatchIn(after)
                val ok = if (before == null) fromSample else (fromSample || anyCid)
                if (!ok) {
                    refused.add(mapOf(
                        "op" to op, "file" to rel,
                        "reason" to if (before == null)
                            "new pattern page names no sampled transcript cid (provenance gate)"
                        else "edit would leave the pattern page with no transcript cid (provenance gate)",
                    ))
                    continue
                }
            }
            staged[rel] = after
            applied.add(linkedMapOf<String, Any?>(
                "op" to op, "file" to rel,
                "beforeChars" to (before?.length ?: 0), "afterChars" to after.length,
                "created" to (before == null),
            ))
        }

        // ── commit ──
        withContext(Dispatchers.IO) {
            for ((rel, content) in staged) {
                val f = home.resolve(rel) ?: continue
                f.parentFile?.mkdirs()
                f.writeText(content)
            }
        }

        // Runner-emitted evolution record — the machine half of logs.md. The
        // Maintainer's own narrative rides `edits` targeting logs.md.
        val logLine = buildString {
            append("- iteration ").append(iteration).append(" | ").append(isoish(clock()))
            append(" | contextId=").append(contextId)
            append(" | model=").append(reply.model)
            append(" | responseCid=").append(responseCid)
            append(" | responseFile=raw-responses/").append(safeName(contextId)).append(".json")
            append(" | traces=").append(resolvedCids.joinToString(","))
            append(" | applied=").append(applied.size)
            append(" refused=").append(refused.size)
            append(" files=").append(staged.keys.joinToString(","))
            append('\n')
        }
        withContext(Dispatchers.IO) { home.logs.appendText(logLine) }

        mapOf(
            "report" to linkedMapOf<String, Any?>(
                "ok" to true,
                "iteration" to iteration,
                "wikiRoot" to home.root.absolutePath,
                "contextId" to contextId,
                "model" to reply.model,
                "promptCid" to promptCid,
                "responseCid" to responseCid,
                "responsePath" to captureFile.absolutePath,
                "sampledCids" to cids,
                "resolvedCids" to resolvedCids,
                "unresolvedCids" to missing,
                "applied" to applied,
                "refused" to refused,
                "files" to staged.keys.toList(),
                "patterns" to withContext(Dispatchers.IO) { home.patternFiles().map { it.name } },
            ),
        )
    }

    // ── wiki.propose — the Skill Proposer (multi-turn ReAct) ───────────

    /**
     * One Proposer pass. Initial context is EXACTLY the paper's: the wiki
     * index, the skill-impact history, and a concise outcome summary. The
     * model then asks for reads (`{"action":"read","targets":[...]}`); the
     * RUNNER performs each read and appends it to a machine read log
     * (`read-log/<contextId>.jsonl`) before feeding the contents back. The
     * pass ends when the model emits `{"action":"propose",...}` — ONE skill,
     * or the proposal is refused.
     */
    fun proposeRunner(
        dialog: WikiDialog,
        wikiRoot: () -> File,
        traces: WikiTraceLoader,
        casPut: (ByteArray) -> String = { ContentId.of(it).value },
        clock: () -> Long = { System.currentTimeMillis() },
    ): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val home = WikiHome(wikiRoot())
        withContext(Dispatchers.IO) { home.ensure() }

        val summary = ((inputs["summary"] ?: inputs["summary?"]) as? String)?.takeIf { it.isNotBlank() }
            ?: node.params["summary"]?.takeIf { it.isNotBlank() }
            ?: "No outcome summary supplied."
        val maxTurns = node.params["maxTurns"]?.toIntOrNull() ?: 6
        val maxTokens = node.params["maxTokens"]?.toIntOrNull() ?: 4096
        val temperature = node.params["temperature"]?.toDoubleOrNull() ?: 0.2
        val readChars = node.params["readChars"]?.toIntOrNull() ?: 9000
        val baseContextId = node.params["contextId"]?.takeIf { it.isNotBlank() }
            ?: "wiki.propose/${clock()}"

        val index = withContext(Dispatchers.IO) { if (home.index.isFile) home.index.readText() else "(index.md is empty)" }
        val impact = withContext(Dispatchers.IO) { if (home.skillImpact.isFile) home.skillImpact.readText() else "(no prior proposals)" }
        val existingSkills = withContext(Dispatchers.IO) {
            home.skills.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
        }

        val readLog = File(home.readLogs, "${safeName(baseContextId)}.jsonl")
        val readOrder = ArrayList<Map<String, Any?>>()
        val turnRecords = ArrayList<Map<String, Any?>>()
        val transcript = StringBuilder()
        transcript.append(buildProposerOpening(index, impact, existingSkills, summary))

        var proposal: Map<String, Any?>? = null
        var lastModel = ""
        var turn = 0
        while (turn < maxTurns && proposal == null) {
            turn++
            val contextId = "$baseContextId/t$turn"
            val reply = dialog.ask(
                WikiCall(
                    system = PROPOSER_SYSTEM,
                    prompt = transcript.toString(),
                    maxTokens = maxTokens,
                    temperature = temperature,
                    contextId = contextId,
                    preferredModel = node.params["model"]?.takeIf { it.isNotBlank() },
                ),
            )
            lastModel = reply.model
            val responseCid = casPut(reply.content.encodeToByteArray())
            val captureFile = File(home.rawResponses, "${safeName(contextId)}.json")
            withContext(Dispatchers.IO) {
                captureFile.writeText(JsonSupport.stringify(linkedMapOf<String, Any?>(
                    "pass" to "wiki.propose",
                    "turn" to turn,
                    "contextId" to contextId,
                    "atMs" to clock(),
                    "model" to reply.model,
                    "maxTokens" to maxTokens,
                    "promptCid" to ContentId.of(transcript.toString().encodeToByteArray()).value,
                    "promptChars" to transcript.length,
                    "responseCid" to responseCid,
                    "response" to reply.content,
                )))
            }
            turnRecords.add(linkedMapOf<String, Any?>(
                "turn" to turn, "contextId" to contextId, "model" to reply.model,
                "responseCid" to responseCid, "responsePath" to captureFile.absolutePath,
            ))

            val act = lastBalancedObject(reply.content)
            val action = (act?.get("action") as? String)?.trim()?.lowercase()
            if (act != null && action == "propose") {
                proposal = act
                break
            }
            if (act == null || action != "read") {
                transcript.append("\n\n[RUNNER] Your turn $turn produced no parsable {\"action\":\"read\"|\"propose\"} object. ")
                transcript.append("Reply with EXACTLY ONE JSON object and nothing after it.\n")
                continue
            }
            val targets = (act["targets"] as? List<*>)?.map { it.toString() } ?: emptyList()
            transcript.append("\n\n[RUNNER] Reads performed for turn ").append(turn).append(":\n")
            for (t in targets.take(8)) {
                val (body, source) = performRead(home, traces, t, readChars)
                val record = linkedMapOf<String, Any?>(
                    "seq" to (readOrder.size + 1),
                    "turn" to turn,
                    "contextId" to contextId,
                    "atMs" to clock(),
                    "target" to t,
                    "source" to source,
                    "bytes" to body.length,
                    "found" to (source != "not_found"),
                )
                readOrder.add(record)
                withContext(Dispatchers.IO) { readLog.appendText(JsonSupport.stringify(record) + "\n") }
                transcript.append("\n<<< ").append(t).append(" (").append(source).append(") >>>\n")
                transcript.append(body).append("\n")
            }
            if (targets.isEmpty()) {
                transcript.append("(no targets named — name pattern pages or trace cids, or emit your proposal)\n")
            }
        }

        if (proposal == null) {
            return@LcncNodeRunner mapOf("report" to linkedMapOf<String, Any?>(
                "ok" to false,
                "error" to "no_proposal_within_maxTurns",
                "wikiRoot" to home.root.absolutePath,
                "contextId" to baseContextId,
                "turns" to turnRecords,
                "reads" to readOrder,
                "readLogPath" to readLog.absolutePath,
            ))
        }

        // ── atomicity + grounding gates ──
        val skillNames = LinkedHashSet<String>()
        (proposal["skill"] as? String)?.trim()?.takeIf { it.isNotBlank() }?.let { skillNames.add(it) }
        (proposal["skills"] as? List<*>)?.forEach { s -> s?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { skillNames.add(it) } }
        val refusals = ArrayList<String>()
        if (skillNames.size != 1) refusals.add("proposal targets ${skillNames.size} skills — exactly one is allowed")
        val skill = skillNames.firstOrNull()?.let { safeName(it) }.orEmpty()
        val kind = (proposal["kind"] as? String)?.trim()?.lowercase() ?: if (skill in existingSkills) "patch" else "new"
        val skillMd = (proposal["skillMd"] as? String).orEmpty()
        val purposeMd = (proposal["purposeMd"] as? String).orEmpty()
        val diff = (proposal["diff"] as? String).orEmpty()
        val patternNames = withContext(Dispatchers.IO) { home.patternFiles().map { it.name } }
        if (kind == "new" && (skillMd.isBlank() || purposeMd.isBlank())) {
            refusals.add("a new skill needs BOTH skillMd and purposeMd")
        }
        if (kind == "patch" && diff.isBlank()) refusals.add("a patch proposal needs a unified `diff`")
        if (purposeMd.isNotBlank()) {
            val mapped = patternNames.any { purposeMd.contains(it) || purposeMd.contains(it.removeSuffix(".md")) }
            if (!mapped) refusals.add("PURPOSE names no existing wiki pattern page (mapping gate)")
        } else if (kind == "patch") {
            refusals.add("PURPOSE mapping is mandatory even for a patch proposal")
        }

        if (refusals.isNotEmpty()) {
            return@LcncNodeRunner mapOf("report" to linkedMapOf<String, Any?>(
                "ok" to false,
                "error" to "proposal_refused",
                "refusals" to refusals,
                "wikiRoot" to home.root.absolutePath,
                "contextId" to baseContextId,
                "skill" to skill,
                "turns" to turnRecords,
                "reads" to readOrder,
                "readLogPath" to readLog.absolutePath,
            ))
        }

        val skillDir = File(home.skills, skill)
        val written = ArrayList<String>()
        withContext(Dispatchers.IO) {
            skillDir.mkdirs()
            if (kind == "new") {
                File(skillDir, "SKILL.md").writeText(skillMd)
                written.add("skills/$skill/SKILL.md")
                File(skillDir, "PURPOSE.md").writeText(purposeMd)
                written.add("skills/$skill/PURPOSE.md")
                if (diff.isNotBlank()) {
                    File(skillDir, "proposal-${safeName(baseContextId)}.diff").writeText(diff)
                    written.add("skills/$skill/proposal-${safeName(baseContextId)}.diff")
                }
            } else {
                File(skillDir, "proposal-${safeName(baseContextId)}.diff").writeText(diff)
                written.add("skills/$skill/proposal-${safeName(baseContextId)}.diff")
                if (purposeMd.isNotBlank()) {
                    File(skillDir, "PURPOSE.md").writeText(purposeMd)
                    written.add("skills/$skill/PURPOSE.md")
                }
            }
        }
        val proposalCid = casPut(JsonSupport.stringify(proposal).encodeToByteArray())
        val impactLine = buildString {
            append("- proposal ").append(baseContextId).append(" | ").append(isoish(clock()))
            append(" | targetSkill=").append(skill)
            append(" | kind=").append(kind)
            append(" | model=").append(lastModel)
            append(" | proposalCid=").append(proposalCid)
            append(" | artifacts=").append(written.joinToString(","))
            append(" | reads=").append(readOrder.size)
            append(" | readLog=read-log/").append(safeName(baseContextId)).append(".jsonl")
            append(" | validationScore=pending | acceptance=pending")
            append('\n')
        }
        withContext(Dispatchers.IO) { home.skillImpact.appendText(impactLine) }

        mapOf("report" to linkedMapOf<String, Any?>(
            "ok" to true,
            "wikiRoot" to home.root.absolutePath,
            "contextId" to baseContextId,
            "model" to lastModel,
            "skill" to skill,
            "kind" to kind,
            "proposalCid" to proposalCid,
            "artifacts" to written.map { File(home.root, it).absolutePath },
            "relativeArtifacts" to written,
            "initialInputs" to listOf("index.md", "skill-impact.md", "outcome-summary(param)"),
            "turns" to turnRecords,
            "reads" to readOrder,
            "readLogPath" to readLog.absolutePath,
            "patterns" to patternNames,
        ))
    }

    /** One ReAct read, performed BY THE RUNNER: a wiki page or a trace cid. */
    private suspend fun performRead(
        home: WikiHome,
        traces: WikiTraceLoader,
        targetRaw: String,
        limit: Int,
    ): Pair<String, String> {
        val target = targetRaw.trim()
        val cidish = target.removePrefix("trace:").removePrefix("raw:").trim()
        if (cidish.startsWith("sha256:") || Regex("^[0-9a-f]{64}$").matches(cidish)) {
            val cid = if (cidish.startsWith("sha256:")) cidish else "sha256:$cidish"
            val t = traces.load(cid) ?: return "(no trace with cid $cid)" to "not_found"
            return window(t.text, limit) to "trace:${t.source}"
        }
        val f = home.resolve(target) ?: return "(path outside the wiki: $target)" to "not_found"
        return withContext(Dispatchers.IO) {
            if (f.isFile) f.readText().take(limit) to "wiki" else "(no such wiki file: $target)" to "not_found"
        }
    }

    // ── prompts ────────────────────────────────────────────────────────

    internal const val MAINTAINER_SYSTEM =
        "You are the Wiki Maintainer of a WikiSkill knowledge layer (arXiv 2608.27454). " +
            "You read the FULL prior wiki plus a sample of successful and failing execution traces, " +
            "perform root-cause analysis on the failures and strategy extraction from the successes, " +
            "and return incremental PATCH-based edits. " +
            "Reply with EXACTLY ONE JSON object and no prose outside it."

    internal const val PROPOSER_SYSTEM =
        "You are the Skill Proposer of a WikiSkill system (arXiv 2608.27454). " +
            "You start from the wiki index, the skill-impact history and an outcome summary ONLY. " +
            "You must ACTIVELY REQUEST specific pattern pages and raw traces before proposing. " +
            "You emit exactly ONE atomic proposal per iteration, targeting a SINGLE skill. " +
            "Reply with EXACTLY ONE JSON object per turn and no prose outside it."

    internal fun buildConsolidatePrompt(
        iteration: Int,
        priorIndex: String,
        priorPatterns: List<Pair<String, String>>,
        priorLogs: String,
        traces: List<WikiTrace>,
        perTrace: Int,
    ): String = buildString {
        appendLine("# Consolidation iteration $iteration")
        appendLine()
        appendLine("## Prior wiki W(k-1)")
        appendLine()
        appendLine("### index.md")
        appendLine(priorIndex.ifBlank { "(empty — this is the first iteration)" })
        appendLine()
        appendLine("### patterns/ (${priorPatterns.size} page(s))")
        if (priorPatterns.isEmpty()) appendLine("(no pattern pages yet)")
        for ((name, body) in priorPatterns) {
            appendLine()
            appendLine("<<<FILE patterns/$name>>>")
            appendLine(body)
            appendLine("<<<END patterns/$name>>>")
        }
        appendLine()
        appendLine("### logs.md (tail)")
        appendLine(priorLogs.takeLast(4000).ifBlank { "(no log entries yet)" })
        appendLine()
        appendLine("## Sampled execution traces for this iteration")
        appendLine("Each trace is identified by its content id (cid). Every pattern page you")
        appendLine("create or edit MUST quote at least one of these cids verbatim.")
        for (t in traces) {
            appendLine()
            appendLine("<<<TRACE ${t.cid}>>>")
            appendLine(window(t.text, perTrace))
            appendLine("<<<END TRACE ${t.cid}>>>")
        }
        appendLine()
        appendLine("## Your task")
        appendLine("1. Root-cause the FAILING traces; extract the strategy from the SUCCEEDING ones.")
        appendLine("2. Create or PATCH pattern pages under patterns/ — one page per failure mode or")
        appendLine("   successful strategy, each with an actionable workaround.")
        appendLine("3. Revise index.md so it catalogues every pattern page.")
        appendLine("4. Append one narrative line to logs-narrative.md describing what changed and why.")
        appendLine("   (logs.md is the runner's machine ledger and is NOT yours to write —")
        appendLine("    an edit naming it is redirected to logs-narrative.md.)")
        appendLine("Prefer INCREMENTAL edits (replace/insert/append) on existing pages over creating")
        appendLine("near-duplicates. Never rewrite a page wholesale when a span edit will do.")
        appendLine()
        appendLine("## Reply format — ONE JSON object, nothing else")
        appendLine("""{"analysis":"<short>","edits":[""")
        appendLine("""  {"op":"create","file":"patterns/<slug>.md","text":"<full page markdown>"},""")
        appendLine("""  {"op":"replace","file":"patterns/<slug>.md","find":"<exact span already in the page>","text":"<replacement>"},""")
        appendLine("""  {"op":"insert","file":"patterns/<slug>.md","after":"<exact anchor span>","text":"<inserted markdown>"},""")
        appendLine("""  {"op":"append","file":"index.md","text":"<catalog line>"},""")
        appendLine("""  {"op":"append","file":"logs-narrative.md","text":"<narrative log line>"}""")
        appendLine("""]}""")
        appendLine("`find`/`after` spans must occur VERBATIM in the current file content shown above,")
        appendLine("or the edit is refused. A NEW pattern page that names no sampled cid is refused,")
        appendLine("and an edit that would leave an existing page with no transcript cid is refused.")
    }

    internal fun buildProposerOpening(
        index: String,
        impact: String,
        existingSkills: List<String>,
        summary: String,
    ): String = buildString {
        appendLine("# Skill proposal pass")
        appendLine()
        appendLine("## wiki index.md")
        appendLine(index)
        appendLine()
        appendLine("## skill-impact.md (proposal history)")
        appendLine(impact)
        appendLine()
        appendLine("## existing skills")
        appendLine(if (existingSkills.isEmpty()) "(none — any proposal must be a NEW skill)" else existingSkills.joinToString(", "))
        appendLine()
        appendLine("## outcome summary")
        appendLine(summary)
        appendLine()
        appendLine("## Protocol")
        appendLine("You have NOT been given the pattern pages or the raw traces. Ask for them.")
        appendLine("""Turn format A (read): {"action":"read","targets":["patterns/<name>.md","trace:sha256:<hex>"],"why":"<short>"}""")
        appendLine("""Turn format B (propose, ONE skill only):""")
        appendLine("""{"action":"propose","skill":"<slug>","kind":"new","skillMd":"<full SKILL.md>","purposeMd":"<full PURPOSE.md naming the motivating pattern pages by filename>","patterns":["patterns/<name>.md"]}""")
        appendLine("""or {"action":"propose","skill":"<existing slug>","kind":"patch","diff":"<unified diff against that ONE skill>","purposeMd":"<PURPOSE.md naming the motivating pattern pages>"}""")
        appendLine("Read at least one pattern page AND at least one raw trace before proposing.")
        appendLine("Reply with EXACTLY ONE JSON object per turn.")
        appendLine("Budget: keep SKILL.md under 80 lines and PURPOSE.md under 20 lines, and do")
        appendLine("not restate the pattern pages — cite them. A proposal that runs past the")
        appendLine("token budget is truncated and lost, so be terse and finish the object.")
    }

    // ── small helpers ──────────────────────────────────────────────────

    internal fun cidList(v: Any?): List<String> {
        val out = LinkedHashSet<String>()
        fun add(s: String) {
            val t = s.trim()
            if (t.isEmpty()) return
            out.add(if (t.startsWith("sha256:")) t else "sha256:$t")
        }
        when (v) {
            null -> {}
            is String -> v.split(',', '\n', ' ').forEach(::add)
            is List<*> -> v.forEach { e -> e?.toString()?.let(::add) }
            else -> v.toString().split(',', '\n', ' ').forEach(::add)
        }
        return out.toList()
    }

    internal fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /**
     * A transcript content id as it appears in a pattern page. The `sha256:`
     * prefix is OPTIONAL: a Maintainer writing prose cites the bare hex as
     * often as the prefixed form (`See trace \`ac79e55…\``), and a gate that
     * only recognised the prefixed spelling would refuse a perfectly
     * well-provenanced page — which is exactly what it did on the first
     * iteration-2 pass.
     */
    private val CID_TOKEN = Regex("(sha256:)?\\b[0-9a-f]{64}\\b")

    /**
     * A context window over one long trace: the HEAD (where the task is set)
     * plus the TAIL (where it succeeds or fails), with the elision named. A
     * naive `take(n)` would hand the Maintainer the opening of every trace and
     * the outcome of none — root-cause analysis needs the ending.
     */
    internal fun window(text: String, budget: Int): String {
        if (budget <= 0 || text.length <= budget) return text
        val head = (budget * 3) / 5
        val tail = budget - head
        val elided = text.length - budget
        return text.take(head) +
            "\n\n… [$elided characters elided by the runner's per-trace budget] …\n\n" +
            text.takeLast(tail)
    }

    private fun isoish(ms: Long): String =
        java.time.Instant.ofEpochMilli(ms).toString()

    /** The LAST balanced `{…}` object in [text] that parses as a JSON map. */
    internal fun lastBalancedObject(text: String): Map<String, Any?>? {
        var end = text.lastIndexOf('}')
        while (end >= 0) {
            var depth = 0
            var start = -1
            for (j in end downTo 0) {
                when (text[j]) {
                    '}' -> depth++
                    '{' -> depth--
                }
                if (depth == 0) { start = j; break }
            }
            if (start >= 0) {
                val parsed = runCatching { JsonSupport.parse(text.substring(start, end + 1)) }.getOrNull()
                if (parsed is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    return parsed.entries.associate { (k, v) -> k.toString() to v } as Map<String, Any?>
                }
            }
            end = if (end > 0) text.lastIndexOf('}', end - 1) else -1
        }
        return null
    }
}
