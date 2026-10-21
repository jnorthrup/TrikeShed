package modelmux

import borg.trikeshed.htx.*
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.relaxfactory.CouchHttpSurface
import kotlinx.coroutines.*
import modelmux.acp.*

/**
 * Opt in with `breadcrumb_context` on a completion. Only tool-result content is
 * projected; instructions, tool call IDs and assistant envelopes retain their roles.
 * Execute the advertised breadcrumb tool with POST /v1/breadcrumbs:
 * {"context":"session-id","id":"tool-call-id","presence":0.5}.
 * The returned JSON is the tool result to append to the next completion.
 * GET /v1/breadcrumbs lists contexts; ?context=session-id inspects current decay,
 * while &view=submitted returns the immutable tool input from the latest request,
 * &view=routing returns completed provider/key routing receipts and &view=horizon
 * returns the context cleaner (overflow band, error rate, per-record presence and gain).
 * Inspection never refreshes recency; DELETE releases the session.
 * A configured BreadcrumbStore retains originals and receipts across restarts.
 * Text-part arrays are retained as exact JSON and projected into string tool content.
 */
class BreadcrumbModelHost(val mux: ModelMux, val contexts: BreadcrumbElement) {
    private val host = OpenAiModelHost(mux,
        completion = { model, request -> completion(model, request) },
        routes = { route(it) },
    )

    suspend fun handle(request: HtxRequest): HtxResponse? = host.handle(request)

    /** Explicit wire opt-in projects evidence; a host context alone only observes unchanged tool input. */
    suspend fun completion(
        model: String,
        request: ConfixDoc,
        contextId: String? = null,
        timeoutMs: Long = HtxRequest.DEFAULT_TIMEOUT_MS,
        assessmentId: String? = null,
    ): Result<ConfixDoc> {
        val optedIn = request.getAt("breadcrumb_context") != null
        val id = if (optedIn) {
            val value = request.value("breadcrumb_context")
            require(value is String) { "breadcrumb_context must be a string" }
            value
        } else contextId
        if (!optedIn) return withContext(contexts) {
            mux.completion(model, request, assessmentId = assessmentId, contextId = id, timeoutMs = timeoutMs)
        }
        requireNotNull(id)
        val emissions = mutableSetOf<BreadcrumbId>()
        var reserved: BreadcrumbContext? = null
        var successful = false
        try {
            currentCoroutineContext().ensureActive()
            val outgoing = withContext(NonCancellable) {
                contexts.transact(id) {
                    val projected = project(request, it, emissions)
                    it.reserveEmissions(emissions)
                    it.clean(tokens(projected))
                    projected j it
                }.also { reserved = it.b }.a
            }
            return withContext(contexts + BreadcrumbAdmission(contexts, id, requireNotNull(reserved))) {
                mux.completion(model, outgoing, assessmentId = assessmentId, contextId = id, timeoutMs = timeoutMs)
            }.also { successful = it.isSuccess }
        } finally {
            val admitted = reserved
            if (admitted != null) withContext(NonCancellable) {
                // A caller may delete this session while the provider is answering.
                try {
                    contexts.transact(id, create = false) {
                        if (it === admitted) it.completeEmissions(emissions, successful)
                    }
                } catch (_: BreadcrumbContextNotFoundException) { /* Session was released. */ }
                catch (failure: Throwable) {
                    // A failed durable completion rolls back the reservation; release it without marking an emission.
                    try {
                        contexts.transact(id, create = false) {
                            if (it === admitted) it.completeEmissions(emissions, successful = false)
                        }
                    } catch (_: BreadcrumbContextNotFoundException) { /* Session was released. */ }
                    catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            }
        }
    }

    suspend fun route(request: HtxRequest): HtxResponse? {
        if (request.target.requestPath.substringBefore('?').trimEnd('/') != "/v1/breadcrumbs") return null
        return try {
            when (request.method) {
                HtxMethod.GET -> {
                    val query = CouchHttpSurface.parseQuery(request.target.requestPath.substringAfter('?', ""))
                    val id = query["context"]
                    val result = if (id == null) jsonOf(mapOf("contexts" to contexts.snapshot().view))
                    else contexts.transact(id, create = false) {
                        when (query["view"]) {
                            null, "current" -> it.snapshot().breadcrumbJson()
                            "submitted" -> it.submittedJson(id)
                            "horizon" -> it.horizonJson(id)
                            "routing" -> jsonOf(mapOf("context" to id, "calls" to it.receipts().view.map { call -> call.toWire() }))
                            "index" -> it.index.search(query["q"] ?: "", BreadcrumbBudget(16_384), 32)
                            else -> throw IllegalArgumentException("unknown breadcrumb view")
                        }
                    }
                    HtxResponse(200, ByteSeries(result.encodeToByteArray()), host.jsonHeaders())
                }
                HtxMethod.POST -> {
                    val parsed = try { reifyStrict(request.body.toArray().decodeToString(throwOnInvalidSequence = true)) }
                        catch (_: Exception) { throw IllegalArgumentException("Invalid JSON") }
                    val req = parsed as? Map<*, *>
                        ?: throw IllegalArgumentException("JSON object required")
                    val context = req["context"] as? String ?: throw IllegalArgumentException("context is required")
                    val tool = req["tool"] as? String
                    if (tool == TOOL_SEARCH || tool == TOOL_RECALL) {
                        val result = contexts.transact(context, create = false) {
                            val ceiling = it.submission?.model?.let(mux::contextWindow)?.takeIf { length -> length > 0 } ?: 2048
                            val budget = BreadcrumbBudget(ceiling,
                                currentTokens = it.lastTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                            BreadcrumbToolDispatcher(it, budget).dispatch(tool, req.entries
                                .filter { row -> row.key != "context" && row.key != "tool" }
                                .associate { row -> row.key.toString() to row.value.toString() })!!
                        }
                        return HtxResponse(200, ByteSeries(result.encodeToByteArray()), host.jsonHeaders())
                    }
                    val id = BreadcrumbId(req["id"] as? String ?: throw IllegalArgumentException("id is required"))
                    val presence = BreadcrumbPresence((req["presence"] as? Number)?.toDouble()
                        ?: throw IllegalArgumentException("presence must be a number from 0 to 1"))
                    val result = contexts.transact(context, create = false) {
                        require(it.resize(id, presence)) { "breadcrumb not found" }
                        it.nextToolResult().breadcrumbJson()
                    }
                    HtxResponse(200, ByteSeries(result.encodeToByteArray()), host.jsonHeaders())
                }
                HtxMethod.DELETE -> {
                    val query = CouchHttpSurface.parseQuery(request.target.requestPath.substringAfter('?', ""))
                    contexts.remove(requireNotNull(query["context"]) { "context is required" })
                    host.json(200, mapOf("deleted" to true))
                }
                else -> host.error(405, "method_not_allowed", "Use GET, POST or DELETE")
            }
        } catch (invalid: IllegalArgumentException) {
            host.error(400, "invalid_breadcrumb", invalid.message ?: "Invalid breadcrumb request")
        }
    }

    companion object {
        /** onegood1 Agent._estimate_token_count: message text, text parts and tool-call arguments at four characters per token. */
        fun tokens(request: ConfixDoc): Long {
            val src = request.src
            var chars = 0L
            for (row in request.getAt("messages")?.kids?.view ?: return 0L) {
                val content = row.step("content", src)
                when (content?.tag) {
                    IOMemento.IoString -> chars += (content.reify(src) as String).length
                    IOMemento.IoArray -> for (part in content.kids.view) chars += if (part.tag == IOMemento.IoObject)
                        (part.step("text", src)?.reify(src) as? String)?.length ?: 0 else part.close - part.open + 1
                    else -> {}
                }
                row.step("tool_calls", src)?.kids?.view?.forEach { call ->
                    chars += (call.getAt("function", "arguments", src = src)?.reify(src) as? String)?.length ?: 0
                }
            }
            return chars / BreadcrumbOverflow.CHARS_PER_TOKEN
        }

        /** Preserve only exact tool content and identity; inspection never retains prompt instructions. */
        fun submission(model: String, request: ConfixDoc, at: BreadcrumbTimestamp): BreadcrumbSubmission {
            val messages = request.getAt("messages")?.kids ?: emptySeriesOf<RowVec>()
            val tools = messages.view.filter { it.step("role", request.src)?.reify(request.src) == "tool" }
            val rows = mutableListOf<String>()
            var size = 0L
            var exact = tools.size <= 1000
            if (exact) for (row in tools) {
                val id = row.step("tool_call_id", request.src)
                val content = row.step("content", request.src)
                val length = (id?.let { it.close - it.open + 1 } ?: 4).toLong() +
                    (content?.let { it.close - it.open + 1 } ?: 4)
                size += length
                // A retained UTF-16 unit can occupy six characters in its JSON string token.
                if (size > 500_000L * 6 + 1000L * 256) { exact = false; break }
                rows.add("{\"role\":\"tool\",\"tool_call_id\":" +
                    (id?.let { raw(request, it) } ?: "null") + ",\"content\":" +
                    (content?.let { raw(request, it) } ?: "null") + "}")
            }
            return BreadcrumbSubmission(model, at, if (exact) rows.toSeries() else emptySeriesOf(), tools.size, exact)
        }

        /** Repeated history must never refresh age or overwrite the retained original. */
        fun project(
            request: ConfixDoc,
            context: BreadcrumbContext,
            emissions: MutableSet<BreadcrumbId> = mutableSetOf(),
        ): ConfixDoc {
            val at = BreadcrumbTimestamp(context.clock())
            val root = requireNotNull(request.root) { "JSON object required" }
            require(root.tag == IOMemento.IoObject) { "JSON object required" }
            val messages = requireNotNull(request.getAt("messages")) { "messages are required" }
            require(messages.tag == IOMemento.IoArray) { "messages must be an array" }
            val tools = request.getAt("tools")
            require(tools == null || tools.tag == IOMemento.IoArray) { "tools must be an array" }
            require(tools == null || tools.kids.view.none {
                it.getAt("function", "name", src = request.src)?.reify(request.src) in BREADCRUMB_TOOLS_NAMES
            }) { "breadcrumb, recall and search tools are reserved when breadcrumb_context is supplied" }

            val records = mutableMapOf<BreadcrumbId, String>()
            val retrievals = mutableMapOf<BreadcrumbId, BreadcrumbId?>()
            for (row in messages.kids.view) {
                val calls = row.step("tool_calls", request.src) ?: continue
                if (row.step("role", request.src)?.reify(request.src) != "assistant") continue
                for (call in calls.kids.view) {
                    if (call.getAt("function", "name", src = request.src)?.reify(request.src) in BREADCRUMB_TOOLS_NAMES) {
                        val id = call.step("id", request.src)?.reify(request.src) as? String
                            ?: throw IllegalArgumentException("breadcrumb tool call id required")
                        val args = call.getAt("function", "arguments", src = request.src)?.reify(request.src) as? String
                        val target = args?.let { runCatching { reifyStrict(it) as? Map<*, *> }.getOrNull() }
                            ?.get("id") as? String
                        retrievals[BreadcrumbId(id)] = target?.takeIf { it.isNotBlank() && it.length <= 256 }?.let(::BreadcrumbId)
                    }
                }
            }
            for (row in messages.kids.view) {
                require(row.tag == IOMemento.IoObject) { "message must be an object" }
                objectProjection(request, row, emptyMap()) // validate field uniqueness before admitting evidence
                if (row.step("role", request.src)?.reify(request.src) != "tool") continue
                val content = toolContent(request, row)
                val id = BreadcrumbId(row.step("tool_call_id", request.src)?.reify(request.src) as? String
                    ?: throw IllegalArgumentException("tool_call_id is required"))
                if (id in retrievals) continue
                require(records.put(id, content).let { it == null || it == content }) { "conflicting tool results" }
                require(context.lookup(id).let { it == null || it.content == content }) { "tool result differs from retained original" }
            }
            val retained = context.snapshot(at)
            val additional = records.filterKeys { context.lookup(it) == null }
            require(retained.size + additional.size <= 1000) { "breadcrumb record capacity reached" }
            val existingChars = retained.view.sumOf { context.lookup(it.id)!!.content.length.toLong() }
            require(existingChars + additional.values.sumOf { it.length.toLong() } <= 500_000) { "breadcrumb content capacity reached" }
            records.forEach { (id, content) -> context.put(id, "tool:${id.value}", content, at) }
            // Pending resizes belong to the next tool result, not a model request.
            val projections = context.snapshot(at).view.associateBy { it.id }
            val projectedMessages = messages.kids.view.joinToString(",", "[", "]") { row ->
                if (row.step("role", request.src)?.reify(request.src) == "tool") {
                    val id = BreadcrumbId(row.step("tool_call_id", request.src)!!.reify(request.src) as String)
                    if (id in retrievals && !context.emitted(id) && emissions.add(id)) raw(request, row)
                    else objectProjection(request, row, mapOf("content" to jsonOf(
                        if (id in retrievals) {
                            retrievals[id]?.let { context.lookup(it)?.project(BreadcrumbPresence(0.0)) }?.render()
                                ?: jsonOf(mapOf("skill" to BREADCRUMB_SKILL, "retrieval" to id.value, "presence" to 0))
                        } else projections.getValue(id).render())))
                } else raw(request, row)
            }
            val projectedTools = buildString {
                append('[')
                tools?.kids?.view?.forEach { append(raw(request, it)).append(',') }
                append(BREADCRUMB_TOOL_DEFINITIONS).append(']')
            }
            return confixDoc(objectProjection(request, root,
                mapOf("messages" to projectedMessages, "tools" to projectedTools), setOf("breadcrumb_context")))
        }

        fun raw(doc: ConfixDoc, row: RowVec): String =
            ByteArray(row.close - row.open + 1) { doc.src[row.open + it] }.decodeToString()

        fun toolContent(doc: ConfixDoc, row: RowVec): String {
            val content = requireNotNull(row.step("content", doc.src)) { "tool content is required" }
            return when (content.tag) {
                IOMemento.IoString -> content.reify(doc.src) as String
                IOMemento.IoArray -> {
                    require(content.kids.view.all {
                        it.tag == IOMemento.IoObject && it.step("type", doc.src)?.reify(doc.src) == "text" &&
                            it.step("text", doc.src)?.tag == IOMemento.IoString
                    }) { "tool content arrays must contain text parts" }
                    raw(doc, content)
                }
                else -> throw IllegalArgumentException("tool content must be a string or text-part array")
            }
        }

        /** Copy all untouched JSON spans, including opaque provider extensions and exact integers. */
        fun objectProjection(doc: ConfixDoc, row: RowVec, replacements: Map<String, String>, omitted: Set<String> = emptySet()): String {
            val seen = mutableSetOf<String>()
            val fields = row.kids
            return buildString {
                append('{')
                var separator = ""
                for (index in 0 until fields.size step 2) {
                    val name = fields[index].reify(doc.src) as? String ?: throw IllegalArgumentException("invalid object key")
                    require(seen.add(name)) { "duplicate field: $name" }
                    if (name in omitted) continue
                    append(separator).append(jsonOf(name)).append(':')
                    append(replacements[name] ?: raw(doc, fields[index + 1]))
                    separator = ","
                }
                for ((name, value) in replacements) if (name !in seen) {
                    append(separator).append(jsonOf(name)).append(':').append(value)
                    separator = ","
                }
                append('}')
            }
        }
    }
}
