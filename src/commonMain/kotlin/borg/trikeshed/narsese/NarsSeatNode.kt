package borg.trikeshed.narsese

import borg.trikeshed.parse.reify
import borg.trikeshed.parse.jsonOf

import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.boundLcnc
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
/**
 * `nars.seat` — an advisory, non-model council seat.
 *
 * A Hamming-near belief is a discovery suggestion, not evidence that a choice
 * is the same proposition, reviewed, or independently supported. This seat
 * reports nearby context but always requests clarification until a separate
 * reviewed proposition-to-evidence binding is available. The council record
 * shape is retained for `text.fold`, `record.fold` and `ruling.parse`.
 */
object NarsSeatNode {

    const val ANSWERED_BY = "nars:belief-bag"

    private fun port(inputs: Map<String, Any?>, name: String): Any? =
        inputs[name] ?: inputs["$name?"]

    /** Choices arrive as a MANY-collected list, one raw string, or a JSON array string. */
    private fun choicesOf(raw: Any?): List<String> = when (raw) {
        null -> emptyList()
        is List<*> -> raw.flatMap { c -> if (c is List<*>) c.map { it.toString() } else listOf(c.toString()) }
        is String -> {
            val parsed = runCatching { reify(raw) as? List<*> }.getOrNull()
            parsed?.map { it.toString() } ?: listOf(raw)
        }
        else -> listOf(raw.toString())
    }.map { it.trim() }.filter { it.isNotEmpty() }

    /** One choice's discovery context; evidence remains empty without a verified binding. */
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
        val taxonomy = node.params["taxonomy"]?.takeIf { it.isNotBlank() }

        val reviews = choices.map { choice ->
            val centroid = AngularCodec.encode(
                relation = RelationKind.CAUSALITY,
                taxonomyKey = taxonomy,
                subjectTerm = choice,
            )
            val near = bag.recallNear(centroid, maxDistance)
            val evidence = EvidenceCoord(0L, 0L)
            val front = ArrayList<Map<String, Any?>>()
            val bounded = if (near.size < k) near.size else k
            for (i in 0 until bounded) {
                val signal = near[i]
                front.add(mapOf(
                    "angular" to signal.angular.toString(),
                    "gloss" to (glossOf(signal.angular) ?: bag.glossOf(signal.angular)),
                    "relation" to signal.relation.name,
                    "status" to "unverified-proximity",
                ))
            }
            val truth = Nal.truthOf(evidence)
            ChoiceReview(choice, centroid, evidence, truth.expectation(), truth.frequency, truth.confidence, front)
        }

        // Preserve input order. There is no evidence-backed ranking or verdict.
        val ranked = reviews

        val verdict = linkedMapOf<String, Any?>(
            "disposition" to "INSUFFICIENT CURATED EVIDENCE",
            "needsClarification" to true,
            "clarificationQuestion" to "Which reviewed source and exact proposition support a choice?",
            "mistrial" to false,
            "basis" to "unverified-proximity",
            "ranking" to ranked.map { r ->
                mapOf(
                    "choice" to r.choice,
                    "evidenceStatus" to "not-established",
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
            appendLine("Nearby beliefs are unverified discovery context, not evidence for a choice.")
            for (r in ranked) append("  - ").appendLine(r.choice)
            append(jsonOf(verdict))
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
            "basis" to "unverified-proximity",
            "choices" to ranked.map { r ->
                mapOf(
                    "choice" to r.choice,
                    "evidenceStatus" to "not-established",
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
