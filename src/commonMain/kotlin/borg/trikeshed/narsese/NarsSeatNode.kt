package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.boundLcnc
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.json.JsonSupport

/**
 * `nars.seat` — the council's NON-MODEL seat: NARS as the review of choices.
 *
 * A `council.seat` spends a model call on an opinion; this seat spends nothing
 * and answers only from curated belief: each wired choice is encoded to its
 * angular centroid ([AngularCodec], the same address `beliefs.resonate`
 * sweeps), the bag's hamming front around that centroid is revised into one
 * evidence base ([revise] — plain addition, the NAL merge), and the choice's
 * standing is [Nal.truthOf] of that base. The verdict ranks choices by
 * expectation; a choice with no resident evidence does not win by default —
 * the seat asks for clarification instead, the same escape a model judge has.
 *
 * Record shape is `council.seat`-compatible (`seat`, `panel`, `role`,
 * `round`, `charge`, `answeredBy`, `status`, `cid`, `text`, `contextId`) so
 * `text.fold` / `record.fold` / `council.record` compose unchanged, and the
 * trailing JSON verdict is exactly what `ruling.parse` extracts (strict
 * booleans included). `answeredBy` is `nars:belief-bag`: the provenance field
 * a fiduciary reader checks to see that no model spoke.
 */
object NarsSeatNode {

    const val ANSWERED_BY = "nars:belief-bag"

    private fun port(inputs: Map<String, Any?>, name: String): Any? =
        inputs[name] ?: inputs["$name?"]

    /** Fixed 3-decimal rendering, commonMain-legal (no String.format on JS). */
    private fun f3(v: Float): String {
        val scaled = (v * 1000).toInt()
        val neg = scaled < 0
        val abs = if (neg) -scaled else scaled
        val whole = abs / 1000
        val frac = (abs % 1000).toString().padStart(3, '0')
        return (if (neg) "-" else "") + "$whole.$frac"
    }

    /** Choices arrive as a MANY-collected list, one raw string, or a JSON array string. */
    private fun choicesOf(raw: Any?): List<String> = when (raw) {
        null -> emptyList()
        is List<*> -> raw.flatMap { c -> if (c is List<*>) c.map { it.toString() } else listOf(c.toString()) }
        is String -> {
            val parsed = runCatching { JsonSupport.parse(raw) as? List<*> }.getOrNull()
            parsed?.map { it.toString() } ?: listOf(raw)
        }
        else -> listOf(raw.toString())
    }.map { it.trim() }.filter { it.isNotEmpty() }

    /** One choice's review: the revised evidence of its angular front, and the truth of it. */
    data class ChoiceReview(
        val choice: String,
        val centroid: Long,
        val evidence: EvidenceCoord,
        val expectation: Float,
        val frequency: Float,
        val confidence: Float,
        val front: List<Map<String, Any?>>,
    )

    fun seatRunner(
        bag: BeliefBagElement,
        glossOf: (Long) -> String? = { null },
    ): LcncNodeRunner = boundLcnc(bag) { bag, node, inputs ->
        val charge = (port(inputs, "charge")?.toString() ?: node.params["charge"])
            ?.takeIf { it.isNotBlank() }
        require(charge != null) { "nars.seat ${node.id}: no charge wired" }
        val choices = choicesOf(port(inputs, "choices") ?: node.params["choices"])
        require(choices.isNotEmpty()) { "nars.seat ${node.id}: no choices wired" }

        val panel = node.params["panel"] ?: "council"
        val seatName = node.params["seat"] ?: node.id
        val role = node.params["role"] ?: "review"
        val round = node.params["round"]?.toIntOrNull() ?: 1
        val contextId = node.params["contextId"].orEmpty()
        val k = node.params["k"]?.toIntOrNull() ?: 8
        val maxDistance = node.params["maxDistance"]?.toIntOrNull() ?: 16
        val taxonomy = node.params["taxonomy"]
        val minEvidence = (node.params["minEvidence"]?.toFloatOrNull() ?: 1f)

        val reviews = choices.map { choice ->
            val centroid = AngularCodec.encode(
                relation = RelationKind.CAUSALITY,
                taxonomyKey = taxonomy,
                subjectTerm = choice,
            )
            val near = bag.recallNear(centroid, maxDistance)
            var evidence = EvidenceCoord(0L, 0L)
            val front = ArrayList<Map<String, Any?>>()
            val bounded = if (near.size < k) near.size else k
            for (i in 0 until bounded) {
                val signal = near[i]
                evidence = revise(evidence, signal.evidence)
                val truth = Nal.truthOf(signal.evidence)
                front.add(mapOf(
                    "angular" to signal.angular.toString(),
                    "gloss" to (glossOf(signal.angular) ?: bag.glossOf(signal.angular)),
                    "expectation" to truth.expectation(),
                    "frequency" to truth.frequency,
                ))
            }
            val truth = Nal.truthOf(evidence)
            ChoiceReview(choice, centroid, evidence, truth.expectation(), truth.frequency, truth.confidence, front)
        }

        // Deterministic order: expectation, then confidence, then the text itself.
        val ranked = reviews.sortedWith(
            compareByDescending<ChoiceReview> { it.expectation }
                .thenByDescending { it.confidence }
                .thenBy { it.choice }
        )
        val top = ranked.first()
        val thin = top.evidence.positive + top.evidence.negative < (minEvidence * Nal.UNIT).toLong()

        val verdict = linkedMapOf<String, Any?>(
            "disposition" to if (thin) "INSUFFICIENT CURATED EVIDENCE" else top.choice,
            "needsClarification" to thin,
            "clarificationQuestion" to
                if (thin) "no curated belief addresses \"${top.choice}\" — admit evidence or re-phrase the choice" else "",
            "mistrial" to false,
            "basis" to ANSWERED_BY,
            "ranking" to ranked.map { r ->
                mapOf(
                    "choice" to r.choice,
                    "expectation" to r.expectation,
                    "frequency" to r.frequency,
                    "confidence" to r.confidence,
                    "evidencePositive" to r.evidence.positive,
                    "evidenceNegative" to r.evidence.negative,
                )
            },
        )

        val body = buildString {
            appendLine("charge: $charge")
            for (r in ranked) {
                append("  ").append(f3(r.expectation))
                    .append(" (f=").append(f3(r.frequency)).append(" c=").append(f3(r.confidence))
                    .append(", w+=").append(r.evidence.positive).append(" w-=").append(r.evidence.negative)
                    .append(") ").appendLine(r.choice)
            }
            append(JsonSupport.stringify(verdict))
        }

        val record = linkedMapOf<String, Any?>(
            "seat" to seatName,
            "panel" to panel,
            "role" to role,
            "round" to round,
            "charge" to charge,
            "requestedModel" to "",
            "answeredBy" to ANSWERED_BY,
            "status" to "ok",
            "cid" to ContentId.of(body.encodeToByteArray()).value,
            "text" to body,
            "chars" to body.length,
            "contextId" to contextId,
            "basis" to ANSWERED_BY,
            "choices" to ranked.map { r ->
                mapOf(
                    "choice" to r.choice,
                    "centroid" to r.centroid.toString(),
                    "expectation" to r.expectation,
                    "frequency" to r.frequency,
                    "confidence" to r.confidence,
                    "evidencePositive" to r.evidence.positive,
                    "evidenceNegative" to r.evidence.negative,
                    "front" to r.front,
                )
            },
        )

        mapOf(
            "content" to body,
            "labeled" to "[$panel.$seatName · $role · round $round · $ANSWERED_BY]\n$body",
            "model" to ANSWERED_BY,
            "record" to record,
        )
    }
}
