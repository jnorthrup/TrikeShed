package borg.trikeshed.agent.modelmux

import borg.trikeshed.cas.LineCasSourceRanges
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.mcp.McpTool
import borg.trikeshed.mcp.McpToolResult
import borg.trikeshed.mcp.ToolFailure

data class ModelInfo(val id: String, val provider: String, val capabilities: Set<String>)

data class ChatMessage(val role: String, val content: String)

data class ChatReply(val text: String, val inputTokens: Int, val outputTokens: Int)

/**
 * Host-side model access. Credentials live behind this port: a caller names a model and sends messages, and never
 * sees, supplies or receives a key. The JVM boundary wires it from the same KeyMux/ModelMux lanes the operator CLI
 * uses; tests wire a fixture.
 */
interface ModelPort {
    suspend fun models(): List<ModelInfo>
    suspend fun chat(model: String, messages: List<ChatMessage>, maxTokens: Int?): ChatReply
}

/**
 * ModelMux as MCP tools: shared evidence in, task-conditioned breadcrumbs out.
 *
 * What is registered IS the permission set. With a shared context the reading tools are present; [allowWrite] adds `modelmux_context_put`
 * (it mutates the shared store); a [models] port adds `modelmux_models` and `modelmux_chat` (the latter spends quota,
 * so it is not read-only). Nothing is gated by a runtime flag inside a handler, so a tool that was not registered
 * cannot be reached by any argument.
 *
 * Failure text is deliberately thin: a typed reason and an identity, never a transport message, so a peer's or a
 * provider's error text cannot be used to smuggle content back through a tool result.
 */
class ModelMuxTools(
    private val contextOrNull: SharedContext? = null,
    private val oversight: BreadcrumbOversight? = null,
    private val allowWrite: Boolean = false,
    private val models: ModelPort? = null,
) {
    private val context: SharedContext get() = checkNotNull(contextOrNull) { "no shared context is configured" }

    fun tools(): List<McpTool> = buildList {
        if (contextOrNull != null) {
            add(acquireTool())
            add(readTool())
            add(projectTool())
            if (allowWrite) add(putTool())
        }
        if (models != null) {
            add(modelsTool(models))
            add(chatTool(models))
        }
    }

    // ── helpers ───────────────────────────────────────────────────────

    private fun Map<String, Any?>.str(key: String): String = this[key] as String
    private fun Map<String, Any?>.strOrNull(key: String): String? = this[key] as String?
    private fun Map<String, Any?>.int(key: String, default: Int): Int = (this[key] as Number?)?.toInt() ?: default

    private fun cidOf(text: String, label: String): ContentId =
        try { ContentId(text) } catch (bad: IllegalArgumentException) { throw ToolFailure("$label is not a sha256 identity") }

    private suspend fun <T> guarded(block: suspend () -> T): T =
        try { block() } catch (rejected: ContextBlockException) { throw ToolFailure("${rejected.reason} ${rejected.cid.value}") }

    private fun obj(vararg properties: Pair<String, Map<String, Any?>>, required: List<String> = emptyList()): Map<String, Any?> =
        mapOf("type" to "object", "properties" to properties.toMap(), "required" to required, "additionalProperties" to false)

    private fun string(max: Int, min: Int = 1): Map<String, Any?> = mapOf("type" to "string", "minLength" to min, "maxLength" to max)
    private fun integer(min: Int, max: Int): Map<String, Any?> = mapOf("type" to "integer", "minimum" to min, "maximum" to max)

    private fun counters(c: ContextReadCounters) = mapOf("localReads" to c.localReads, "peerFetches" to c.peerFetches, "peerBytes" to c.peerBytes)

    private fun summary(snapshot: ContextSnapshot): Map<String, Any?> = mapOf(
        "manifest" to snapshot.manifestCid.value,
        "scope" to snapshot.manifest.scope,
        "author" to snapshot.manifest.author,
        "policy" to snapshot.manifest.policyRevision,
        "parents" to (0 until snapshot.manifest.parents.size).map { snapshot.manifest.parents[it].value },
        "documents" to (0 until snapshot.documents.size).map { i ->
            val d = snapshot.documents[i]
            val pinned = snapshot.manifest.documents[i]
            mapOf("name" to d.name, "cid" to d.cid.value, "spine" to pinned.spine.value, "bytes" to d.bytes.size)
        },
    )

    private val manifestArg = string(71, 71)
    private val scopeArg = string(256)

    // ── modelmux_context_put ──────────────────────────────────────────

    private fun putTool() = McpTool(
        name = "modelmux_context_put",
        description = "Publish a new immutable revision of shared context: named text documents under a scope, optionally derived " +
            "from parent revisions. Returns the revision's identity. Nothing existing is overwritten; a successor is a new revision.",
        inputSchema = obj(
            "scope" to scopeArg,
            "author" to string(256),
            "policy" to string(256),
            "documents" to mapOf(
                "type" to "array", "maxItems" to MAX_DOCUMENTS,
                "items" to obj("name" to string(1024), "text" to string(MAX_DOCUMENT_CHARS, min = 0), required = listOf("name", "text")),
            ),
            "parents" to mapOf("type" to "array", "maxItems" to 64, "items" to manifestArg),
            required = listOf("scope", "author", "documents"),
        ),
        readOnly = false,
    ) { args ->
        val scope = args.str("scope")
        if (!context.policy.serves(scope)) throw ToolFailure("scope '$scope' is not served here")
        try {
            var total = 0L
            val docs = ArrayList<ManifestDocument>()
            val blobs = ArrayList<ByteArray>()
            for (row in args["documents"] as List<*>) {
                row as Map<*, *>
                val bytes = (row["text"] as String).encodeToByteArray()
                total += bytes.size
                if (total > MAX_PUT_BYTES) throw ToolFailure("documents exceed $MAX_PUT_BYTES bytes in total")
                docs.add(ManifestDocument.of(row["name"] as String, bytes))
                blobs.add(bytes)
            }
            val parents = (args["parents"] as List<*>?)?.map { cidOf(it as String, "parent") } ?: emptyList()
            val manifest = BreadcrumbContextManifest.of(scope, args.strOrNull("policy") ?: BreadcrumbProjector.POLICY, args.str("author"), parents, docs)
            guarded {
                for (b in blobs) context.admit(b)
                context.admit(manifest.encode())
                context.sync()      // only now may the revision be named to the caller
            }
            McpToolResult(
                mapOf(
                    "manifest" to manifest.cid.value,
                    "scope" to scope,
                    "documents" to docs.map { mapOf("name" to it.name, "cid" to it.content.value, "spine" to it.spine.value) },
                    "parents" to parents.map { it.value },
                ),
            )
        } catch (bad: ManifestFormatException) {
            throw ToolFailure(bad.message ?: "invalid revision")
        }
    }

    // ── modelmux_context_acquire ──────────────────────────────────────

    private fun acquireTool() = McpTool(
        name = "modelmux_context_acquire",
        description = "Resolve a pinned revision of shared context to its verified documents (names, identities, sizes). Every block is " +
            "digest-checked and the closure is complete or the call fails; nothing partial is returned.",
        inputSchema = obj("manifest" to manifestArg, "scope" to scopeArg, required = listOf("manifest", "scope")),
    ) { args ->
        val snapshot = guarded { context.acquire(cidOf(args.str("manifest"), "manifest"), args.str("scope")) }
        McpToolResult(summary(snapshot) + ("counters" to counters(context.counters())))
    }

    // ── modelmux_context_read ─────────────────────────────────────────

    private fun readTool() = McpTool(
        name = "modelmux_context_read",
        description = "Expand a breadcrumb to its source: the exact original text of one document of a pinned revision, by raw line " +
            "numbers (1-based, as shown in breadcrumb headers). Whole lines only; the answer says when it stopped early.",
        inputSchema = obj(
            "manifest" to manifestArg, "scope" to scopeArg, "document" to string(1024),
            "startLine" to integer(1, 10_000_000), "endLine" to integer(1, 10_000_000), "maxChars" to integer(1, 200_000),
            required = listOf("manifest", "scope", "document"),
        ),
    ) { args ->
        val snapshot = guarded { context.acquire(cidOf(args.str("manifest"), "manifest"), args.str("scope")) }
        val name = args.str("document")
        val doc = (0 until snapshot.documents.size).map { snapshot.documents[it] }.firstOrNull { it.name == name }
            ?: throw ToolFailure("no such document in this revision: $name")
        val text = doc.text
        val raw = LineCasSourceRanges.rawLines(text)
        val first = args.int("startLine", 1) - 1
        if (first >= raw.size) throw ToolFailure("startLine is past the end of $name (${raw.size} lines)")
        if (args.int("endLine", first + 1) < first + 1) throw ToolFailure("endLine is before startLine")
        val wanted = minOf(args.int("endLine", first + DEFAULT_READ_LINES), first + MAX_READ_LINES, raw.size)
        val budget = args.int("maxChars", DEFAULT_READ_CHARS)
        var last = first
        // Grow line by line so the answer is always whole lines and never over the character budget.
        for (i in first until wanted) {
            if (text.substring(raw[first].start, raw[i].end).length > budget) break
            last = i
        }
        val fits = text.substring(raw[first].start, raw[last].end).length <= budget
        McpToolResult(
            mapOf(
                "document" to name,
                "documentCid" to doc.cid.value,
                "firstLine" to first + 1,
                "lastLine" to last + 1,
                "totalLines" to raw.size,
                "text" to if (fits) text.substring(raw[first].start, raw[last].end) else "",
                "truncated" to (!fits || last + 1 < minOf(args.int("endLine", Int.MAX_VALUE), raw.size)),
            ),
        )
    }

    // ── modelmux_breadcrumb_project ───────────────────────────────────

    private fun projectTool() = McpTool(
        name = "modelmux_breadcrumb_project",
        description = "Task-conditioned breadcrumbs over a pinned revision: whole original lines ranked for a question by a deterministic " +
            "lexical heuristic (no generative summarizer), within an exact character budget, each with its source location. Protected " +
            "constraints (negations, limits, errors) travel with their matches. oversight=advisory|required asks the local System1 judge " +
            "to prune lines that are not relevant; a judge that is unavailable is reported, never treated as approval.",
        inputSchema = obj(
            "manifest" to manifestArg, "scope" to scopeArg, "question" to string(4_096),
            "maxChars" to integer(0, 100_000), "maxLines" to integer(1, 200), "neighborLines" to integer(0, 5),
            "oversight" to mapOf("type" to "string", "enum" to listOf("off", "advisory", "required")),
            required = listOf("manifest", "scope", "question"),
        ),
    ) { args ->
        val snapshot = guarded { context.acquire(cidOf(args.str("manifest"), "manifest"), args.str("scope")) }
        val question = args.str("question")
        val projection = BreadcrumbProjector.project(
            snapshot, question,
            ProjectionBudget(args.int("maxChars", 2_000), args.int("maxLines", 24), args.int("neighborLines", 1)),
            context.facets,
        )
        val mode = args.strOrNull("oversight") ?: "off"
        val judge: BreadcrumbOversight = when {
            mode == "off" -> NoOversight
            oversight != null -> oversight
            else -> object : BreadcrumbOversight {
                override suspend fun review(question: String, projection: Projection): OversightReceipt = unavailable(projection)
            }
        }
        val receipt = judge.review(question, projection)
        if (mode == "required" && receipt.status != OversightStatus.REVIEWED) {
            throw ToolFailure("oversight is required but the judge is ${receipt.status}")
        }
        val applied = applyOversight(projection, receipt)
        McpToolResult(
            mapOf(
                "policy" to projection.policy,
                "manifest" to snapshot.manifestCid.value,
                "question" to question,
                "text" to applied.render(),
                "charsUsed" to projection.charsUsed,
                "omittedCandidates" to projection.omittedCandidates,
                "droppedConstraints" to projection.droppedConstraints,
                "requiresExpansion" to projection.requiresExpansion,
                "excerpts" to (0 until projection.excerpts.size).map { i ->
                    val e = projection.excerpts[i]
                    mapOf(
                        "document" to e.document,
                        "documentCid" to e.documentCid.value,
                        "firstLine" to e.firstLine + 1,
                        "lastLine" to e.lastLine + 1,
                        "range" to mapOf("start" to e.range.start, "end" to e.range.end),
                        "score" to e.score,
                        "reasons" to e.reasons,
                        "verdict" to receipt.verdicts[i]?.name,
                        "probability" to receipt.probabilities[i],
                        "shown" to (i !in applied.flaggedAt),
                    )
                },
                "oversight" to mapOf(
                    "mode" to mode,
                    "status" to receipt.status.name,
                    "model" to receipt.model,
                    "asked" to receipt.asked,
                    "unreviewed" to receipt.unreviewed,
                    "detail" to receipt.detail,
                ),
            ),
        )
    }

    private fun unavailable(projection: Projection): OversightReceipt {
        val blank = List<Verdict?>(projection.excerpts.size) { null }
        return OversightReceipt(OversightStatus.UNAVAILABLE, null, blank, List<Double?>(blank.size) { null }, "no judge is configured")
    }

    // ── models ────────────────────────────────────────────────────────

    private fun modelsTool(port: ModelPort) = McpTool(
        name = "modelmux_models",
        description = "List the models this host can reach (id, provider, capabilities). Never includes a credential.",
        inputSchema = obj(),
    ) {
        McpToolResult(mapOf("models" to port.models().map { mapOf("id" to it.id, "provider" to it.provider, "capabilities" to it.capabilities.sorted()) }))
    }

    private fun chatTool(port: ModelPort) = McpTool(
        name = "modelmux_chat",
        description = "One chat completion through ModelMux, with the host's own credentials. Spends provider quota.",
        inputSchema = obj(
            "model" to string(256),
            "messages" to mapOf(
                "type" to "array", "maxItems" to 64,
                "items" to obj(
                    "role" to mapOf("type" to "string", "enum" to listOf("system", "user", "assistant")),
                    "content" to string(100_000, min = 0),
                    required = listOf("role", "content"),
                ),
            ),
            "maxTokens" to integer(1, 200_000),
            required = listOf("model", "messages"),
        ),
        readOnly = false,
    ) { args ->
        val messages = (args["messages"] as List<*>).map { m -> m as Map<*, *>; ChatMessage(m["role"] as String, m["content"] as String) }
        if (messages.isEmpty()) throw ToolFailure("messages must not be empty")
        val reply = try {
            port.chat(args.str("model"), messages, (args["maxTokens"] as Number?)?.toInt())
        } catch (failure: ToolFailure) {
            throw failure
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            val status = Regex("HTTP\\s+(\\d{3})").find(failure.message.orEmpty())?.groupValues?.get(1)
            throw ToolFailure("chat failed: ${failure::class.simpleName}" + (status?.let { " (HTTP $it)" } ?: ""))
        }
        McpToolResult(mapOf("model" to args.str("model"), "text" to reply.text, "inputTokens" to reply.inputTokens, "outputTokens" to reply.outputTokens))
    }

    private companion object {
        const val MAX_DOCUMENTS = 256
        const val MAX_DOCUMENT_CHARS = 262_144
        const val MAX_PUT_BYTES = 4L * 1024 * 1024
        const val DEFAULT_READ_LINES = 200
        const val MAX_READ_LINES = 400
        const val DEFAULT_READ_CHARS = 20_000
    }
}
