package borg.trikeshed.forge.server

import borg.trikeshed.graal.subvm.GuestModules
import borg.trikeshed.jules.BrainClient
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.j
import borg.trikeshed.lib.view
import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.relaxfactory.CouchHttpSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

/** The app and its LCNC composition share the same records, source acquisition and preparation. */
class HeadhunterWire private constructor(
    private val ctx: ModuleContext,
    private val store: HeadhunterStore,
    private val sources: HeadhunterSources,
    private val workflow: HeadhunterWorkflow,
    private val curation: HeadhunterCuration,
    private val documents: DocumentCurationWire?,
    private val brain: BrainClient,
    private val panels: PatchWire,
    private val credentials: HeadhunterCredentials,
    private val log: borg.trikeshed.narsese.DocumentAppendLog,
) {
    companion object {
        const val INTAKE_PROGRAM = "headhunter.intake"

        fun intakeProgram(): LcncProgram {
            val outputs = borg.trikeshed.lib.s_["record", "source", "extraction", "curation"]
            val nodes = borg.trikeshed.lib.SeriesBuffer<LcncNode>()
            val wires = borg.trikeshed.lib.SeriesBuffer<LcncWire>()
            nodes.add(LcncNode("input", LcncContracts.SCOPE_IN, mapOf("name" to "source", "kind" to "json"), 40.0, 80.0))
            nodes.add(LcncNode("capture", "headhunter.capture", x = 350.0, y = 80.0))
            wires.add(LcncWire("input", "value", "capture", "source"))
            for ((index, port) in outputs.view.withIndex()) {
                nodes.add(LcncNode(port, LcncContracts.SCOPE_OUT, mapOf("name" to port, "kind" to "json"), 660.0, 80.0 + index * 160))
                wires.add(LcncWire("capture", port, port, "value"))
            }
            return LcncProgram(INTAKE_PROGRAM, nodes.drain(), wires.drain())
        }
        suspend fun open(ctx: ModuleContext, brain: BrainClient, panels: PatchWire): HeadhunterWire =
            withContext(borg.trikeshed.userspace.nio.file.spi.fileIoContext) {
                val files = checkNotNull(ctx.scope.coroutineContext[borg.trikeshed.userspace.nio.file.spi.FileOperations]) {
                    "Job agent requires the host file service"
                }
                val path = files.resolvePath(ctx.stateDir.absolutePath, "headhunter")
                val privatePath = files.resolvePath(path, "private")
                files.mkdirs(privatePath)
                val log = borg.trikeshed.narsese.DocumentAppendLog.open(files.resolvePath(path, "records.wal"), path)
                var credentials: HeadhunterCredentials? = null
                var documentOwner: DocumentCurationWire? = null
                try {
                    val keys = HeadhunterCredentials.open(privatePath).also { credentials = it }
                    val store = HeadhunterStore(ctx.blackboard, ctx.casStore, log, ctx.clock)
                    store.restore()
                    val documents = DocumentCurationWire.open(ctx, brain, panels).also { documentOwner = it }
                    val curation = HeadhunterCuration(store, documents?.destination.orEmpty(), { documents?.available == true }) { source, instructions ->
                        checkNotNull(documents) { "Shared document curator is unavailable" }.curate(source, instructions)
                    }
                    val workflow = HeadhunterWorkflow(store) { prompt ->
                        val answer = brain.chatSeat(listOf(
                            "system" to "Prepare private job-application drafts grounded in selected evidence. Never execute external actions.",
                            "user" to prompt,
                        ), maxTokens = 6000, temperature = 0.2, contextId = "headhunter.prepare", preferredModel = null, timeoutMs = 90_000L)
                        answer.first j answer.second
                    }
                    HeadhunterWire(ctx, store, HeadhunterSources(ctx.casStore, keys.keys), workflow, curation, documents, brain, panels, keys, log)
                        .also {
                            it.attach(); it.ensureIntakeProgram()
                            for (record in store.records().view) if (record["kind"] == "evidence") documents?.sourceChanged(record["id"] as String)
                        }
                } catch (failure: Throwable) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        try { documentOwner?.drain() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                        try { documentOwner?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    }
                    try { credentials?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    try { log.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            }
    }
    private val requests = HeadhunterRequests(kotlinx.coroutines.CoroutineScope(
        ctx.scope.coroutineContext + borg.trikeshed.userspace.nio.file.spi.fileIoContext + ctx.muxContext,
    ))
    private val programLock = Mutex()

    fun attach() {
        ctx.lcncRunners.putAll(workflow.registry())
        ctx.lcncRunners.putAll(curation.registry())
        ctx.lcncRunners["headhunter.capture"] = LcncNodeRunner { _, inputs ->
            val output = capture(objectOf(inputs["source"], "source"))
            val record = output["record"] as? Map<*, *>
            output + ("record" to record?.let { mapOf("id" to it["id"], "cid" to it["cid"], "kind" to it["kind"]) })
        }
        ctx.routes.claim("headhunter", "/api/headhunter", route = ::route)
        ctx.routes.claim("headhunter", "/api/headhunter/record", route = ::route)
        ctx.routes.claim("headhunter", "/api/headhunter/history", route = ::route)
        ctx.routes.claim("headhunter", "/api/headhunter/collisions", route = ::route)
        ctx.routes.claim("headhunter", "/api/headhunter/capture", route = ::route)
        ctx.routes.claim("headhunter", "/api/headhunter/curation", route = ::route)
        ctx.routes.claim("headhunter", "/api/headhunter/credential", route = ::route)
        ctx.routes.claim("headhunter", "/api/headhunter/prepare", route = ::route)
        ctx.routes.claim("headhunter", "/api/headhunter/program", route = ::route)
        ctx.routes.claim("headhunter", "/api/headhunter/artifact", route = ::route)
    }

    suspend fun drain() {
        ctx.routes.release("headhunter")
        try { requests.drain() } finally { documents?.stopAdmission() }
    }

    /** The host calls this after the LCNC producers have drained. */
    suspend fun drainCuration() { documents?.drain() }

    /** Called after HTTP admission and the host's LCNC runs have drained. */
    suspend fun close() = withContext(borg.trikeshed.userspace.nio.file.spi.fileIoContext) {
        var failure: Throwable? = null
        try { documents?.close() } catch (caught: Throwable) { failure = caught }
        try { credentials.close() } catch (caught: Throwable) { failure?.addSuppressed(caught) ?: run { failure = caught } }
        try { log.close() } catch (caught: Throwable) { failure?.addSuppressed(caught) ?: run { failure = caught } }
        failure?.let { throw it }
        Unit
    }

    suspend fun route(method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)?): WireHttpResponse {
        val target = path.substringBefore('?')
        val query = CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
        try {
            return requests.call {
                if (method == "GET") when (target) {
                    "/api/headhunter" -> {
                        // Refresh the live KeyMux resolution; disclose capabilities, never credentials.
                        brain.rosterStatus()
                        val runs = ctx.blackboard.snapshot().values.entries.asSequence()
                            .filter { it.key.startsWith("lcnc/run/") }
                            .mapNotNull { it.value as? Map<*, *> }
                            .filter { it["program"] == HeadhunterWorkflow.PROGRAM }
                            .sortedByDescending { (it["startedAtMs"] as? Number)?.toLong() ?: 0L }.take(50).toList()
                        json(mapOf("records" to store.records().toList(), "runs" to runs,
                            "profile" to curation.profile(), "curation" to curationPlan(),
                            "model" to mapOf("configured" to brain.hasEndpoints(),
                                "detail" to if (brain.hasEndpoints()) "Configured KeyMux/ModelMux endpoints; availability is checked when invoked"
                                    else "No model endpoint is configured. Configure a provider in ModelMux; evidence assembly remains available.",
                                "models" to brain.endpointSummaries().map { it.model }),
                            "extraction" to mapOf("documents" to ("tika" in GuestModules.installed()),
                                "text" to true, "authenticatedHttp" to true, "browser" to false),
                            "program" to programView()))
                    }
                    "/api/headhunter/history" -> json(mapOf("versions" to store.history(required(query, "id")).toList()))
                    "/api/headhunter/collisions" -> json(mapOf("collisions" to store.collisions(required(query, "applicationId")).toList()))
                    "/api/headhunter/artifact" -> download(required(query, "id"), query["format"] ?: "markdown")
                    else -> json(mapOf("error" to "method_not_allowed"), 405)
                } else if (method == "DELETE" && target == "/api/headhunter/record") {
                    val current = store.record(required(query, "id")) ?: error("Source record is unavailable")
                    require(current["kind"] in setOf("evidence", "source")) { "Only source and evidence records can be removed here" }
                    val deleted = store.save(current["kind"] as String, current["id"] as String, required(query, "baseCid"),
                        fields(current) + ("deleted" to true))
                    if (current["kind"] == "evidence") documents?.sourceChanged(current["id"] as String)
                    json(deleted)
                } else if (method == "POST") {
                    require(text.length <= 4 * 1024 * 1024) { "Request exceeds the 4 MiB request limit; upload files up to 2 MiB" }
                    val input = objectOf(JsonSupport.parseStrict(text.substringAfter("\r\n\r\n", text)), "request")
                    when (target) {
                        "/api/headhunter/record" -> json(save(input))
                        "/api/headhunter/curation" -> json(curation.run(required(input, "planCid")) + ("curation" to curationPlan()))
                        "/api/headhunter/capture" -> {
                            val result = intake(input)
                            val ready = (result["extraction"] as? Map<*, *>)?.get("status") == "ready"
                            json(result, if (ready) 200 else 422)
                        }
                        "/api/headhunter/credential" -> json(credential(input))
                        "/api/headhunter/prepare" -> prepare(input)
                        "/api/headhunter/program" -> { ensureProgram(); json(programView()) }
                        else -> json(mapOf("error" to "method_not_allowed"), 405)
                    }
                } else json(mapOf("error" to "method_not_allowed"), 405)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Acquisition sanitizes transport exceptions. Credentials are never included in this text.
            val message = failure.message.orEmpty().take(2048)
            return json(mapOf("error" to (message.ifBlank { "Job agent operation failed" })),
                if (message.contains("Stale baseCid") || message.contains("Curation plan changed")) 409 else if (failure is IllegalArgumentException) 400 else 503)
        }
    }

    private suspend fun save(input: Map<String, Any?>): Map<String, Any?> {
        val kind = required(input, "kind")
        val fields = objectOf(input["fields"], "fields")
        if (kind == "source") sources.configuration(sourceConfig(fields))
        return store.save(kind, input["id"] as? String, input["baseCid"] as? String, fields).also {
            if (kind == "evidence") documents?.sourceChanged(it["id"] as String)
        }
    }

    private suspend fun credential(input: Map<String, Any?>): Map<String, Any?> {
        val source = store.record(required(input, "sourceId")) ?: error("Source is unavailable")
        require(source["kind"] == "source") { "Choose a source record" }
        val current = fields(source)
        val saved = sources.saveCredential(input + mapOf(
            "origin" to (input["origin"] ?: current["origin"]),
            "credentialRef" to (current["credentialRef"] ?: java.util.UUID.randomUUID().toString()),
        ))
        val record = store.save("source", source["id"] as String, source["cid"] as String,
            current + mapOf("credentialRef" to saved["credentialRef"], "origin" to saved["origin"]))
        return saved + mapOf("source" to record)
    }

    private suspend fun capture(input: Map<String, Any?>): Map<String, Any?> {
        val kind = input["kind"] as? String ?: "listing"
        require(kind == "listing" || kind == "evidence") { "Capture a listing or professional evidence" }
        val old = (input["sourceId"] as? String)?.takeIf { it.isNotBlank() }?.let {
            store.record(it)?.also { record -> require(record["kind"] == "source") { "Choose a source record" } }
                ?: error("Source is unavailable")
        }
        val previous = old?.let(::fields).orEmpty()
        val captureInput = input.toMutableMap()
        if (listOf("url", "text", "base64", "originalCid").none { captureInput[it] != null }) {
            if (previous["url"] != null) captureInput["url"] = previous["url"]
            else if (previous["originalCid"] != null) captureInput["originalCid"] = previous["originalCid"]
        }
        if (captureInput["originalCid"] != null) for (key in listOf("filename", "mediaType")) {
            if (captureInput[key] == null) previous[key]?.let { captureInput[key] = it }
        }
        val acquired = sources.capture(captureInput, sourceConfig(previous))
        val title = (input["title"] as? String)?.takeIf { it.isNotBlank() }
            ?: input["filename"] as? String ?: previous["title"] as? String ?: "Imported $kind"
        val url = captureInput["url"] as? String
        val sourceFields = previous + mapOf(
            "title" to (previous["title"] ?: title),
            "origin" to (previous["origin"] ?: url?.let { val uri = URI(it); "${uri.scheme}://${uri.rawAuthority}" } ?: "upload"),
            "url" to (url ?: previous["url"]), "originalCid" to acquired["originalCid"],
            "captureKind" to kind, "category" to (input["category"] ?: previous["category"] ?: "resume"),
            "filename" to acquired["filename"], "mediaType" to acquired["mediaType"],
            "relativePath" to input["relativePath"],
            "lastCapture" to acquired.filterKeys { it != "text" && it != "configuration" },
        )
        val fields = linkedMapOf<String, Any?>("title" to title, "text" to acquired["text"],
            "originalCid" to acquired["originalCid"], "relativePath" to input["relativePath"],
            "filename" to acquired["filename"], "mediaType" to acquired["mediaType"],
            "provenance" to acquired.filterKeys { it !in setOf("text", "configuration") }, "reviewStatus" to "proposed")
        if (kind == "evidence") fields["category"] = input["category"] as? String ?: previous["category"] ?: "resume"
        else {
            for (key in listOf("employerId", "employer", "requisition", "contactId")) input[key]?.let { fields[key] = it }
            url?.let { fields["url"] = it }
        }
        val captured = store.capture(kind, sourceFields, fields.takeIf { acquired["status"] == "ready" },
            old?.get("id") as? String, old?.get("cid") as? String, input["id"] as? String, input["baseCid"] as? String)
        return mapOf("record" to captured.b, "source" to captured.a, "extraction" to acquired.filterKeys { it != "text" },
            "error" to acquired["error"], "curation" to curationPlan())
    }

    private suspend fun curationPlan(): Map<String, Any?> = curation.plan() + ("program" to DocumentCurationWire.PROFILE_PROGRAM)

    private suspend fun ensureIntakeProgram() = programLock.withLock {
        val path = "/api/panels/$INTAKE_PROGRAM"
        if (panels.route("GET", path, "", null)?.status != 200)
            check(panels.route("POST", path, LcncProgramConfix.toJson(intakeProgram()), null)?.status == 200) { "The intake composition could not be saved" }
    }

    private suspend fun intake(input: Map<String, Any?>): Map<String, Any?> {
        val source = input.toMutableMap()
        (source.remove("base64") as? String)?.let { encoded ->
            val bytes = java.util.Base64.getDecoder().decode(encoded)
            require(bytes.size <= 2 * 1024 * 1024) { "File exceeds 2 MiB" }
            source["originalCid"] = ctx.casStore.put(bytes).value
        }
        val route = ctx.routes.match("/api/lcnc/run") ?: error("LCNC execution is unavailable")
        val response = route.route("POST", "/api/lcnc/run", JsonSupport.stringify(mapOf(
            "program" to INTAKE_PROGRAM, "inputs" to mapOf("source" to source), "timeoutMs" to 120_000)), null)
            ?: error("LCNC returned no intake run")
        val run = JsonSupport.parseMap(response.body)
        check(response.status == 200 && run["error"] == null) { run["error"]?.toString() ?: "Source intake failed" }
        val returned = objectOf(run["returns"], "intake results")
        val reference = returned["record"] as? Map<*, *>
        val record = reference?.let { store.version(it["id"] as String, it["cid"] as String) }
        return returned + mapOf("record" to record, "run" to run)
    }

    private suspend fun programView(): Map<String, Any?> {
        val saved = panels.route("GET", "/api/panels/${HeadhunterWorkflow.PROGRAM}", "", null)
        val document = if (saved?.status == 200) saved.body else LcncProgramConfix.toJson(HeadhunterWorkflow.program())
        return mapOf("name" to HeadhunterWorkflow.PROGRAM, "document" to JsonSupport.parse(document), "saved" to (saved?.status == 200))
    }

    private suspend fun ensureProgram() = programLock.withLock {
        if (programView()["saved"] == true) return@withLock
        val path = "/api/panels/${HeadhunterWorkflow.PROGRAM}"
        val result = panels.route("POST", path, LcncProgramConfix.toJson(HeadhunterWorkflow.program()), null)
        check(result?.status == 200) { "The preparation program could not be saved" }
    }

    private suspend fun prepare(input: Map<String, Any?>): WireHttpResponse {
        val pinned = workflow.pin(input)
        ensureProgram()
        val request = mapOf("program" to HeadhunterWorkflow.PROGRAM,
            "inputs" to mapOf("request" to pinned), "timeoutMs" to 120000)
        val runner = ctx.routes.match("/api/lcnc/run") ?: error("LCNC execution is unavailable")
        val result = runner.route("POST", "/api/lcnc/run", JsonSupport.stringify(request), null)
            ?: error("LCNC returned no run result")
        val run = JsonSupport.parseMap(result.body)
        val returns = run["returns"] as? Map<*, *>
        return json(mapOf("run" to run, "artifacts" to (returns?.get("artifacts") ?: emptyList<Any?>()),
            "collisions" to (returns?.get("collisions") ?: emptyList<Any?>()), "error" to run["error"]), result.status)
    }

    private suspend fun download(id: String, format: String): WireHttpResponse {
        val record = store.record(id) ?: error("Artifact is unavailable")
        require(record["kind"] == "artifact") { "Choose an artifact record" }
        val fields = fields(record)
        val content = fields["content"] as String
        val references = HeadhunterStore.references(fields).view.joinToString("\n") { "${it.a}@${it.b}" }
        val review = store.records().view.asSequence().filter {
            it["kind"] == "review" && fields(it)["subjectId"] == id && fields(it)["subjectCid"] == record["cid"]
        }.maxByOrNull { (it["updatedAtMs"] as? Number)?.toLong() ?: 0L }
        val decision = review?.let { "${fields(it)["decision"]} — ${it["id"]}@${it["cid"]}" } ?: "Awaiting review"
        val text = "$content\n\nEvidence versions:\n$references\n\nArtifact: ${record["id"]}@${record["cid"]}\nReview: $decision\n"
        val title = fields["title"]?.toString() ?: "Application artifact"
        val (body, mime, extension) = when (format) {
            "markdown" -> Triple(text, "text/markdown; charset=utf-8", "md")
            "text" -> Triple(text, "text/plain; charset=utf-8", "txt")
            "html" -> Triple("<!doctype html><html><head><meta charset=\"utf-8\"><title>${escape(title)}</title>" +
                "<style>body{max-width:52rem;margin:3rem auto;padding:0 2rem;font:16px/1.55 system-ui}pre{white-space:pre-wrap;font:inherit}@media print{body{margin:0}}</style></head>" +
                "<body><pre>${escape(text)}</pre></body></html>", "text/html; charset=utf-8", "html")
            else -> throw IllegalArgumentException("Choose markdown, text or html")
        }
        return WireHttpResponse(200, body, mime, headers = mapOf("Cache-Control" to "no-store",
            "Content-Disposition" to "attachment; filename=\"${fields["type"] ?: "artifact"}.$extension\""))
    }

    private fun sourceConfig(fields: Map<String, Any?>) = fields.filterKeys { it in setOf("mode", "extraction", "credentialRef") }
    private fun required(fields: Map<String, *>, key: String) = fields[key]?.toString()?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("$key is required")
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun json(value: Map<String, Any?>, status: Int = 200) = WireHttpResponse(status, JsonSupport.stringify(value),
        headers = mapOf("Cache-Control" to "no-store"))
}
