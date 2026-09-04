package borg.trikeshed.mcp

import borg.trikeshed.kanban.BoardCol
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.parse.json.JsonSupport

/**
 * The MCP projection of the LCNC Kanban asset (KMFSM-004/005 in
 * `docs/marketability-kanban-mcp-audit.md`).
 *
 * The audit's ownership rule is the whole design of this class: MCP is another
 * LENS onto LCNC, never a state owner. Every mutation here is a call into a
 * runner taken from `LcncKanbanExperience.registry()` — the same map that
 * `/api/lcnc/run` and webhook node dispatch resolve — so an MCP write lowers
 * through `BoardIntake` → `BoardStoreElement` and earns exactly the guards a
 * click on the board earns: idempotency dedupe, `expectedRevision` compare-and-
 * set, WIP refusal, dependency-cycle refusal, one WAL record, one CAS receipt.
 *
 * That rule is enforced by the constructor, not by a comment: this handler is
 * handed a runner map and a read port. It is never handed the store, so it has
 * no `intake` channel to reach and CANNOT open a second write path even by
 * mistake. `McpKanbanOwnershipTest` holds the line.
 *
 * Wire shape is JSON-RPC 2.0 — one request object in, one response object out,
 * transport-agnostic (the daemon mounts it at `POST /api/mcp`; a stdio bridge
 * would call the same [handle]). Batching is refused: MCP dropped JSON-RPC
 * batch support in the 2025-06-18 revision.
 */
class LcncKanbanMcp(
    /** The LCNC registry. The ONLY write path — see the class note. */
    private val tools: Map<String, LcncNodeRunner>,
    private val reads: KanbanReadPort,
    private val serverVersion: String = "0.1.0",
) {

    companion object {
        const val SERVER_NAME: String = "oroboros-lcnc-kanban"

        const val URI_SCHEMA: String = "oroboros://lcnc/kanban/schema"
        const val URI_SHEETS: String = "oroboros://lcnc/kanban/sheets"
        const val URI_CARD_PREFIX: String = "oroboros://lcnc/kanban/cards/"
        const val URI_RECEIPT_PREFIX: String = "oroboros://lcnc/kanban/receipts/"

        /** The two mutations the audit opens first. NOT a cap on the LCNC palette —
         *  LCNC users keep every current and future Kanban runner whether or not
         *  MCP projects it. */
        const val TOOL_SUBMIT: String = "kanban.submit"
        const val TOOL_MOVE: String = "kanban.move"

        /**
         * Revisions whose JSON-RPC core this handler speaks. Negotiation echoes a
         * recognized client version; see [negotiateProtocol] for what an
         * unrecognized one gets.
         */
        val SUPPORTED_PROTOCOLS: List<String> = listOf("2026-07-28", "2025-06-18", "2025-03-26")
        val DEFAULT_PROTOCOL: String = SUPPORTED_PROTOCOLS.first()

        /**
         * The revision to answer with when the client's ask is not one we speak
         * and nothing older is available. It is the widely-implemented baseline,
         * not our newest: a client that asked for something we do not know is far
         * likelier to accept this than a revision from the future.
         */
        val BASELINE_PROTOCOL: String = "2025-06-18"

        /**
         * Answer a client's `protocolVersion`.
         *
         * Replying with our NEWEST whenever we did not recognize the ask is what
         * made `claude mcp add` fail: Claude Code asks for 2025-11-25, which is
         * not in [SUPPORTED_PROTOCOLS], and a reply of 2026-07-28 is a revision
         * from beyond its own horizon, so it disconnects with "Server's protocol
         * version is not supported". Negotiate DOWN instead — the newest revision
         * we speak that is no newer than the ask. ISO dates order lexicographically,
         * so a plain string compare is the whole rule.
         */
        fun negotiateProtocol(asked: String?): String {
            if (asked == null) return BASELINE_PROTOCOL
            if (asked in SUPPORTED_PROTOCOLS) return asked
            return SUPPORTED_PROTOCOLS.filter { it <= asked }.maxOrNull() ?: BASELINE_PROTOCOL
        }

        // JSON-RPC 2.0 reserved codes, plus MCP's resource-not-found.
        const val PARSE_ERROR: Int = -32700
        const val INVALID_REQUEST: Int = -32600
        const val METHOD_NOT_FOUND: Int = -32601
        const val INVALID_PARAMS: Int = -32602
        const val INTERNAL_ERROR: Int = -32603
        const val RESOURCE_NOT_FOUND: Int = -32002

        /** Column wire vocabulary, in board order. */
        fun columnWires(): List<String> = BoardCol.rendered.map { it.wire }
    }

    /** A JSON-RPC fault carrying the code the client should see. */
    class McpFault(val code: Int, message: String) : Exception(message)

    // ── entry point ───────────────────────────────────────────────────

    /**
     * One JSON-RPC request in, one response document out. Returns the empty
     * string for a notification (no `id`), which the transport must answer with
     * 202 and no body.
     */
    suspend fun handle(requestText: String): String {
        val parsed = runCatching { JsonSupport.parse(requestText) }.getOrNull()
            ?: return errorJson(null, PARSE_ERROR, "invalid JSON")
        if (parsed is List<*>) {
            return errorJson(null, INVALID_REQUEST, "JSON-RPC batching is not supported (removed in MCP 2025-06-18)")
        }
        val req = parsed as? Map<*, *>
            ?: return errorJson(null, INVALID_REQUEST, "request must be a JSON object")
        val id = req["id"]
        val method = req["method"] as? String
            ?: return errorJson(id, INVALID_REQUEST, "missing 'method'")
        val params = req["params"] as? Map<*, *> ?: emptyMap<Any?, Any?>()

        // A notification is fire-and-forget: `notifications/initialized` and kin
        // get no response document, not even an error for an unknown name.
        if (id == null) return ""

        return try {
            resultJson(id, route(method, params))
        } catch (fault: McpFault) {
            errorJson(id, fault.code, fault.message ?: "error")
        } catch (t: Throwable) {
            errorJson(id, INTERNAL_ERROR, t.message ?: t::class.simpleName ?: "internal error")
        }
    }

    private suspend fun route(method: String, params: Map<*, *>): Map<String, Any?> = when (method) {
        "initialize" -> initialize(params)
        "ping" -> emptyMap()
        "tools/list" -> mapOf("tools" to toolDescriptors())
        "tools/call" -> callTool(params)
        "resources/list" -> mapOf("resources" to resourceDescriptors())
        "resources/templates/list" -> mapOf("resourceTemplates" to templateDescriptors())
        "resources/read" -> readResource(params)
        else -> throw McpFault(METHOD_NOT_FOUND, "unknown method: $method")
    }

    // ── handshake ─────────────────────────────────────────────────────

    private fun initialize(params: Map<*, *>): Map<String, Any?> {
        val asked = params["protocolVersion"] as? String
        return mapOf(
            "protocolVersion" to negotiateProtocol(asked),
            "capabilities" to mapOf(
                "tools" to mapOf("listChanged" to false),
                // No subscriptions yet: a client re-reads the sheets resource after
                // a write rather than being pushed at. Saying so is cheaper than
                // advertising a capability the daemon does not implement.
                "resources" to mapOf("subscribe" to false, "listChanged" to false),
            ),
            "serverInfo" to mapOf(
                "name" to SERVER_NAME,
                "title" to "Oroboros LCNC Kanban",
                "version" to serverVersion,
            ),
            "instructions" to INSTRUCTIONS,
        )
    }

    // ── tools (the write half) ────────────────────────────────────────

    private fun toolDescriptors(): List<Map<String, Any?>> = listOf(
        mapOf(
            "name" to TOOL_SUBMIT,
            "title" to "Add or update a board card",
            "description" to "Put a card on the Oroboros LCNC Kanban board. Give a 'title' for a new " +
                "card, or a 'jobId' to address one that already exists. Returns the card's committed " +
                "revision and sequence plus a receipt reference; a duplicate idempotencyKey is refused, " +
                "not silently re-applied.",
            "inputSchema" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "title" to mapOf("type" to "string", "description" to "Card title. Required unless jobId names an existing card."),
                    "jobId" to mapOf("type" to "string", "description" to "Stable card id. Omit for a new card and one is minted from the title's content hash, so the same title always yields the same id."),
                    "priority" to mapOf("type" to "integer", "description" to "Lower is more urgent. Defaults to 2."),
                    "tags" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "dependencies" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "jobIds this card waits on. A cycle is refused."),
                    "owner" to mapOf("type" to "string"),
                    "idempotencyKey" to mapOf("type" to "string", "description" to "Retry key. Defaults to 'submit#<jobId>', so an accidental repeat is refused as a duplicate rather than doubling the card."),
                ),
                "anyOf" to listOf(
                    mapOf("required" to listOf("title")),
                    mapOf("required" to listOf("jobId")),
                ),
            ),
        ),
        mapOf(
            "name" to TOOL_MOVE,
            "title" to "Move a card to another column",
            "description" to "Move a card between board columns under compare-and-set. Read the card's " +
                "current revision from ${URI_CARD_PREFIX}{jobId} first: a stale expectedRevision is " +
                "refused, and so is a move into a column that is at its WIP limit.",
            "inputSchema" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "jobId" to mapOf("type" to "string"),
                    "toColumn" to mapOf("type" to "string", "enum" to columnWires()),
                    "expectedRevision" to mapOf("type" to "integer", "description" to "The revision you last read. The move is refused if the card has moved on."),
                    "beforeJobId" to mapOf("type" to "string", "description" to "Land immediately before this card in the target column instead of at the bottom."),
                    "idempotencyKey" to mapOf("type" to "string", "description" to "Retry key. Defaults to a value derived from the move itself."),
                ),
                "required" to listOf("jobId", "toColumn", "expectedRevision"),
            ),
        ),
    )

    private suspend fun callTool(params: Map<*, *>): Map<String, Any?> {
        val name = params["name"] as? String ?: throw McpFault(INVALID_PARAMS, "missing 'name'")
        val args = params["arguments"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val command = when (name) {
            TOOL_SUBMIT -> submitCommand(args)
            TOOL_MOVE -> moveCommand(args)
            else -> throw McpFault(INVALID_PARAMS, "unknown tool '$name' — this projection offers $TOOL_SUBMIT and $TOOL_MOVE")
        }
        // THE write path. No store handle exists on this object; the runner is
        // LCNC's, and it lowers into the one durable intake.
        val runner = tools[name]
            ?: throw McpFault(INTERNAL_ERROR, "the LCNC registry mounted here has no '$name' runner")
        val outcome = runCatching {
            runner.run(LcncNode(id = "mcp/$name", type = name), mapOf("command" to command))
        }.getOrElse { failure ->
            // A runner that threw is a tool failure, not a protocol fault: the
            // client sees the reason and can correct its arguments.
            return toolFailure(name, failure.message ?: failure::class.simpleName ?: "runner failed")
        }
        return toolResult(name, outcome)
    }

    private fun submitCommand(args: Map<*, *>): Map<String, Any?> {
        val title = (args["title"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val jobId = (args["jobId"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        if (title == null && jobId == null) {
            throw McpFault(INVALID_PARAMS, "$TOOL_SUBMIT needs 'title' (for a new card) or 'jobId' (for one that exists)")
        }
        return buildMap {
            title?.let { put("title", it) }
            jobId?.let { put("jobId", it) }
            numberOf(args["priority"])?.let { put("priority", it.toInt()) }
            stringsOf(args["tags"])?.let { put("tags", it) }
            stringsOf(args["dependencies"])?.let { put("dependencies", it) }
            (args["owner"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { put("owner", it) }
            (args["idempotencyKey"] as? String)?.takeIf { it.isNotBlank() }?.let { put("idempotencyKey", it) }
        }
    }

    private fun moveCommand(args: Map<*, *>): Map<String, Any?> {
        val jobId = (args["jobId"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw McpFault(INVALID_PARAMS, "$TOOL_MOVE needs 'jobId'")
        val toColumn = (args["toColumn"] as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: throw McpFault(INVALID_PARAMS, "$TOOL_MOVE needs 'toColumn' — one of ${columnWires().joinToString(", ")}")
        if (BoardCol.fromWire(toColumn) == null) {
            throw McpFault(INVALID_PARAMS, "unknown column '$toColumn' — one of ${columnWires().joinToString(", ")}")
        }
        val expected = numberOf(args["expectedRevision"])?.toLong()
            ?: throw McpFault(
                INVALID_PARAMS,
                "$TOOL_MOVE needs 'expectedRevision' — read the card's current revision from $URI_CARD_PREFIX$jobId first",
            )
        return buildMap {
            put("jobId", jobId)
            put("toColumn", toColumn)
            put("expectedRevision", expected)
            // A move retried verbatim carries the same key and is refused as a
            // duplicate instead of moving the card twice; a DIFFERENT move of the
            // same card is a different key. The client never has to invent one.
            put("idempotencyKey", (args["idempotencyKey"] as? String)?.takeIf { it.isNotBlank() }
                ?: "move#$jobId#$expected#$toColumn")
            (args["beforeJobId"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { put("beforeJobId", it) }
        }
    }

    /**
     * Board refusals ride back as `isError` tool results, not JSON-RPC errors —
     * a stale revision is a normal, informative outcome of a healthy call, and
     * the client needs the reason text to retry correctly.
     */
    private fun toolResult(name: String, outcome: Map<String, Any?>): Map<String, Any?> {
        val accepted = outcome["accepted"] == true
        val structured = buildMap<String, Any?> {
            put("accepted", accepted)
            for (field in listOf("jobId", "revision", "sequence", "idempotencyKey", "cid", "reason")) {
                outcome[field]?.let { put(field, it) }
            }
            // The compact board projection travels with the verdict; the FULL
            // sheet family is a resource rather than an echo on every write, so a
            // client pays for the board once and not per mutation.
            (outcome["sheets"] as? Map<*, *>)?.get("boardView")?.let { put("boardView", it) }
            put("sheetsResource", URI_SHEETS)
            (outcome["jobId"] as? String)?.let { put("cardResource", "$URI_CARD_PREFIX$it") }
            numberOf(outcome["sequence"])?.let { put("receiptResource", "$URI_RECEIPT_PREFIX${it.toLong()}") }
        }
        return mapOf(
            "content" to listOf(mapOf("type" to "text", "text" to verdictLine(name, structured))),
            "structuredContent" to structured,
            "isError" to !accepted,
        )
    }

    private fun toolFailure(name: String, reason: String): Map<String, Any?> = mapOf(
        "content" to listOf(mapOf("type" to "text", "text" to "$name failed — $reason")),
        "structuredContent" to mapOf("accepted" to false, "reason" to reason),
        "isError" to true,
    )

    private fun verdictLine(name: String, structured: Map<String, Any?>): String =
        if (structured["accepted"] == true) {
            buildString {
                append(name).append(" accepted — ").append(structured["jobId"] ?: "?")
                structured["revision"]?.let { append(" at revision ").append(numberText(it)) }
                structured["sequence"]?.let { append(", sequence ").append(numberText(it)) }
            }
        } else {
            "$name refused — ${structured["reason"] ?: "no reason given"}"
        }

    // ── resources (the read half) ─────────────────────────────────────

    private fun resourceDescriptors(): List<Map<String, Any?>> = listOf(
        mapOf(
            "uri" to URI_SCHEMA,
            "name" to "kanban-schema",
            "title" to "Board columns, guards, and transition policy",
            "mimeType" to "application/json",
            "description" to "The column vocabulary with WIP limits, the transition policy the store " +
                "actually enforces, the guards a write must pass, and the card field schema.",
        ),
        mapOf(
            "uri" to URI_SHEETS,
            "name" to "kanban-sheets",
            "title" to "Live board and concentric sheets",
            "mimeType" to "application/json",
            "description" to "The kanban.activeSheets family — board, byStatus, byPriority, and the " +
                "orchestration graph — freshly projected from the durable store.",
        ),
    )

    private fun templateDescriptors(): List<Map<String, Any?>> = listOf(
        mapOf(
            "uriTemplate" to "$URI_CARD_PREFIX{jobId}",
            "name" to "kanban-card",
            "title" to "One card, in full",
            "mimeType" to "application/json",
            "description" to "A single card including the tags, dependencies, and owner that the " +
                "summary board projection leaves out — and the revision a move must quote.",
        ),
        mapOf(
            "uriTemplate" to "$URI_RECEIPT_PREFIX{sequence}",
            "name" to "kanban-receipt",
            "title" to "The receipt for one committed change",
            "mimeType" to "application/json",
            "description" to "What the store committed at this sequence: the card, the column it left " +
                "and entered, the command, and the CAS id of the raw command that is the durable truth.",
        ),
    )

    private fun readResource(params: Map<*, *>): Map<String, Any?> {
        val uri = params["uri"] as? String ?: throw McpFault(INVALID_PARAMS, "missing 'uri'")
        val body: Map<String, Any?> = when {
            uri == URI_SCHEMA -> schema()
            uri == URI_SHEETS -> reads.sheets() + mapOf("watermark" to reads.watermark())
            uri.startsWith(URI_CARD_PREFIX) -> {
                val jobId = uri.removePrefix(URI_CARD_PREFIX)
                reads.card(jobId) ?: throw McpFault(RESOURCE_NOT_FOUND, "no card '$jobId' on the board")
            }
            uri.startsWith(URI_RECEIPT_PREFIX) -> {
                val raw = uri.removePrefix(URI_RECEIPT_PREFIX)
                val sequence = raw.toLongOrNull()
                    ?: throw McpFault(INVALID_PARAMS, "receipt sequence must be a number, got '$raw'")
                reads.receipt(sequence)
                    ?: throw McpFault(RESOURCE_NOT_FOUND, "no retained receipt at sequence $sequence")
            }
            else -> throw McpFault(RESOURCE_NOT_FOUND, "unknown resource: $uri")
        }
        return mapOf(
            "contents" to listOf(
                mapOf("uri" to uri, "mimeType" to "application/json", "text" to JsonSupport.stringify(body)),
            ),
        )
    }

    /**
     * The transition policy, published as the one the store ENFORCES rather than
     * an aspirational happy path. Today a move may target any recognized column;
     * what constrains it is the guard list below, not a per-column allow table.
     * Saying "open" plainly is the honest answer to KMFSM-002 — and when a
     * narrower policy is chosen it changes here, inside the same LCNC boundary,
     * for HTTP, UI, and MCP callers at once.
     */
    fun schema(): Map<String, Any?> = mapOf(
        "columns" to BoardCol.rendered.map {
            mapOf("id" to it.wire, "order" to it.order, "wipLimit" to it.wipLimit)
        },
        "transitionPolicy" to mapOf(
            "kind" to "open",
            "note" to "Any recognized column is a legal target; movement is constrained by the guards, " +
                "not by a per-column allow table.",
            "allowedTargets" to BoardCol.rendered.associate { it.wire to columnWires() },
        ),
        "guards" to listOf(
            mapOf(
                "name" to "idempotency",
                "applies" to "every write",
                "effect" to "A repeated idempotencyKey is refused as a duplicate rather than applied twice.",
            ),
            mapOf(
                "name" to "expectedRevision",
                "applies" to TOOL_MOVE,
                "effect" to "Compare-and-set on the card's revision; a stale value is refused.",
            ),
            mapOf(
                "name" to "wipLimit",
                "applies" to TOOL_MOVE,
                "effect" to "A move into a column at its WIP limit is refused. Only 'running' is limited (3).",
            ),
            mapOf(
                "name" to "dependencyCycle",
                "applies" to TOOL_SUBMIT,
                "effect" to "A submit whose dependencies would close a cycle is refused at the door.",
            ),
        ),
        "tools" to listOf(TOOL_SUBMIT, TOOL_MOVE),
        "cardFields" to listOf(
            mapOf("name" to "id", "type" to "string", "note" to "stable jobId"),
            mapOf("name" to "title", "type" to "string"),
            mapOf("name" to "status", "type" to "string", "note" to "column wire id"),
            mapOf("name" to "priority", "type" to "integer", "note" to "lower is more urgent"),
            mapOf("name" to "order", "type" to "integer", "note" to "position within the column"),
            mapOf("name" to "revision", "type" to "integer", "note" to "quote this in a move"),
            mapOf("name" to "lastSequence", "type" to "integer", "note" to "the receipt to read"),
            mapOf("name" to "lastMoveMs", "type" to "integer"),
            mapOf("name" to "dependencies", "type" to "string[]"),
            mapOf("name" to "tags", "type" to "string[]"),
            mapOf("name" to "owner", "type" to "string"),
        ),
        "watermark" to reads.watermark(),
    )

    // ── JSON-RPC envelope ─────────────────────────────────────────────

    private fun resultJson(id: Any?, result: Map<String, Any?>): String =
        """{"jsonrpc":"2.0","id":${idJson(id)},"result":${JsonSupport.stringify(result)}}"""

    private fun errorJson(id: Any?, code: Int, message: String): String =
        """{"jsonrpc":"2.0","id":${idJson(id)},"error":${
            JsonSupport.stringify(mapOf("code" to code, "message" to message))
        }}"""

    /** An integral id must go back out as `7`, not `7.0` — clients match on it. */
    private fun idJson(id: Any?): String = when (id) {
        null -> "null"
        is Number -> numberText(id)
        else -> JsonSupport.stringify(id)
    }

    private fun numberText(value: Any?): String = when (value) {
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Float -> numberText(value.toDouble())
        else -> value.toString()
    }

    private fun numberOf(value: Any?): Number? = when (value) {
        is Number -> value
        is String -> value.toDoubleOrNull()
        else -> null
    }

    private fun stringsOf(value: Any?): List<String>? = when (value) {
        is Iterable<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        is String -> value.trim().takeIf { it.isNotEmpty() }?.let { listOf(it) }
        else -> null
    }
}

/** Prose the client shows its model before the first call. */
private val INSTRUCTIONS: String = """
    This is the live Kanban board of an Oroboros daemon. Cards are durable: every
    change is written to a WAL and content-addressed, and survives a restart.

    Read ${LcncKanbanMcp.URI_SHEETS} for the board. Before moving a card, read
    ${LcncKanbanMcp.URI_CARD_PREFIX}{jobId} and quote its revision as
    expectedRevision — the move is refused if someone changed the card first.
    ${LcncKanbanMcp.URI_SCHEMA} carries the columns, their WIP limits, and the
    guards a write must pass.
""".trimIndent()

/**
 * The read half of the projection — deliberately four narrow projections and no
 * intake channel, so the ownership rule in [LcncKanbanMcp] is a type, not a
 * convention.
 */
interface KanbanReadPort {
    /** The `kanban.activeSheets` family: board, byStatus, byPriority, orchestration. */
    fun sheets(): Map<String, Any?>

    /** One card, carrying the tags/dependencies/owner the board summary drops. */
    fun card(jobId: String): Map<String, Any?>?

    /** The receipt committed at this sequence, or null if it is outside retention. */
    fun receipt(sequence: Long): Map<String, Any?>?

    /** Highest committed sequence — the sheets' watermark. */
    fun watermark(): Long
}
