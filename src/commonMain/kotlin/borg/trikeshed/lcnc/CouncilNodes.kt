package borg.trikeshed.lcnc

import borg.trikeshed.parse.reify
import borg.trikeshed.parse.reifyMap
import borg.trikeshed.parse.jsonOf

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Durable decision and publication are distinct boundaries across the record's stores. */
enum class CouncilRecordPhase { PREPARED, RECORDED, PUBLISHED }

/**
 * The persistence seams `council.record` / `council.case` cross — every
 * durable plane as a plain function, so the runners stay commonMain-pure
 * and the daemon wires the REAL planes (FileCasStore, blackboard, kifSink,
 * couch, CouncilCaseRegistry) while tests wire [inMemory] fakes and spy the
 * backing [InMemoryRecordStore] directly.
 */
data class RecordSeams(
    /** Store bytes content-addressed; returns the cid ("sha256:<hex>"). */
    val casPut: (ByteArray) -> String,
    /** Land an index fact: (key, fact, source). */
    val blackboardPut: (String, Map<String, String>, String) -> Unit,
    /** Assert one KIF expression into the shared bank. */
    val kifSink: (String) -> Unit,
    /** Upsert a durable document: (id, body). Must throw on rejection, including conflicts. */
    val couchPut: (String, Map<String, Any?>) -> Unit,
    /** Advance the per-case lifecycle to ruled; returns its retained JobSnapshot CID. */
    val recordRuling: suspend (caseId: String, verdictCid: String, transcriptCid: String) -> String,
    /** Advance the per-case lifecycle to mistrial; returns its retained JobSnapshot CID. */
    val recordMistrial: suspend (caseId: String, reason: String) -> String,
    val blackboardGet: (String) -> Map<String, String>?,
    val couchGet: (String) -> Map<String, Any?>?,
    val casGet: (String) -> ByteArray?,
    /** Shared by copies of these borrowed services; serialize journal admission and replay. */
    val recordMutex: Mutex = Mutex(),
) {
    fun verifyRetained(bytes: ByteArray): String {
        val cid = ContentId.of(bytes).value
        check(casGet(cid)?.contentEquals(bytes) == true) { "council.record: CAS bytes not retained for $cid" }
        return cid
    }

    fun retain(bytes: ByteArray): String {
        check(casPut(bytes) == ContentId.of(bytes).value) { "council.record: CAS acknowledgement digest mismatch" }
        return verifyRetained(bytes)
    }

    /** Check the receipt itself, without reconstructing or rewriting any missing state. */
    fun lifecycleReceipt(caseId: String, receipt: String?, decisionStatus: String?): String {
        try {
            val cid = ContentId(checkNotNull(receipt?.takeIf { it.isNotBlank() }) { "missing identity" })
            val bytes = checkNotNull(casGet(cid.value)) { "snapshot bytes not retained" }
            check(ContentId.of(bytes) == cid) { "snapshot digest mismatch" }
            val snapshot = CanonicalCbor.decodeJobSnapshot(bytes)
            val expected = when (decisionStatus) {
                "ruled" -> "closed"
                "mistrial" -> "failed"
                else -> error("missing or invalid decision status")
            }
            check(snapshot.jobId == JobId.of("case/$caseId")) { "snapshot names another case" }
            check(snapshot.revision > 0L) { "snapshot has no committed revision" }
            check(snapshot.lifecycle == expected) { "expected '$expected' lifecycle, found '${snapshot.lifecycle}'" }
            return cid.value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw IllegalStateException("council.record: invalid lifecycle receipt for '$caseId': ${failure.message}", failure)
        }
    }

    companion object {
        /** Couch rejects writes with an error body as well as exceptions. Both fail recording. */
        fun requireCouchWrite(id: String, response: Map<String, Any?>) {
            check(response["ok"] == true && response["id"] == id &&
                (response["rev"] as? String)?.isNotBlank() == true) {
                "council.record: Couch write '$id' was not acknowledged: ${jsonOf(response)}"
            }
        }

        /** Map/list-backed fakes over [store] — the zero-spend test seams. */
        fun inMemory(store: InMemoryRecordStore = InMemoryRecordStore()): RecordSeams = RecordSeams(
            casPut = { bytes -> ContentId.of(bytes).value.also { store.cas[it] = bytes } },
            blackboardPut = { key, fact, source ->
                store.blackboard[key] = fact
                store.blackboardSources[key] = source
            },
            kifSink = { store.kif.add(it) },
            couchPut = { id, body -> store.couch[id] = body },
            recordRuling = { caseId, verdictCid, transcriptCid ->
                store.rulings.add(Triple(caseId, verdictCid, transcriptCid))
                store.receipt(caseId, "closed")
            },
            recordMistrial = { caseId, reason ->
                store.mistrials.add(caseId to reason)
                store.receipt(caseId, "failed")
            },
            blackboardGet = { store.blackboard[it] },
            couchGet = { store.couch[it] },
            casGet = { store.cas[it] },
        )
    }
}

/** The containers behind [RecordSeams.inMemory] — public so tests spy them. */
class InMemoryRecordStore {
    val cas = LinkedHashMap<String, ByteArray>()
    val blackboard = LinkedHashMap<String, Map<String, String>>()
    val blackboardSources = LinkedHashMap<String, String>()
    val kif = ArrayList<String>()
    val couch = LinkedHashMap<String, Map<String, Any?>>()
    val rulings = ArrayList<Triple<String, String, String>>()
    val mistrials = ArrayList<Pair<String, String>>()

    /** Snapshot-shaped fixture; this does not execute a job nexus or establish disk durability. */
    fun receipt(caseId: String, lifecycle: String): String {
        val bytes = CanonicalCbor.encode(JobSnapshot(JobId.of("case/$caseId"), 3L, "", lifecycle, attemptCount = 1))
        return ContentId.of(bytes).value.also { cas[it] = bytes }
    }
}

/**
 * The council's node family — the runners behind the vocabulary
 * [CouncilProgram.build] draws: `council.seat` (the model seat over the
 * [CouncilDialog] seam, degrade-loudly), `text.fold` / `record.fold`
 * (dumb MANY-part concatenators — prompt assembly is never hidden in a
 * runner), `ruling.parse` (the trailing-JSON verdict extractor with
 * STRICT-false booleans so ring guards never see truthy garbage),
 * `coalesce` (clarified-over-original via absent-yield semantics),
 * `council.convene` (re-geometry: config in, drawn program out),
 * `council.record` (the ONE host-wired sink: CAS + blackboard + couch +
 * KIF + case lifecycle), and `council.case` (the read-back veneer).
 */
object CouncilNodes {

    /** Shared structured ruling semantics; control guards never rewrite the retained text. */
    fun ruling(text: String): Map<String, Any?> {
        val parsed = if (text.startsWith("[SEAT FAILED")) null else lastBalancedObject(text)
        val mistrial = strictBool(parsed?.get("mistrial"))
        return if (parsed == null) mapOf(
            "verdict" to mapOf("disposition" to text),
            "needsClarification" to false,
            "clarificationQuestion" to "",
            "mistrial" to false,
            "text" to text,
        ) else mapOf(
            "verdict" to parsed,
            "needsClarification" to (!mistrial && strictBool(parsed["needsClarification"])),
            "clarificationQuestion" to (parsed["clarificationQuestion"]?.toString() ?: ""),
            "mistrial" to mistrial,
            "text" to text,
        )
    }

    fun fold(label: String, brief: Any?, parts: Any?, separator: String = "\n\n---\n\n", numbered: Boolean = true): String {
        val values = ArrayList<String>()
        flattenText(brief, values)
        flattenText(parts, values)
        val body = values.filter { it.isNotBlank() }.mapIndexed { i, p ->
            if (!numbered || p.startsWith("[")) p else "(${i + 1}) $p"
        }
        val lines = (if (label.isBlank()) emptyList() else listOf("== $label ==")) + body
        return lines.joinToString(separator)
    }

    fun registry(
        dialog: CouncilDialog,
        seams: RecordSeams = RecordSeams.inMemory(),
    ): Map<String, LcncNodeRunner> = mapOf(

        // The model seat. Inputs are keyed by the wire's literal toPort
        // (gather() does not strip the `?`) — check both spellings, the
        // same defensive dance mux.chat's prompt resolution does. The
        // dialog call NEVER throws through: a throwing dialog becomes a
        // Refused banner that still emits content, so downstream folds and
        // prompts carry the failure ON the record (degrade loudly).
        "council.seat" to boundLcnc(CouncilDialogKey(dialog)) { service, node, inputs ->
            val prompt = ((inputs["prompt"] as? String)
                ?: (inputs["prompt?"] as? String))
                ?.takeIf { it.isNotBlank() }
            require(prompt != null) { "council.seat ${node.id}: no prompt wired" }
            val panel = node.params["panel"] ?: "council"
            val seatName = node.params["seat"] ?: node.id
            val role = node.params["role"] ?: "expert"
            val round = node.params["round"]?.toIntOrNull() ?: 1
            val charge = node.params["charge"].orEmpty()
            val requestedModel = node.params["model"]?.takeIf { it.isNotBlank() }
            val contextId = node.params["contextId"].orEmpty()
            val call = SeatCall(
                node = node,
                caseId = node.params["caseId"] ?: "default",
                panel = panel, seat = seatName, role = role, round = round,
                system = node.params["system"].orEmpty(),
                prompt = prompt,
                preferredModel = requestedModel,
                maxTokens = node.params["maxTokens"]?.toIntOrNull() ?: 512,
                temperature = node.params["temperature"]?.toDoubleOrNull() ?: 0.2,
                contextId = contextId,
            )
            val outcome = try {
                service.value.seat(call)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                SeatOutcome.Refused(t.message ?: t.toString(), emptyList())
            }
            fun record(status: String, answeredBy: String, text: String, extra: Map<String, Any?>): Map<String, Any?> =
                linkedMapOf<String, Any?>(
                    "seat" to seatName,
                    "panel" to panel,
                    "role" to role,
                    "round" to round,
                    "charge" to charge,
                    "requestedModel" to requestedModel.orEmpty(),
                    "answeredBy" to answeredBy,
                    "status" to status,
                    "cid" to ContentId.of(text.encodeToByteArray()).value,
                    "text" to text,
                    "chars" to text.length,
                    "contextId" to contextId,
                ).also { it.putAll(extra) }
            when (outcome) {
                is SeatOutcome.Ok -> mapOf(
                    "content" to outcome.content,
                    "labeled" to "[$panel.$seatName · $role · round $round · ${outcome.answeredBy}]\n${outcome.content}",
                    "model" to outcome.answeredBy,
                    "record" to record("ok", outcome.answeredBy, outcome.content, emptyMap()),
                )
                is SeatOutcome.Refused -> {
                    val trail = outcome.attempted.joinToString(" -> ")
                    val banner = "[SEAT FAILED: $panel.$seatName] ${outcome.error}; attempted: $trail"
                    mapOf(
                        "content" to banner,
                        "labeled" to "[$panel.$seatName · $role · round $round · FAILED: ${trail.ifEmpty { outcome.error }}]\n$banner",
                        "model" to "",
                        "record" to record(
                            "refused", "", banner,
                            mapOf("error" to outcome.error, "attempted" to outcome.attempted),
                        ),
                    )
                }
            }
        },

        // Dumb concatenator over MANY `parts` wires. Tolerates the runner's
        // single-wire scalar unwrap (LcncRunner gather: one wire arrives as
        // the value itself, not a list). `brief?` is the text-kinded lead
        // part (legal.evidence's brief into the ruling fold — the judge-diet
        // graft's wire), folded FIRST. A part that already carries a bracket
        // header (a seat's `labeled`) keeps it; otherwise `numbered`
        // prefixes its ordinal.
        "text.fold" to LcncPureRunner { node, inputs ->
            mapOf("text" to fold(node.params["label"].orEmpty(),
                inputs["brief"] ?: inputs["brief?"], inputs["parts"] ?: inputs["parts?"],
                node.params["separator"] ?: "\n\n---\n\n", node.params["numbered"] != "false"))
        },

        // Provenance gatherer: MANY `parts` of turn-record maps (or lists of
        // them) flattened to one list in wire order — gathered INSIDE each
        // ring so no inner→outer wire ever exists.
        "record.fold" to object : LcncPureRunner {
            override suspend fun execute(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?> {
                val turns = ArrayList<Map<String, Any?>>()
                flattenTurns(inputs["parts"] ?: inputs["parts?"], turns)
                return mapOf("turns" to turns)
            }
            override fun retainedOutputs(node: LcncNode, inputs: Map<String, Any?>,
                                         retainedInputs: Map<String, Any?>, outputs: Map<String, Any?>): Map<String, Any?> {
                val selected = if (inputs["parts"] != null) "parts" else "parts?"
                if (selected !in retainedInputs || retainedInputs[selected] === LcncProjection.Absent)
                    return outputs - "turns"
                if ((outputs["turns"] as? List<*>)?.isEmpty() == true) return outputs
                val turns = ArrayList<Any?>()
                flattenTurns(inputs[selected], retainedInputs[selected], turns)
                return outputs + ("turns" to turns)
            }

            override fun retainedOrigins(node: LcncNode, inputs: Map<String, Any?>,
                                         inputOrigins: Map<String, LcncBindingOrigins>, outputs: Map<String, Any?>,
                                         terms: LcncBindingTerms): Map<String, LcncBindingOrigins> {
                if ("turns" !in outputs) return emptyMap()
                val selected = if (inputs["parts"] != null) "parts" else "parts?"
                val source = inputOrigins[selected] ?: emptySeriesOf()
                val parts = ArrayList<LcncBindingOrigins>()
                val rows = ArrayList<Int>()
                var ordinal = 0
                fun visit(value: Any?, path: List<Either<String, Int>>) {
                    terms.account()
                    if (path.size > 30) throw LcncBindingTerms.Limit("depth_limit")
                    when (value) {
                        is Map<*, *> -> {
                            val origins = terms.select(source, path.toSeries())
                            rows.add(terms.presence(origins))
                            parts.add(terms.prefix(origins,
                                s_[Either.right<String, Int>(ordinal++)]))
                        }
                        is List<*> -> for ((index, child) in value.withIndex())
                            visit(child, path + Either.right<String, Int>(index))
                    }
                }
                visit(inputs[selected], emptyList())
                parts.add(terms.guard(if (rows.isEmpty()) terms.presence(source) else terms.any(rows.toSeries())))
                return mapOf("turns" to terms.join(parts.toSeries()))
            }
        },

        // The verdict extractor. Locates the LAST balanced {...} object in
        // the ruling text and parses it. Booleans are STRICT: true ONLY on
        // JSON true or the string "true" — on ANY failure path (no JSON,
        // malformed JSON, a seat-failure banner) both come back false, so
        // the clarify/mistrial ring guards can never see truthy garbage.
        // Mistrial is terminal: suppress clarification without rewriting the verdict.
        "ruling.parse" to LcncNodeRunner { _, inputs ->
            ruling(((inputs["text"] as? String) ?: (inputs["text?"] as? String)).orEmpty())
        },

        // Clarified-over-original: `a?` wins when present and non-blank; a
        // skipped ring's yield stays absent, so `b` (the original) stands.
        "coalesce" to object : LcncPureRunner {
            fun input(inputs: Map<String, Any?>): String? {
                val a = listOf("a", "a?").firstOrNull { inputs[it] != null }
                val value = a?.let { inputs[it] }
                return if (value != null && (value !is String || value.isNotBlank())) a
                    else listOf("b", "b?").firstOrNull { inputs[it] != null }
            }
            override suspend fun execute(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?> =
                mapOf("value" to input(inputs)?.let { inputs[it] })
            override fun outputSources(node: LcncNode, inputs: Map<String, Any?>): Map<String, String> =
                input(inputs)?.let { mapOf("value" to it) } ?: emptyMap()
        },

        // Re-geometry: a convening config (wired map or JSON string, merged
        // OVER the `config` param) in, the drawn program document out —
        // embeddable directly as /api/lcnc/run's `document`. Bound
        // violations from CouncilProgram.build propagate loudly.
        "council.convene" to LcncNodeRunner { node, inputs ->
            val base = node.params["config"]?.takeIf { it.isNotBlank() }
                ?.let { reifyMap(it) } ?: emptyMap()
            val over: Map<String, Any?> = when (val c = inputs["config"] ?: inputs["config?"]) {
                null -> emptyMap()
                is Map<*, *> -> c.entries.associate { (k, v) -> k.toString() to v }
                is String -> if (c.isBlank()) emptyMap() else reifyMap(c)
                else -> emptyMap()
            }
            val cfg = parseCouncilConfig(base + over)
            val program = CouncilProgram.build(cfg)
            val experts = cfg.panels.map { it.personas.size }
            // reify types every JSON number as Double; `seq` is the one Int
            // toJson emits, so restore it — the emitted document must
            // re-stringify byte-identical to the builder's (test-pinned).
            val doc = LinkedHashMap(reifyMap(LcncProgramConfix.toJson(program)))
            doc["seq"] = program.seq
            mapOf(
                "program" to doc,
                "summary" to linkedMapOf(
                    "name" to program.name,
                    "panels" to cfg.panels.size,
                    "expertsPerPanel" to (experts.distinct().singleOrNull() ?: experts),
                    "rounds" to cfg.rounds,
                    "seats" to countType(program.nodes, "council.seat"),
                    "nodes" to countNodes(program.nodes),
                ),
            )
        },

        // The ONE host-wired sink: per-turn bytes to CAS with commit-time
        // cid verification (missing/mismatched provenance retained as mistrial), transcript +
        // verdict + case doc to CAS, blackboard index fact, durable couch
        // doc, the (ruling …) KIF assertion, and the per-case lifecycle
        // advance. All seats refused, a banner verdict, an explicit mistrial,
        // or unverified turn bytes prevent a ruling fact from entering the bank.
        // An unresolved clarification is not a decision and is refused before persistence.
        "council.record" to boundLcnc(CouncilRecordKey(seams)) { service, node, inputs ->
            val seams = service.value
            seams.recordMutex.withLock {
            val verdict = inputs["verdict"] ?: inputs["verdict?"]
            require(verdict != null) { "council.record: no verdict wired" }
            val decision = verdict as? Map<*, *>
            val verdictMistrial = strictBool(decision?.get("mistrial"))
            require(verdictMistrial || !strictBool(decision?.get("needsClarification"))) {
                "council.record: unresolved clarification; no terminal ruling to record"
            }
            val caseId = ((inputs["caseId"] ?: inputs["caseId?"]) as? String)?.takeIf { it.isNotBlank() }
                ?: node.params["caseId"]?.takeIf { it.isNotBlank() }
                ?: "default"
            val documentCid = ((inputs["documentCid"] ?: inputs["documentCid?"]) as? String)
                ?.takeIf { it.isNotBlank() }
                ?.also { ContentId(it) } // Validate before any durable effect or KIF interpolation.

            val turns = ArrayList<Map<String, Any?>>()
            flattenTurns(inputs["turns"] ?: inputs["turns?"], turns)
            var cidMismatches = 0
            for (turn in turns) {
                val bytes = (turn["text"] as? String)?.encodeToByteArray()
                val declared = turn["cid"] as? String
                if (bytes == null || ContentId.of(bytes).value != declared) cidMismatches++
            }
            val seatCount = turns.size
            val seatFailures = turns.count { it["status"] == "refused" }

            val transcriptParts = ArrayList<String>()
            flattenText(inputs["transcript"] ?: inputs["transcript?"], transcriptParts)
            val transcriptDoc = transcriptParts.filter { it.isNotBlank() }.joinToString("\n\n---\n\n")
            val transcriptBytes = transcriptDoc.encodeToByteArray()
            val transcriptCid = ContentId.of(transcriptBytes).value

            val verdictText = if (verdict is String) verdict else jsonOf(verdict)
            val verdictBytes = verdictText.encodeToByteArray()
            val verdictCid = ContentId.of(verdictBytes).value

            val status = when {
                cidMismatches > 0 -> "mistrial"
                seatCount > 0 && seatFailures == seatCount -> "mistrial"
                verdictText.contains("[SEAT FAILED") -> "mistrial"
                verdictMistrial -> "mistrial"
                else -> "ruled"
            }

            val caseDoc = linkedMapOf<String, Any?>(
                "caseId" to caseId,
                "documentCid" to documentCid.orEmpty(),
                "verdictCid" to verdictCid,
                "transcriptCid" to transcriptCid,
                "seatFailures" to seatFailures,
                "seatCount" to seatCount,
                "cidMismatches" to cidMismatches,
                "status" to status,
                "turns" to turns,
            )
            val caseBytes = jsonOf(caseDoc).encodeToByteArray()
            val caseCid = ContentId.of(caseBytes).value
            val key = "council-case/$caseId"
            val existing = seams.couchGet(key)
            check(existing == null || existing["caseCid"] == caseCid) {
                "council.record: case '$caseId' already names different or legacy evidence; use a distinct case id"
            }
            val phase = existing?.let { doc ->
                checkNotNull(CouncilRecordPhase.entries.find { it.name == doc["recordPhase"] }) {
                    "council.record: case '$caseId' has no valid persistence phase"
                }
            }
            // Validate replay before any repair/publication writes, not just before returning a report.
            val priorReceipt = if (phase == CouncilRecordPhase.RECORDED || phase == CouncilRecordPhase.PUBLISHED) {
                seams.lifecycleReceipt(caseId, existing?.get("recorded") as? String, status)
            } else null
            // A published replay is read-only, including CAS. Missing bytes are a failure,
            // not an excuse to silently reconstruct a previously completed record.
            fun retain(bytes: ByteArray) {
                if (phase == CouncilRecordPhase.PUBLISHED) seams.verifyRetained(bytes) else seams.retain(bytes)
            }
            for (turn in turns) (turn["text"] as? String)?.let { retain(it.encodeToByteArray()) }
            retain(transcriptBytes)
            retain(verdictBytes)
            retain(caseBytes)
            val index = linkedMapOf("caseCid" to caseCid, "verdictCid" to verdictCid,
                "documentCid" to documentCid.orEmpty(), "status" to status)
            fun journal(next: CouncilRecordPhase, recorded: String? = null): Map<String, Any?> =
                caseDoc + mapOf("caseCid" to caseCid, "decisionStatus" to status,
                    "status" to if (next == CouncilRecordPhase.PUBLISHED) status else "recording",
                    "recordPhase" to next.name, "recorded" to recorded)

            val recorded = priorReceipt ?: run {
                seams.couchPut(key, journal(CouncilRecordPhase.PREPARED))
                seams.blackboardPut(key, index + ("status" to "recording"), "council.record")
                val cid = if (status == "mistrial") {
                    seams.recordMistrial(caseId,
                        "seatFailures=$seatFailures/$seatCount; cidMismatches=$cidMismatches; verdict=${verdictText.take(160)}")
                } else seams.recordRuling(caseId, verdictCid, transcriptCid)
                seams.lifecycleReceipt(caseId, cid, status)
                seams.couchPut(key, journal(CouncilRecordPhase.RECORDED, cid))
                cid
            }
            if (phase != CouncilRecordPhase.PUBLISHED) {
                // Never expose a ruling in the evidence bank before the lifecycle commits.
                if (documentCid != null && status == "ruled") {
                    seams.kifSink("(ruling case_${kifAtom(caseId)} doc_${documentCid.removePrefix("sha256:")} " +
                        verdictCid.removePrefix("sha256:") + ")")
                }
                seams.blackboardPut(key, index, "council.record")
                seams.couchPut(key, journal(CouncilRecordPhase.PUBLISHED, recorded))
            }
            mapOf("report" to linkedMapOf(
                "caseId" to caseId,
                "caseCid" to caseCid,
                "verdictCid" to verdictCid,
                "transcriptCid" to transcriptCid,
                "recorded" to recorded,
                "status" to status,
                "seatFailures" to seatFailures,
                "seatCount" to seatCount,
                "cidMismatches" to cidMismatches,
            ))
            }
        },

        // Read-back veneer: index fact (blackboard, falling back to the
        // durable couch doc) plus the transcript/verdict bytes from CAS —
        // the GET /api/lcnc/council/{caseId} route is a thin shell over this.
        "council.case" to boundLcnc(CouncilRecordKey(seams)) { service, node, inputs ->
            val seams = service.value
            val caseId = ((inputs["caseId"] ?: inputs["caseId?"]) as? String)?.takeIf { it.isNotBlank() }
                ?: node.params["caseId"]?.takeIf { it.isNotBlank() }
                ?: "default"
            val key = "council-case/$caseId"
            val bb = seams.blackboardGet(key)
            val couch = seams.couchGet(key)
            if (couch?.get("recordPhase") in setOf(CouncilRecordPhase.RECORDED.name, CouncilRecordPhase.PUBLISHED.name)) {
                seams.lifecycleReceipt(caseId, couch?.get("recorded") as? String, couch?.get("decisionStatus") as? String)
            }
            if (bb == null && couch == null) {
                mapOf("case" to mapOf("error" to "not_found", "caseId" to caseId))
            } else {
                // The durable journal wins over a partially updated volatile index.
                val index = if (couch?.containsKey("recordPhase") == true) couch else (bb ?: couch)
                fun field(name: String): String? =
                    index?.get(name)?.toString()?.takeIf { it.isNotBlank() }
                        ?: couch?.get(name)?.toString()?.takeIf { it.isNotBlank() }
                val verdictCid = field("verdictCid")
                // The blackboard index fact carries no transcriptCid — fall
                // back to the couch doc, then to the CAS-stored case doc.
                val transcriptCid = field("transcriptCid")
                    ?: field("caseCid")
                        ?.let { seams.casGet(it)?.decodeToString() }
                        ?.let { runCatching { reifyMap(it) }.getOrNull() }
                        ?.get("transcriptCid")?.toString()
                mapOf("case" to linkedMapOf(
                    "caseId" to caseId,
                    "index" to index,
                    "transcript" to transcriptCid?.let { seams.casGet(it)?.decodeToString() },
                    "verdict" to verdictCid?.let { seams.casGet(it)?.decodeToString() },
                ))
            }
        },
    )

    /** The full contract: which node types the council registry serves. */
    fun servedTypes(): Set<String> = setOf(
        "council.seat", "text.fold", "record.fold", "ruling.parse",
        "coalesce", "council.convene", "council.record", "council.case",
    )

    /**
     * A convening config from loosely-typed JSON — absent fields fall back
     * to [CouncilConfig]'s defaults. Panels accept explicit `personas` or a
     * bare `experts` count (default personas cycled to that many seats).
     */
    internal fun parseCouncilConfig(map: Map<String, Any?>): CouncilConfig {
        val defaults = CouncilConfig()
        fun int(v: Any?, dflt: Int): Int = when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: dflt
            else -> dflt
        }
        fun bool(v: Any?, dflt: Boolean): Boolean = when (v) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull() ?: dflt
            else -> dflt
        }
        val panels = (map["panels"] as? List<*>)?.mapNotNull { p ->
            (p as? Map<*, *>)?.let { pm ->
                val personas = (pm["personas"] as? List<*>)?.map { it.toString() }
                    ?: (pm["experts"] as? Number)?.toInt()?.let { n ->
                        List(n) { i -> CouncilProgram.DEFAULT_PERSONAS[i % CouncilProgram.DEFAULT_PERSONAS.size] }
                    }
                    ?: CouncilProgram.DEFAULT_PERSONAS
                PanelSpec(
                    name = pm["name"]?.toString() ?: "panel",
                    charge = pm["charge"]?.toString() ?: "",
                    personas = personas,
                )
            }
        }?.takeIf { it.isNotEmpty() } ?: defaults.panels
        return CouncilConfig(
            caseId = map["caseId"]?.toString()?.takeIf { it.isNotBlank() } ?: defaults.caseId,
            panels = panels,
            rounds = int(map["rounds"], defaults.rounds),
            clarify = bool(map["clarify"], defaults.clarify),
            mistrial = bool(map["mistrial"], defaults.mistrial),
            roster = (map["roster"] as? List<*>)?.map { it.toString() }?.takeIf { it.isNotEmpty() }
                ?: defaults.roster,
            synthesisModel = map["synthesisModel"]?.toString()?.takeIf { it.isNotBlank() },
            rulingModel = map["rulingModel"]?.toString()?.takeIf { it.isNotBlank() },
        )
    }

    /** Strict boolean: true ONLY on JSON true or the string "true". */
    private fun strictBool(v: Any?): Boolean = v == true || v == "true"

    /** A caseId as a KIF atom fragment — anything exotic becomes '_'. */
    private fun kifAtom(s: String): String = buildString {
        for (c in s) append(if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.') c else '_')
    }

    /** Flatten a MANY-port value (scalar-unwrap tolerated) to text parts. */
    private fun flattenText(v: Any?, out: MutableList<String>) {
        when (v) {
            null -> {}
            is String -> out.add(v)
            is List<*> -> for (e in v) flattenText(e, out)
            is Map<*, *> -> out.add(jsonOf(v))
            else -> out.add(v.toString())
        }
    }

    /** Flatten a MANY-port value to turn-record maps, wire order kept. */
    private fun flattenTurns(v: Any?, out: MutableList<Map<String, Any?>>) {
        when (v) {
            null -> {}
            is Map<*, *> -> out.add(v.entries.associate { (k, value) -> k.toString() to value })
            is List<*> -> for (e in v) flattenTurns(e, out)
            else -> {}
        }
    }

    /** Project the actual fold's row slots; ignored scalars never acquire a retained row. */
    private fun flattenTurns(actual: Any?, retained: Any?, out: MutableList<Any?>) {
        when (actual) {
            is Map<*, *> -> out.add(if (retained is Map<*, *>)
                retained.entries.associate { (key, value) -> key.toString() to value }
            else LcncProjection.Absent)
            is List<*> -> {
                val projected = retained as? List<*>
                for (index in actual.indices) flattenTurns(actual[index],
                    if (projected != null && index < projected.size) projected[index] else LcncProjection.Absent, out)
            }
            else -> {}
        }
    }

    /** The LAST balanced `{…}` object in [text] that parses as a JSON map. */
    private fun lastBalancedObject(text: String): Map<String, Any?>? {
        var end = text.lastIndexOf('}')
        while (end >= 0) {
            // A candidate closes outside its strings. Walking backward keeps
            // quoted braces as data; prose before its opening is never visited.
            var depth = 0
            var start = -1
            var quoted = false
            for (j in end downTo 0) {
                if (text[j] == '"') {
                    var prior = j - 1
                    while (prior >= 0 && text[prior] == '\\') prior--
                    if ((j - prior - 1) % 2 == 0) quoted = !quoted
                    continue
                }
                if (quoted) continue
                when (text[j]) {
                    '}' -> depth++
                    '{' -> depth--
                }
                if (depth == 0) {
                    start = j
                    break
                }
            }
            if (start >= 0) {
                val parsed = runCatching { reify(text.substring(start, end + 1)) }.getOrNull()
                if (parsed is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    return parsed as Map<String, Any?>
                }
            }
            end = if (end > 0) text.lastIndexOf('}', end - 1) else -1
        }
        return null
    }

    private fun countNodes(nodes: Series<LcncNode>): Int {
        var n = 0
        for (i in 0 until nodes.size) n += 1 + countNodes(nodes[i].children)
        return n
    }

    private fun countType(nodes: Series<LcncNode>, type: String): Int {
        var n = 0
        for (i in 0 until nodes.size) {
            if (nodes[i].type == type) n++
            n += countType(nodes[i].children, type)
        }
        return n
    }
}
