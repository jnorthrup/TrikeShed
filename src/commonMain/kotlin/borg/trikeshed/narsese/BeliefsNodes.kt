package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.parse.json.JsonSupport
import kotlin.math.abs

/**
 * Belief-field LCNC nodes — the BeliefWire HTTP behaviors (`/api/beliefs/
 * introspect`, `/resonate`, `/review`) as first-class node runners, plus the
 * missing composability legos over [BeliefIntake] and [AngularCodec]:
 * `nal.attend` (budget rekey), `nal.reinforce` (evidence delta), and
 * `nal.encode` (mint the 64-bit centroid `nal.recall mode=near` demands).
 *
 * commonMain by construction: the jvm-only gloss store (HermesMemoryFiles)
 * enters through the [glossOf]/[glossSink] seams, never as a dependency.
 */
object BeliefsNodes {

    /** BeliefWire's hard-coded pen cohort — the legion-alarm default. */
    private val PEN_COHORT = listOf("mux_converse", "bag_recall", "bag_assert", "crumb_walk", "skill_scribe")
        .map { "pen/$it" }

    /** Ports arrive as authored — a wire into `goal?` keys the map with the `?`. */
    private fun port(inputs: Map<String, Any?>, name: String): Any? =
        inputs[name] ?: inputs["$name?"]

    private fun parseAngular(raw: Any?): Long? = when (raw) {
        is Long -> raw
        is Number -> raw.toLong()
        else -> raw?.toString()?.trim()?.toLongOrNull()
    }

    /**
     * `beliefs.introspect` — NAL-9 introspection over the moment field
     * (BeliefWire GET /api/beliefs/introspect): crux-axis top bits, principal
     * concepts, cohort Hotelling T². `cohortTaxonomy` (comma-separated
     * taxonomy keys) overrides the pen-cohort default.
     */
    fun introspectRunner(bag: BeliefBagElement): LcncNodeRunner = LcncNodeRunner { node, _ ->
        val field = bag.field()
        val cruxTop = node.params["cruxTop"]?.toIntOrNull() ?: 6
        val conceptsK = node.params["concepts"]?.toIntOrNull() ?: 3
        val axis = field.cruxAxis()
        val cruxBits = axis.withIndex().sortedByDescending { abs(it.value) }.take(cruxTop)
            .map { mapOf("bit" to it.index, "loading" to it.value) }
        val concepts = field.principalConcepts(conceptsK).map { (eigenvalue, vec) ->
            mapOf(
                "variance" to eigenvalue,
                "topBits" to vec.withIndex().sortedByDescending { abs(it.value) }.take(cruxTop)
                    .map { mapOf("bit" to it.index, "loading" to it.value) },
            )
        }
        val cohortKeys = node.params["cohortTaxonomy"]?.split(',')
            ?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
            ?: PEN_COHORT
        val cohortSigs = cohortKeys.map { AngularCodec.taxonomySigOfKey(it) }.toSet()
        val cohortT2 = field.hotelling { coord -> AngularCodec.Fields.taxonomySigOf(coord) in cohortSigs }
        mapOf(
            "field" to mapOf(
                "size" to field.n,
                "cruxBits" to cruxBits,
                "concepts" to concepts,
                "cohortT2" to cohortT2,
            ),
        )
    }

    /**
     * `beliefs.resonate` — support/refutation fronts of a goal term
     * (BeliefWire POST /api/beliefs/resonate): the centroid is encoded
     * server-side from goal + taxonomy, `mode=whitened` takes the Mahalanobis
     * path over the moment field, anything else the raw hamming sweep.
     * Contract-name aliases `synonyms`/`antonyms` ride along.
     */
    fun resonateRunner(
        bag: BeliefBagElement,
        glossOf: (Long) -> String? = { null },
    ): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val goal = port(inputs, "goal")?.toString()?.takeIf { it.isNotBlank() }
            ?: node.params["goal"]?.takeIf { it.isNotBlank() }
            ?: node.params["subject"]?.takeIf { it.isNotBlank() }
            ?: return@LcncNodeRunner mapOf(
                "synonymPeaks" to emptyList<Any?>(),
                "antonymPeaks" to emptyList<Any?>(),
                "error" to "goal required",
            )
        val k = node.params["k"]?.toIntOrNull() ?: 8
        val mode = node.params["mode"] ?: "whitened"
        val centroid = AngularCodec.encode(
            relation = RelationKind.CAUSALITY,
            taxonomyKey = node.params["taxonomy"],
            subjectTerm = goal,
        )
        val (syn, ant) = if (mode == "whitened") {
            val r = bag.resonateWhitened(centroid, k)
            fun wrow(pk: MomentField.Peak) = mapOf(
                "angular" to pk.angular.toString(),
                "gloss" to glossOf(pk.angular),
                "mahalanobis" to pk.distance,
                "activation" to pk.activation,
                "pri" to pk.pri,
                "frequency" to pk.freq,
                "level" to bag.levelOf(pk.angular),
            )
            r.synonyms.map(::wrow) to r.antonyms.map(::wrow)
        } else {
            val r = bag.resonate(centroid, k)
            fun row(s: HijackBeliefBag.Slot) = mapOf(
                "angular" to s.angular.toString(),
                "gloss" to glossOf(s.angular),
                "expectation" to Nal.truthOf(s.signal.evidence).expectation(),
                "frequency" to Nal.truthOf(s.signal.evidence).frequency,
                "pri" to s.pri,
                "level" to bag.levelOf(s.angular),
                "relation" to s.signal.relation.name,
            )
            r.synonyms.map(::row) to r.antonyms.map(::row)
        }
        mapOf(
            "mode" to mode,
            "synonymPeaks" to syn,
            "antonymPeaks" to ant,
            "synonyms" to syn,
            "antonyms" to ant,
        )
    }

    /**
     * `beliefs.review` — the pure induction pass (BeliefWire POST
     * /api/beliefs/review): `facts` is a list of {verb, ok, context, object?}
     * (a raw JSON string is parsed; anything else lands zero facts and the
     * node still completes — the preset's introspect→review wire degrades,
     * never dies). Landed glosses go through [glossSink].
     */
    fun reviewRunner(
        review: TurnReviewElement,
        glossSink: (Long, String) -> Unit = { _, _ -> },
    ): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val turnSucceeded = node.params["turnSucceeded"]?.toBooleanStrictOrNull() ?: true
        val raw = port(inputs, "facts")
        val list = when (raw) {
            is List<*> -> raw
            is String -> runCatching { JsonSupport.parse(raw) as? List<*> }.getOrNull().orEmpty()
            else -> emptyList<Any?>()
        }
        val facts = list.mapNotNull { f ->
            val m = f as? Map<*, *> ?: return@mapNotNull null
            TurnReviewElement.TurnFact(
                verb = m["verb"]?.toString() ?: return@mapNotNull null,
                ok = m["ok"] as? Boolean ?: true,
                contextTerm = m["context"]?.toString() ?: return@mapNotNull null,
                objectTerm = m["object"]?.toString(),
            )
        }
        val landed = review.reviewTurn(facts, turnSucceeded)
        for ((angular, gloss) in landed) glossSink(angular, gloss)
        mapOf(
            "landed" to landed.map { (angular, gloss) ->
                mapOf("angular" to angular.toString(), "gloss" to gloss)
            },
            "glosses" to landed.map { it.second },
            "factsParsed" to facts.size,
        )
    }

    /**
     * `nal.attend` — [BeliefIntake.Attend] as a lego: rekey an angular to a
     * new budget, evidence untouched. Unsupplied p/d/q components keep the
     * resident budget's value (0.5 when the angular is not resident — the
     * bag's Attend on a non-resident angular is a no-op anyway).
     */
    fun attendRunner(bag: BeliefBagElement): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val angular = parseAngular(port(inputs, "angular") ?: node.params["angular"])
            ?: return@LcncNodeRunner mapOf("attended" to mapOf("error" to "angular required"))
        val base = bag.budgetOf(angular)
        val p = node.params["p"]?.toFloatOrNull() ?: base?.pf ?: 0.5f
        val d = node.params["d"]?.toFloatOrNull() ?: base?.df ?: 0.5f
        val q = node.params["q"]?.toFloatOrNull() ?: base?.qf ?: 0.5f
        bag.intake.send(BeliefIntake.Attend(angular, BudgetCoord(p, d, q)))
        mapOf(
            "attended" to mapOf(
                "angular" to angular.toString(),
                "p" to p, "d" to d, "q" to q,
                "resident" to (base != null),
            ),
        )
    }

    /**
     * `nal.reinforce` — [BeliefIntake.Reinforce] as a lego: an evidence delta
     * onto an existing angular, budget untouched. `wPlus`/`wMinus` are in
     * observation units (scaled by [Nal.UNIT] like [Nal.observe]).
     */
    fun reinforceRunner(bag: BeliefBagElement): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val angular = parseAngular(port(inputs, "angular") ?: node.params["angular"])
            ?: return@LcncNodeRunner mapOf("revised" to mapOf("error" to "angular required"))
        val wPlus = node.params["wPlus"]?.toFloatOrNull() ?: 1f
        val wMinus = node.params["wMinus"]?.toFloatOrNull() ?: 0f
        val delta = EvidenceCoord((wPlus * Nal.UNIT).toLong(), (wMinus * Nal.UNIT).toLong())
        bag.intake.send(BeliefIntake.Reinforce(angular, delta))
        mapOf(
            "revised" to mapOf(
                "angular" to angular.toString(),
                "wPlus" to delta.positive,
                "wMinus" to delta.negative,
                "resident" to (bag.budgetOf(angular) != null),
            ),
        )
    }

    /**
     * `nal.encode` — [AngularCodec.encode] exposed: mints the feature-coded
     * 64-bit centroid that `nal.recall mode=near` and `beliefs.resonate`
     * address by. Decimal-string output so the coordinate survives JSON
     * (64-bit longs overflow JS doubles) and pastes into a `centroid` param.
     */
    fun encodeRunner(): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val subject = port(inputs, "subject")?.toString()?.takeIf { it.isNotBlank() }
            ?: node.params["subject"]?.takeIf { it.isNotBlank() }
            ?: return@LcncNodeRunner mapOf("centroid" to null, "error" to "subject required")
        val relation = node.params["relation"]?.let { r ->
            RelationKind.entries.firstOrNull { it.name.equals(r, ignoreCase = true) }
        } ?: RelationKind.CAUSALITY
        val grade = node.params["grade"]?.let { g ->
            TemporalGrade.entries.firstOrNull { it.name.equals(g, ignoreCase = true) }
        } ?: TemporalGrade.NONE
        val centroid = AngularCodec.encode(
            relation = relation,
            taxonomyKey = node.params["taxonomy"],
            subjectTerm = subject,
            objectTerm = node.params["object"]?.takeIf { it.isNotBlank() },
            grade = grade,
        )
        mapOf("centroid" to centroid.toString())
    }
}
