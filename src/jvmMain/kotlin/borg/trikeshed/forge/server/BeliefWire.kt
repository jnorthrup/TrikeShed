package borg.trikeshed.forge.server

import borg.trikeshed.cas.GroupingReorientation
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.memory.HermesMemoryFiles
import borg.trikeshed.narsese.AttentionEconomy
import borg.trikeshed.narsese.GroupCoherenceEnactment
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.BeliefIntake
import borg.trikeshed.narsese.CuratorImpulseElement
import borg.trikeshed.narsese.CuratorImpulseKind
import borg.trikeshed.narsese.Nal
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.narsese.ReplayScenario
import borg.trikeshed.narsese.ReplayTurn
import borg.trikeshed.narsese.TurnReviewElement
import borg.trikeshed.parse.json.JsonSupport

/**
 * BeliefWire — the NARS curation loop's HTTP window, mounted on the kanban
 * listener like GraalWire/VmWire:
 *
 *   GET  /api/beliefs                top-k beliefs with budgets + curation states
 *   GET  /api/beliefs/render        the bounded MEMORY render (frozen-snapshot form)
 *   POST /api/beliefs/review        {facts:[{verb,ok,context,object?}], turnSucceeded} → pure induction pass
 *   POST /api/beliefs/tick          one DecayTick (curation pulse, on demand — demos/tests)
 *   POST /api/beliefs/teach         {impulses:[{kind,subject,rationale}], scenarios:[...]} → curator hindsight pass (W5.3)
 *   POST /api/beliefs/query         {pattern:"kif"} → bank solver query (W5.3)
 *
 * The loop, drivable end-to-end: mint (seed/pen/user) → review (induction) →
 * tick (attention decay → floor crossings → eviction/spill) → render (bounded
 * selection) → user edit → re-ingest → revise. /panels can build a live
 * curation dashboard from these routes with http.get/timer nodes.
 */
class BeliefWire(
    private val bag: BeliefBagElement,
    private val review: TurnReviewElement,
    private val memoryFiles: HermesMemoryFiles?,
    /** W5.3: the live curator element; null = teach/query degrade to 503. */
    private val curator: CuratorImpulseElement? = null,
) {
    suspend fun route(
        method: String,
        path: String,
        text: String,
        respond: (suspend (ByteArray) -> Unit)?,
    ): JvmKanbanServer.HttpResponse? {
        val p = path.substringBefore('?')
        return when {
            method == "GET" && p == "/api/beliefs" -> {
                val top = bag.recallTop(64)
                val rows = (0 until top.size).map { i ->
                    val (signal, budget) = top[i]
                    mapOf(
                        "angular" to signal.angular.toString(),
                        "relation" to signal.relation.name,
                        "expectation" to Nal.truthOf(signal.evidence).expectation(),
                        "evidence" to mapOf("positive" to signal.evidence.positive, "negative" to signal.evidence.negative),
                        "priority" to budget.pf,
                        "durability" to budget.df,
                        "quality" to budget.qf,
                        "state" to AttentionEconomy.stateOf(budget).name,
                        "subjectCid" to signal.subjectCid,
                        "provenanceCid" to signal.provenanceCid,
                    )
                }
                json(mapOf("size" to bag.size, "capacity" to bag.capacity, "beliefs" to rows))
            }

            method == "GET" && p == "/api/beliefs/render" -> {
                val rendered = memoryFiles?.renderTo()
                    ?: return json(mapOf("error" to "memory files not wired"), 503)
                JvmKanbanServer.HttpResponse(200, rendered.a, "text/plain; charset=utf-8")
            }

            method == "POST" && p == "/api/beliefs/review" -> {
                val req = parse(text)
                val turnSucceeded = req["turnSucceeded"] as? Boolean ?: true
                val facts = (req["facts"] as? List<*>).orEmpty().mapNotNull { f ->
                    val m = f as? Map<*, *> ?: return@mapNotNull null
                    TurnReviewElement.TurnFact(
                        verb = m["verb"]?.toString() ?: return@mapNotNull null,
                        ok = m["ok"] as? Boolean ?: true,
                        contextTerm = m["context"]?.toString() ?: return@mapNotNull null,
                        objectTerm = m["object"]?.toString(),
                    )
                }
                val landed = review.reviewTurn(facts, turnSucceeded)
                for ((angular, gloss) in landed) memoryFiles?.gloss(angular, gloss)
                json(mapOf("landed" to landed.size, "bagSize" to bag.size, "factsParsed" to facts.size, "reviewState" to review.state.name))
            }

            method == "POST" && p == "/api/beliefs/tick" -> {
                bag.intake.send(BeliefIntake.DecayTick)
                json(mapOf("ok" to true, "bagSize" to bag.size))
            }

            // W5.3: curator hindsight teaching — impulses + replay scenarios in,
            // landed (angular → gloss) pairs out. Quota-free by construction.
            method == "POST" && p == "/api/beliefs/teach" -> {
                val c = curator ?: return json(mapOf("error" to "curator not wired"), 503)
                val req = runCatching { JsonSupport.parse(rawBody(text)) as? Map<*, *> }.getOrNull()
                    ?: return json(mapOf("error" to "bad_json"), 400)
                val impulses = ((req["impulses"] as? List<*>).orEmpty()).mapNotNull { raw ->
                    val m = raw as? Map<*, *> ?: return@mapNotNull null
                    val subject = m["subject"]?.toString() ?: return@mapNotNull null
                    val kind = runCatching { CuratorImpulseKind.valueOf(m["kind"]?.toString()?.uppercase() ?: "") }
                        .getOrNull() ?: CuratorImpulseKind.ADOPT
                    borg.trikeshed.narsese.CuratorImpulse(
                        kind = kind,
                        subject = subject,
                        rationale = m["rationale"]?.toString() ?: "",
                        proposalCid = m["proposalCid"]?.toString(),
                    )
                }.toSeries()
                val scenarios = ((req["scenarios"] as? List<*>).orEmpty()).mapNotNull { raw ->
                    val m = raw as? Map<*, *> ?: return@mapNotNull null
                    val sid = m["scenarioId"]?.toString() ?: return@mapNotNull null
                    val subject = m["impulseSubject"]?.toString() ?: return@mapNotNull null
                    val turns = ((m["turns"] as? List<*>).orEmpty()).mapNotNull { t ->
                        (t as? Map<*, *>)?.let {
                            ReplayTurn(
                                role = it["role"]?.toString() ?: "user",
                                text = it["text"]?.toString() ?: "",
                            )
                        }
                    }.toSeries()
                    ReplayScenario(sid, subject, turns)
                }.toSeries()
                // R6: enacted group-coherence resolutions ride the same teach call. The wire is a
                // reporter — enactment (human-approved) happened upstream; resolutionCid is trusted
                // when supplied, else recomputed purely from the same canonical body enact() writes.
                val asInt: (Any?) -> Int? = { v ->
                    (v as? Number)?.toInt() ?: v?.toString()?.toDoubleOrNull()?.toInt()
                }
                val groupings = ((req["groupings"] as? List<*>).orEmpty()).mapNotNull { raw ->
                    val m = raw as? Map<*, *> ?: return@mapNotNull null
                    val ring8 = asInt(m["ring8"]) ?: return@mapNotNull null
                    val members = ((m["members"] as? List<*>).orEmpty()).mapNotNull { v ->
                        runCatching { borg.trikeshed.job.ContentId(v.toString()) }.getOrNull()
                    }
                    val samples = ((m["sampleMembers"] as? List<*>).orEmpty()).mapNotNull { v ->
                        runCatching { borg.trikeshed.job.ContentId(v.toString()) }.getOrNull()
                    }
                    val resolution = when (m["action"]?.toString()) {
                        "relabel" -> GroupingReorientation.GroupingResolution.Relabel(
                            m["label"]?.toString() ?: return@mapNotNull null,
                        )
                        "split" -> GroupingReorientation.GroupingResolution.Split(
                            asInt(m["low"]) ?: return@mapNotNull null,
                            asInt(m["high"]) ?: return@mapNotNull null,
                        )
                        "merge" -> GroupingReorientation.GroupingResolution.Merge(
                            asInt(m["other"]) ?: return@mapNotNull null,
                            asInt(m["into"]) ?: return@mapNotNull null,
                        )
                        "reject" -> GroupingReorientation.GroupingResolution.Reject
                        else -> return@mapNotNull null
                    }
                    val post = GroupingReorientation.GroupingPost(
                        group = GroupingReorientation.Group(ring8, members),
                        currentLabel = m["currentLabel"]?.toString() ?: "",
                        proposedLabel = null,
                        proposedSplit = null,
                        proposedMergeWith = null,
                        evidence = GroupingReorientation.ProposalEvidence(samples, emptyList()),
                        origin = m["origin"]?.toString() ?: "wire",
                    )
                    val cid = m["resolutionCid"]?.toString()
                        ?: GroupingReorientation.resolutionCid(post, resolution).value
                    GroupCoherenceEnactment(post, resolution, cid)
                }.toSeries()
                val landed = c.teach(impulses, scenarios, groupings)
                json(mapOf(
                    "verdict" to "ok",
                    "landed" to landed.size,
                    "groupings" to groupings.size,
                    "glosses" to landed.map { (_, gloss) -> gloss },
                    "knowledgeSize" to c.knowledgeBank.asserts().size,
                ))
            }

            // W5.3: query the banked knowledge through the KIF solver.
            method == "POST" && p == "/api/beliefs/query" -> {
                val c = curator ?: return json(mapOf("error" to "curator not wired"), 503)
                val req = parse(text)
                val pattern = req["pattern"]?.toString()
                    ?: return json(mapOf("error" to "pattern required"), 400)
                val results = runCatching { c.queryBank(pattern) }
                    .getOrElse { return json(mapOf("error" to (it.message ?: "query failed")), 400) }
                json(mapOf("verdict" to "ok", "pattern" to pattern, "results" to results))
            }

            // The resonance of a solver proposal: support + refutation fronts, one sweep
            method == "POST" && p == "/api/beliefs/resonate" -> {
                val req = parse(text)
                val goal = req["goal"]?.toString() ?: return json(mapOf("error" to "goal required"), 400)
                val k = (req["k"] as? Number)?.toInt() ?: 8
                val centroid = borg.trikeshed.narsese.AngularCodec.encode(
                    relation = borg.trikeshed.narsese.RelationKind.CAUSALITY,
                    taxonomyKey = req["taxonomy"]?.toString(),
                    subjectTerm = goal,
                )
                if (req["mode"]?.toString() == "whitened") {
                    // NAL-9 path: Mahalanobis over the moment field, β from the temperature seam
                    val r = bag.resonateWhitened(centroid, k)
                    fun wrow(pk: borg.trikeshed.narsese.MomentField.Peak) = mapOf(
                        "angular" to pk.angular.toString(),
                        "gloss" to memoryFiles?.glossOf(pk.angular),
                        "mahalanobis" to pk.distance,
                        "activation" to pk.activation,
                        "pri" to pk.pri,
                        "frequency" to pk.freq,
                        "level" to bag.levelOf(pk.angular),
                    )
                    return json(mapOf(
                        "mode" to "whitened",
                        "synonymPeaks" to r.synonyms.map(::wrow),
                        "antonymPeaks" to r.antonyms.map(::wrow),
                    ))
                }
                val r = bag.resonate(centroid, k)
                fun row(s: borg.trikeshed.narsese.HijackBeliefBag.Slot) = mapOf(
                    "angular" to s.angular.toString(),
                    "gloss" to memoryFiles?.glossOf(s.angular),
                    "expectation" to Nal.truthOf(s.signal.evidence).expectation(),
                    "frequency" to Nal.truthOf(s.signal.evidence).frequency,
                    "pri" to s.pri,
                    "level" to bag.levelOf(s.angular),
                    "relation" to s.signal.relation.name,
                )
                json(mapOf(
                    "synonymPeaks" to r.synonyms.map(::row),
                    "antonymPeaks" to r.antonyms.map(::row),
                ))
            }

            // NAL-9 introspection: the system's beliefs about its own belief field
            method == "GET" && p == "/api/beliefs/introspect" -> {
                val field = bag.field()
                val fields = borg.trikeshed.narsese.AngularCodec.Fields
                val axis = field.cruxAxis()
                val cruxBits = axis.withIndex().sortedByDescending { kotlin.math.abs(it.value) }.take(6)
                    .map { mapOf("bit" to it.index, "loading" to it.value) }
                val concepts = field.principalConcepts(3).map { (eigenvalue, vec) ->
                    mapOf(
                        "variance" to eigenvalue,
                        "topBits" to vec.withIndex().sortedByDescending { kotlin.math.abs(it.value) }.take(6)
                            .map { mapOf("bit" to it.index, "loading" to it.value) },
                    )
                }
                val penSigs = listOf("mux_converse", "bag_recall", "bag_assert", "crumb_walk", "skill_scribe")
                    .map { borg.trikeshed.narsese.AngularCodec.taxonomySigOfKey("pen/" + it) }.toSet()
                val penAnomaly = field.hotelling { coord -> fields.taxonomySigOf(coord) in penSigs }
                json(mapOf(
                    "beliefs" to field.n,
                    "cruxAxisTopBits" to cruxBits,
                    "principalConcepts" to concepts,
                    "penCohortT2" to penAnomaly,
                ))
            }

            // Turtle/RDF or KIF body → NAL beliefs, copulas intact (KgNalBridge)
            method == "POST" && p == "/api/beliefs/kg" -> {
                val kgText = rawBody(text)
                if (kgText.isBlank()) return json(mapOf("error" to "empty body"), 400)
                val sourceCid = borg.trikeshed.job.ContentId.of(kgText.encodeToByteArray())
                val mapped = borg.trikeshed.narsese.KgNalBridge.bridge(kgText)
                var minted = 0
                for (m in mapped) {
                    val signal = m.signal(sourceCid.value)
                    bag.intake.send(
                        BeliefIntake.Mint(
                            signal,
                            borg.trikeshed.cursor.BudgetCoord(0.6f, 0.4f, 0.6f),
                            gloss = m.gloss(),
                        ),
                    )
                    memoryFiles?.gloss(signal.angular, m.gloss())
                    minted++
                }
                json(mapOf(
                    "format" to borg.trikeshed.narsese.KgNalBridge.sniff(kgText)?.name,
                    "statements" to mapped.size,
                    "minted" to minted,
                    "copulas" to mapped.groupingBy { it.copula.name }.eachCount(),
                ))
            }

            else -> null
        }
    }

    private fun json(value: Any?, status: Int = 200): JvmKanbanServer.HttpResponse =
        JvmKanbanServer.HttpResponse(status, JsonSupport.stringify(value))

    /** Extra routes receive the RAW request (headers + body) as `text`; split first. */
    private fun rawBody(text: String): String = when {
        "\r\n\r\n" in text -> text.substringAfter("\r\n\r\n")
        "\n\n" in text -> text.substringAfter("\n\n")
        else -> text
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(text: String): Map<String, Any?> {
        val body = rawBody(text)
        if (body.isBlank()) return emptyMap()
        return runCatching { JsonSupport.parse(body) as? Map<String, Any?> }.getOrNull() ?: emptyMap()
    }
}
