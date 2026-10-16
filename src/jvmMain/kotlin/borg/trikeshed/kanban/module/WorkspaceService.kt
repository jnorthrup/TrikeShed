package borg.trikeshed.kanban.module

import borg.trikeshed.graal.subvm.VmLanguages
import borg.trikeshed.graal.subvm.CamelSource
import borg.trikeshed.job.ContentId
import borg.trikeshed.couch.isam.descriptor
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.parse.*
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.vm.VmHost
import borg.trikeshed.vm.VmLanguageCapability
import borg.trikeshed.vm.VmLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Read-only inspection plus an explicit PUT for bounded Couch-to-dense indexing. No graph execution. */
class WorkspaceService(
    val context: ModuleContext,
    val vocabulary: () -> Map<String, LcncPortContract> = { LcncContracts.all().associateBy { it.type } },
) {
    suspend fun catalog(): Map<String, Any?> = mapOf(
        "facets" to WorkspaceFacets.catalog().view.toList(),
        "languages" to capabilities().view.map(::language),
        "runners" to context.lcncRunners.keys.sorted(),
        "blackboardRevision" to context.blackboard.snapshot().revision,
        "storage" to storageCatalog(),
    )

    suspend fun capabilities(): Series<VmLanguageCapability> {
        val runner = context.lcncRunners[SubVmLegos.POLYGLOT]
        val binding = runner as? LcncServiceBinding
        if (binding == null || binding.requiredKeys.view.none { it === VmHostKey }) {
            val note = if (runner == null) "vm.polyglot is not bound in this workspace."
                else "The registered vm.polyglot runner does not declare a VM host binding."
            return VmLanguage.all α { VmLanguageCapability(it, false, false, note) }
        }
        // Match LcncRunService: caller > mux > module > the runner's borrowed default.
        return withContext(context.scope.coroutineContext.minusKey(Job) +
            context.muxContext.minusKey(Job) + currentCoroutineContext()) {
            binding.withServices {
                VmLanguages.capabilities(currentCoroutineContext()[VmHostKey]?.value ?: VmHost.NONE)
            }
        }
    }

    fun language(capability: VmLanguageCapability): Map<String, Any?> {
        val native = if (capability.language in setOf(VmLanguage.JAVA, VmLanguage.GROOVY) &&
            SubVmLegos.CAMEL in context.lcncRunners) {
            val probe = runCatching { CamelSource.checkAvailable(capability.language) }
            val program = LcncWorkspacePresets.camelNative(capability.language)
            mapOf("nativeConstruction" to mapOf("available" to probe.isSuccess,
                "note" to (probe.exceptionOrNull()?.message ?: "Native ${capability.language.label} RouteBuilder. OWN trust; no statement, call-count or wall-clock enforcement. Loading does not execute."),
                "name" to program.name, "document" to reify(LcncProgramConfix.toJson(program))))
        } else emptyMap()
        val descriptor = capability.toMap() + native
        if (!capability.available || SubVmLegos.POLYGLOT !in context.lcncRunners || SubVmLegos.CAMEL !in context.lcncRunners)
            return descriptor
        val program = LcncWorkspacePresets.polyglot(capability.language, "${LcncWorkspacePresets.POLYGLOT}-${capability.language.id}")
        val route = LcncWorkspacePresets.camel(capability.language, "${LcncWorkspacePresets.CAMEL_AUTHORING}-${capability.language.id}")
        val document = LcncWorkspacePresets.routing(capability.language, name = "${LcncWorkspacePresets.DOCUMENT_ROUTING}-${capability.language.id}")
        return descriptor + ("construction" to mapOf("name" to program.name,
            "document" to reify(LcncProgramConfix.toJson(program)))) +
            ("routeConstruction" to mapOf("name" to route.name, "document" to reify(LcncProgramConfix.toJson(route)))) +
            ("documentRoutingConstruction" to mapOf("name" to document.name, "document" to reify(LcncProgramConfix.toJson(document))))
    }

    suspend fun storageCatalog(): Map<String, Any?> = try {
        context.confixIsam?.catalog() ?: mapOf("available" to false)
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { mapOf("available" to false, "error" to (e.message ?: "Dense storage unavailable")) }

    fun inspect(request: Map<*, *>): Map<String, Any?> {
        WorkspaceFacets.valueBudget().violation(request)?.let { throw IllegalArgumentException(it) }
        val raw = requireNotNull(request["document"] as? Map<*, *>) { "document must be an object" }
        require(raw["nodes"] is List<*> && raw["wires"] is List<*>) { "document needs nodes and wires arrays" }
        fun nodes(values: List<*>) {
            for (value in values) {
                val node = requireNotNull(value as? Map<*, *>) { "Each node must be an object" }
                require(node["id"] is String && node["type"] is String) { "Node id and type must be strings" }
                node["children"]?.let {
                    require(it is List<*>) { "Node children must be an array" }
                    nodes(it)
                }
            }
        }
        nodes(raw["nodes"] as List<*>)
        val source = jsonOf(raw)
        val name = (request["name"] as? String)?.takeIf { it.isNotBlank() } ?: "canvas"
        val program = LcncProgramConfix.fromJson(name, source)
        val path = path(request)
        val offset = request["offset"]?.let { integer(it, "offset") } ?: 0
        val limit = request["limit"]?.let { integer(it, "limit") } ?: 32
        val board = context.blackboard.snapshot()
        val contracts = vocabulary()
        val compiled = LcncContracts.all().map { it.type }.toSet()
        val bindings = LcncWrappers.bindings(contracts.values, context.lcncRunners,
            { it::class.toString() }, contracts.filterKeys { it !in compiled }).associateBy { it.type }
        fun describe(nodes: Series<LcncNode>): List<Map<String, Any?>> = nodes.view.flatMap { node ->
            // Inline/named bodies take precedence over registry dispatch in LcncRunner.
            val body = node.type != LcncContracts.SCOPE_IN && node.type != LcncContracts.SCOPE_OUT &&
                (node.children.size > 0 || node.subprogram != null ||
                    (node.type == LcncContracts.SCOPE && !node.params["program"].isNullOrBlank()))
            val binding = if (body) LcncBinding(node.type, LcncBindingKind.INTERPRETER, "LcncRunner") else bindings[node.type]
            val services = if (binding?.kind == LcncBindingKind.KOTLIN)
                context.lcncRunners[node.type] as? LcncServiceBinding else null
            listOf(mapOf("id" to node.id, "type" to node.type,
                "binding" to (binding?.kind?.name ?: "UNDECLARED"),
                "context" to LcncContextContract.of(if (body) LcncContracts.SCOPE else node.type,
                    binding?.kind == LcncBindingKind.COMPOSITE).toMap(),
                "requiredKeys" to services?.requiredKeys?.view?.map(CcekNodes::keyName).orEmpty(),
                "providedKeys" to services?.providedKeys?.view?.map(CcekNodes::keyName).orEmpty())) + describe(node.children)
        }
        val programCid = LcncBlackboard.cidOf(program)
        val programKey = LcncBlackboard.programKey(name)
        val comparisons = mutableMapOf<String, String?>()
        val comparisonLimit = 32
        var comparisonLimited = false
        fun relation(cid: String?): String? {
            if (cid == programCid) return "identical"
            if (cid == null) return null
            if (cid in comparisons) return comparisons[cid]
            if (comparisons.size >= comparisonLimit) { comparisonLimited = true; return null }
            val result = try { compare(program, cid)["relation"] as? String }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null } // An unavailable/corrupt snapshot cannot establish a match.
            comparisons[cid] = result
            return result
        }
        val runs = LcncRunHead.rows(board.values).asSequence()
            .sortedWith(compareByDescending<LcncRunHead.RunRow> { it.startedAtMs }.thenByDescending { it.sequence })
            // Inline labels are not membership. Compare frozen definitions, never mutable named heads.
            .filter { it.programKey == programKey || (it.programKey == null && relation(it.programCid) in setOf("identical", "presentation")) }
            .take(8).map { row -> mapOf(
                "runId" to row.runId, "programCid" to row.programCid, "receiptCid" to row.receiptCid,
                "programKey" to row.programKey,
                "programRelation" to if (row.programCid == programCid) "identical" else comparisons[row.programCid],
                "status" to row.status, "sameVersion" to (row.programCid == programCid),
            ) }.toList()
        return mapOf(
            "name" to name, "programCid" to programCid,
            "comparison" to request["compareProgramCid"]?.let { compare(program, it) },
            "confix" to WorkspaceFacets.inspect(source, path, offset, limit, cursorQuery(request["cursor"])),
            "violations" to LcncTypeCheck.check(program, contracts).map { it.render() },
            "bindings" to describe(program.nodes),
            "blackboardRevision" to board.revision, "runs" to runs,
            "runComparisonLimit" to comparisonLimit, "runComparisonLimited" to comparisonLimited,
        )
    }

    /** Read the immutable program, never a mutable named head or client-provided definition. */
    fun compare(program: LcncProgram, reference: Any): Map<String, Any?> {
        val cid = ContentId(requireNotNull(reference as? String) { "compareProgramCid must be a content ID" })
        val bytes = context.casStore.get(cid)
            ?: return mapOf("programCid" to cid.value, "relation" to "unavailable", "detail" to "Frozen program is unavailable")
        require(bytes.size <= WorkspaceFacets.MAX_BYTES) { "Frozen program exceeds workspace byte limit" }
        require(ContentId.of(bytes) == cid) { "Frozen program content identity mismatch" }
        val raw = requireNotNull(reifyStrict(bytes.decodeToString()) as? Map<*, *>) { "Frozen program must be an object" }
        require(raw["nodes"] is List<*> && raw["wires"] is List<*>) { "Content is not a frozen program" }
        WorkspaceFacets.valueBudget().violation(raw)?.let { throw IllegalArgumentException(it) }
        val frozen = LcncProgramConfix.fromJson(program.name, jsonOf(raw))
        // Refuse lossy parsing or a noncanonical blob before making a definition claim.
        require(LcncBlackboard.cidOf(frozen) == cid.value) { "Content is not a canonical frozen program" }
        val relation = when {
            LcncBlackboard.cidOf(program) == cid.value -> "identical"
            LcncProgramConfix.sameDefinition(program, frozen) -> "presentation"
            else -> "different"
        }
        return mapOf("programCid" to cid.value, "relation" to relation)
    }

    fun path(request: Map<*, *>): Series<Any> {
        val steps = request["path"] ?: emptyList<Any>()
        require(steps is List<*>) { "path must be an array" }
        return steps.map { step -> when (step) {
            is String -> step
            is Number -> integer(step, "path index")
            else -> throw IllegalArgumentException("Path steps must be strings or indices")
        } }.toSeries()
    }

    suspend fun stored(request: Map<*, *>): HttpResponse {
        require("document" !in request) { "Choose the canvas or a stored revision, not both" }
        val source = requireNotNull(request["source"] as? Map<*, *>) { "source must be an object" }
        if (source["kind"] == "cas") return content(request, source)
        require(source["kind"] == "couch") { "Unknown workspace source" }
        val tree = source["facet"] == "file-tree"
        require(source["facet"] == null || tree) { "Unknown stored facet" }
        if (tree) require(source["id"] is String && source["rev"] is String) { "File-tree inspection requires an exact Couch revision" }
        val storage = context.confixIsam ?: return HttpResponse(503, jsonOf(mapOf("error" to "Dense storage unavailable")))
        if (source["id"] == null) return HttpResponse(200, jsonOf(mapOf("storage" to storage.catalog(
            request["offset"]?.let { integer(it, "offset") } ?: 0,
            request["limit"]?.let { integer(it, "limit") } ?: 32))))
        val id = requireNotNull(source["id"] as? String) { "Stored id must be a string" }
        val rev = source["rev"]?.let { requireNotNull(it as? String) { "Stored rev must be a string" } }
        val entry = storage.document(id, rev) ?: return HttpResponse(404, jsonOf(mapOf("error" to "Revision not indexed")))
        val fileTree = if (entry.b != null) storage.fileTree(id, entry.a.rev, read = tree) else null
        if (tree && fileTree?.b == null) return HttpResponse(404, jsonOf(mapOf("error" to "File-tree metadata not indexed")))
        val document = if (tree) fileTree?.b else entry.b
        val body = mapOf("source" to entry.a.descriptor(), "blackboardRevision" to context.blackboard.snapshot().revision,
            "fileTree" to fileTree?.a, "facet" to if (tree) "file-tree" else "publication",
            "confix" to document?.let { doc -> WorkspaceFacets.inspect(doc, if (tree) Syntax.CBOR else requireNotNull(entry.a.syntax), path(request),
                request["offset"]?.let { integer(it, "offset") } ?: 0,
                request["limit"]?.let { integer(it, "limit") } ?: 32, cursorQuery(request["cursor"])) })
        return HttpResponse(200, jsonOf(body))
    }

    /** Facets over the exact retained JSON bytes; no named-head fallback or execution. */
    fun content(request: Map<*, *>, source: Map<*, *>): HttpResponse {
        require(source.keys == setOf("kind", "cid")) { "CAS inspection requires only kind and cid" }
        val cid = ContentId(requireNotNull(source["cid"] as? String) { "A retained content ID is required" })
        val bytes = context.casStore.get(cid)
            ?: return HttpResponse(404, jsonOf(mapOf("error" to "Retained content unavailable")))
        if (bytes.size > WorkspaceFacets.MAX_BYTES)
            return HttpResponse(413, jsonOf(mapOf("error" to "Retained content exceeds workspace byte limit")))
        if (ContentId.of(bytes) != cid)
            return HttpResponse(409, jsonOf(mapOf("error" to "Retained content identity mismatch")))
        val text: String
        val value: Any?
        try {
            text = bytes.decodeToString(throwOnInvalidSequence = true)
            value = reifyStrict(text)
        } catch (e: CharacterCodingException) {
            return HttpResponse(422, jsonOf(mapOf("error" to "Retained content is not UTF-8 JSON")))
        } catch (e: IllegalArgumentException) {
            return HttpResponse(422, jsonOf(mapOf("error" to "Retained content is not UTF-8 JSON")))
        } catch (e: IllegalStateException) {
            return HttpResponse(422, jsonOf(mapOf("error" to "Retained content is not UTF-8 JSON")))
        }
        WorkspaceFacets.valueBudget().violation(value)?.let { throw IllegalArgumentException(it) }
        return HttpResponse(200, jsonOf(mapOf("source" to mapOf("kind" to "cas", "cid" to cid.value),
            "blackboardRevision" to context.blackboard.snapshot().revision,
            "confix" to WorkspaceFacets.inspect(text, path(request),
                request["offset"]?.let { integer(it, "offset") } ?: 0,
                request["limit"]?.let { integer(it, "limit") } ?: 32, cursorQuery(request["cursor"])))))
    }

    fun cursorQuery(value: Any?): CursorFacetQuery {
        if (value == null) return CursorFacetQuery()
        val query = requireNotNull(value as? Map<*, *>) { "cursor must be an object" }
        fun array(value: Any?, name: String): List<*> = requireNotNull(value as? List<*>) { "$name must be an array" }
        val path = array(query["path"] ?: emptyList<Int>(), "cursor path")
            .map { integer(it, "cursor row") }.toSeries()
        val axes = array(query["axes"] ?: listOf(listOf(2)), "cursor axes").map { axis ->
            array(axis, "index columns").map { integer(it, "column") }.toIntArray()
        }.toSeries()
        val selections = array(query["selections"] ?: emptyList<Any>(), "cursor selections").map { selection ->
            val pair = array(selection, "index selection")
            require(pair.size == 2) { "Index selection must be [index, group]" }
            integer(pair[0], "index") j integer(pair[1], "group")
        }.toSeries()
        val cid = query["sourceCid"]?.let { requireNotNull(it as? String) { "sourceCid must be a string" } }
        return CursorFacetQuery(path, axes, selections,
            query["offset"]?.let { integer(it, "cursor offset") } ?: 0,
            query["groupOffset"]?.let { integer(it, "index group offset") } ?: 0, cid)
    }

    suspend fun route(method: String, body: String): HttpResponse = try {
        when (method) {
            "GET" -> HttpResponse(200, jsonOf(catalog()))
            "POST", "PUT" -> {
                require(body.encodeToByteArray().size <= WorkspaceFacets.MAX_BYTES) { "Workspace request exceeds byte limit" }
                val request = requireNotNull(reifyStrict(body) as? Map<*, *>) { "Request must be an object" }
                WorkspaceFacets.valueBudget().violation(request)?.let { throw IllegalArgumentException(it) }
                if (method == "PUT") {
                    require(request["action"] in setOf("index-couch", "index-file-tree")) { "Unknown workspace action" }
                    val storage = context.confixIsam
                    if (storage == null) HttpResponse(503, jsonOf(mapOf("error" to "Dense storage unavailable")))
                    else {
                        val dictionary = request["dictionaryCid"]?.let {
                            ContentId(requireNotNull(it as? String) { "dictionaryCid must be a content ID" })
                        }
                        val result = if (request["action"] == "index-couch") storage.importCouch(
                            request["limit"]?.let { integer(it, "limit") } ?: 32, dictionary)
                        else {
                            val source = requireNotNull(request["source"] as? Map<*, *>) { "source must be an object" }
                            require(source["kind"] == "couch") { "Unknown workspace source" }
                            storage.importFileTree(requireNotNull(source["id"] as? String) { "Stored id must be a string" },
                                requireNotNull(source["rev"] as? String) { "File-tree indexing requires an exact Couch revision" }, dictionary)
                        }
                        HttpResponse(200, jsonOf(result))
                    }
                } else if ("source" in request) stored(request)
                else HttpResponse(200, jsonOf(inspect(request)))
            }
            else -> HttpResponse(405, jsonOf(mapOf("error" to "method_not_allowed")))
        }
    } catch (e: CancellationException) { throw e }
    catch (e: IllegalArgumentException) {
        HttpResponse(400, jsonOf(mapOf("error" to (e.message ?: "Invalid workspace document"))))
    } catch (e: IllegalStateException) {
        HttpResponse(400, jsonOf(mapOf("error" to (e.message ?: "Invalid workspace request"))))
    }

    fun integer(value: Any?, name: String): Int {
        val n = requireNotNull(value as? Number) { "$name must be an integer" }.toDouble()
        require(n.isFinite() && n >= 0 && n <= Int.MAX_VALUE && n % 1 == 0.0) { "Invalid $name" }
        return n.toInt()
    }
}
