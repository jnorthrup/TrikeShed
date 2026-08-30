package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.json.JsonSupport
import kotlin.coroutines.cancellation.CancellationException

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
    /** Upsert a durable document: (id, body). */
    val couchPut: (String, Map<String, Any?>) -> Unit,
    /** Advance the per-case lifecycle to ruled; returns the recorded cid. */
    val recordRuling: suspend (caseId: String, verdictCid: String, transcriptCid: String) -> String,
    /** Advance the per-case lifecycle to mistrial; returns the recorded cid. */
    val recordMistrial: suspend (caseId: String, reason: String) -> String,
    val blackboardGet: (String) -> Map<String, String>?,
    val couchGet: (String) -> Map<String, Any?>?,
    val casGet: (String) -> ByteArray?,
) {
    companion object {
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
                verdictCid
            },
            recordMistrial = { caseId, reason ->
                store.mistrials.add(caseId to reason)
                "mistrial:$caseId"
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
        "council.seat" to LcncNodeRunner { node, inputs ->
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
                dialog.seat(call)
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
        // the value itself, not a list). A part that already carries a
        // bracket header (a seat's `labeled`) keeps it; otherwise `numbered`
        // prefixes its ordinal.
        "text.fold" to LcncNodeRunner { node, inputs ->
            val label = node.params["label"].orEmpty()
            val separator = node.params["separator"] ?: "\n\n---\n\n"
            val numbered = node.params["numbered"] != "false"
            val parts = ArrayList<String>()
            flattenText(inputs["parts"] ?: inputs["parts?"], parts)
            val body = parts.filter { it.isNotBlank() }.mapIndexed { i, p ->
                if (!numbered || p.startsWith("[")) p else "(${i + 1}) $p"
            }
            val lines = (if (label.isBlank()) emptyList() else listOf("== $label ==")) + body
            mapOf("text" to lines.joinToString(separator))
        },

        // Provenance gatherer: MANY `parts` of turn-record maps (or lists of
        // them) flattened to one list in wire order — gathered INSIDE each
        // ring so no inner→outer wire ever exists.
        "record.fold" to LcncNodeRunner { _, inputs ->
            val turns = ArrayList<Map<String, Any?>>()
            flattenTurns(inputs["parts"] ?: inputs["parts?"], turns)
            mapOf("turns" to turns)
        },

        // The verdict extractor. Locates the LAST balanced {...} object in
        // the ruling text and parses it. Booleans are STRICT: true ONLY on
        // JSON true or the string "true" — on ANY failure path (no JSON,
        // malformed JSON, a seat-failure banner) both come back false, so
        // the clarify/mistrial ring guards can never see truthy garbage.
        "ruling.parse" to LcncNodeRunner { _, inputs ->
            val text = ((inputs["text"] as? String) ?: (inputs["text?"] as? String)).orEmpty()
            val parsed = if (text.startsWith("[SEAT FAILED")) null else lastBalancedObject(text)
            if (parsed == null) mapOf(
                "verdict" to mapOf("disposition" to text),
                "needsClarification" to false,
                "clarificationQuestion" to "",
                "mistrial" to false,
                "text" to text,
            ) else mapOf(
                "verdict" to parsed,
                "needsClarification" to strictBool(parsed["needsClarification"]),
                "clarificationQuestion" to (parsed["clarificationQuestion"]?.toString() ?: ""),
                "mistrial" to strictBool(parsed["mistrial"]),
                "text" to text,
            )
        },

        // Clarified-over-original: `a?` wins when present and non-blank; a
        // skipped ring's yield stays absent, so `b` (the original) stands.
        "coalesce" to LcncNodeRunner { _, inputs ->
            val a = inputs["a"] ?: inputs["a?"]
            val b = inputs["b"] ?: inputs["b?"]
            mapOf("value" to if (a != null && (a !is String || a.isNotBlank())) a else b)
        },

        // Re-geometry: a convening config (wired map or JSON string, merged
        // OVER the `config` param) in, the drawn program document out —
        // embeddable directly as /api/lcnc/run's `document`. Bound
        // violations from CouncilProgram.build propagate loudly.
        "council.convene" to LcncNodeRunner { node, inputs ->
            val base = node.params["config"]?.takeIf { it.isNotBlank() }
                ?.let { JsonSupport.parseMap(it) } ?: emptyMap()
            val over: Map<String, Any?> = when (val c = inputs["config"] ?: inputs["config?"]) {
                null -> emptyMap()
                is Map<*, *> -> c.entries.associate { (k, v) -> k.toString() to v }
                is String -> if (c.isBlank()) emptyMap() else JsonSupport.parseMap(c)
                else -> emptyMap()
            }
            val cfg = parseCouncilConfig(base + over)
            val program = CouncilProgram.build(cfg)
            val experts = cfg.panels.map { it.personas.size }
            // reify types every JSON number as Double; `seq` is the one Int
            // toJson emits, so restore it — the emitted document must
            // re-stringify byte-identical to the builder's (test-pinned).
            val doc = LinkedHashMap(JsonSupport.parseMap(LcncProgramConfix.toJson(program)))
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
        // cid verification (mismatches counted, never thrown), transcript +
        // verdict + case doc to CAS, blackboard index fact, durable couch
        // doc, the (ruling …) KIF assertion, and the per-case lifecycle
        // advance. Commit-time mistrial rule: all seats refused OR a banner
        // verdict OR the verdict itself declares mistrial — never silent.
        "council.record" to LcncNodeRunner { node, inputs ->
            val verdict = inputs["verdict"] ?: inputs["verdict?"]
            require(verdict != null) { "council.record: no verdict wired" }
            val caseId = ((inputs["caseId"] ?: inputs["caseId?"]) as? String)?.takeIf { it.isNotBlank() }
                ?: node.params["caseId"]?.takeIf { it.isNotBlank() }
                ?: "default"
            val documentCid = ((inputs["documentCid"] ?: inputs["documentCid?"]) as? String)
                ?.takeIf { it.isNotBlank() }

            val turns = ArrayList<Map<String, Any?>>()
            flattenTurns(inputs["turns"] ?: inputs["turns?"], turns)
            var cidMismatches = 0
            for (turn in turns) {
                val text = turn["text"] as? String ?: continue
                val declared = turn["cid"] as? String ?: continue
                val bytes = text.encodeToByteArray()
                seams.casPut(bytes)
                if (ContentId.of(bytes).value != declared) cidMismatches++
            }
            val seatCount = turns.size
            val seatFailures = turns.count { it["status"] == "refused" }

            val transcriptParts = ArrayList<String>()
            flattenText(inputs["transcript"] ?: inputs["transcript?"], transcriptParts)
            val transcriptDoc = transcriptParts.filter { it.isNotBlank() }.joinToString("\n\n---\n\n")
            val transcriptCid = seams.casPut(transcriptDoc.encodeToByteArray())

            val verdictText = if (verdict is String) verdict else JsonSupport.stringify(verdict)
            val verdictCid = seams.casPut(verdictText.encodeToByteArray())

            val verdictMistrial = (verdict as? Map<*, *>)?.get("mistrial").let { it == true || it == "true" }
            val status = when {
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
                "status" to status,
                "turns" to turns,
            )
            val caseCid = seams.casPut(JsonSupport.stringify(caseDoc).encodeToByteArray())

            val recorded = if (status == "mistrial") {
                seams.recordMistrial(
                    caseId,
                    "seatFailures=$seatFailures/$seatCount; verdict=${verdictText.take(160)}",
                )
            } else {
                seams.recordRuling(caseId, verdictCid, transcriptCid)
            }
            seams.blackboardPut(
                "council-case/$caseId",
                linkedMapOf(
                    "caseCid" to caseCid,
                    "verdictCid" to verdictCid,
                    "documentCid" to documentCid.orEmpty(),
                    "status" to status,
                ),
                "council.record",
            )
            seams.couchPut("council-case/$caseId", caseDoc)
            if (documentCid != null) {
                seams.kifSink(
                    "(ruling case_${kifAtom(caseId)} doc_${documentCid.removePrefix("sha256:")} " +
                        verdictCid.removePrefix("sha256:") + ")",
                )
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
        },

        // Read-back veneer: index fact (blackboard, falling back to the
        // durable couch doc) plus the transcript/verdict bytes from CAS —
        // the GET /api/lcnc/council/{caseId} route is a thin shell over this.
        "council.case" to LcncNodeRunner { node, inputs ->
            val caseId = ((inputs["caseId"] ?: inputs["caseId?"]) as? String)?.takeIf { it.isNotBlank() }
                ?: node.params["caseId"]?.takeIf { it.isNotBlank() }
                ?: "default"
            val key = "council-case/$caseId"
            val bb = seams.blackboardGet(key)
            val couch = seams.couchGet(key)
            if (bb == null && couch == null) {
                mapOf("case" to mapOf("error" to "not_found", "caseId" to caseId))
            } else {
                fun field(name: String): String? =
                    bb?.get(name) ?: couch?.get(name)?.toString()?.takeIf { it.isNotBlank() }
                val verdictCid = field("verdictCid")
                // The blackboard index fact carries no transcriptCid — fall
                // back to the couch doc, then to the CAS-stored case doc.
                val transcriptCid = field("transcriptCid")
                    ?: field("caseCid")
                        ?.let { seams.casGet(it)?.decodeToString() }
                        ?.let { runCatching { JsonSupport.parseMap(it) }.getOrNull() }
                        ?.get("transcriptCid")?.toString()
                mapOf("case" to linkedMapOf(
                    "caseId" to caseId,
                    "index" to (bb ?: couch),
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
            is Map<*, *> -> out.add(JsonSupport.stringify(v))
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

    /** The LAST balanced `{…}` object in [text] that parses as a JSON map. */
    private fun lastBalancedObject(text: String): Map<String, Any?>? {
        var end = text.lastIndexOf('}')
        while (end >= 0) {
            // Walk backward from this close brace to its matching open.
            var depth = 0
            var start = -1
            for (j in end downTo 0) {
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
                val parsed = runCatching { JsonSupport.parse(text.substring(start, end + 1)) }.getOrNull()
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
