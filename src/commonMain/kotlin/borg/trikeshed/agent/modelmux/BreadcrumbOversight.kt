package borg.trikeshed.agent.modelmux

import borg.trikeshed.lib.*
import borg.trikeshed.narsese.Jeva
import borg.trikeshed.parse.jsonOf
import kotlinx.coroutines.CancellationException

/** A finite relevance judgment: does an excerpt bear on the task question. It says nothing about whether the excerpt is correct. */
enum class Verdict { RELEVANT, NOT_RELEVANT, UNCERTAIN }

enum class OversightStatus {
    /** Oversight was off. No model was asked. */
    NOT_REQUESTED,

    /** The judge answered. Individual verdicts may still be null: those excerpts were outside the review bounds. */
    REVIEWED,

    /** The judge could not answer. This is NOT approval: nothing was reviewed. */
    UNAVAILABLE,
}

/**
 * Bounds and thresholds for relevance oversight. The thresholds are configuration, not calibrated probabilities:
 * `p >= relevantAt` is RELEVANT, `p <= notRelevantAt` is NOT_RELEVANT, and the band between is UNCERTAIN.
 * Everything is bounded up front so the judge's input is known before it is asked: [maxReviewed] questions,
 * excerpts no longer than [maxExcerptChars] (a longer one is left unreviewed, never truncated), and a serialized
 * state no larger than [maxStateChars] (the judge errors rather than clip, so we stay under its limit).
 */
data class OversightPolicy(
    val relevantAt: Double = 0.65,
    val notRelevantAt: Double = 0.35,
    val maxReviewed: Int = 24,
    val maxExcerptChars: Int = 1_500,
    val maxStateChars: Int = 6_000,
) {
    init {
        require(0.0 <= notRelevantAt && notRelevantAt < relevantAt && relevantAt <= 1.0) { "thresholds must satisfy 0 <= notRelevantAt < relevantAt <= 1" }
        require(maxReviewed in 1..Jeva.MAX_QUESTIONS) { "maxReviewed must be 1..${Jeva.MAX_QUESTIONS}" }
        require(maxExcerptChars > 0 && maxStateChars > 0)
    }
}

/**
 * What the judge said, aligned index-for-index with the projection's excerpts (null = not reviewed). It carries no
 * excerpt text and cannot alter one: oversight annotates evidence, it never rewrites it.
 */
class OversightReceipt(
    val status: OversightStatus,
    val model: String?,
    val verdicts: List<Verdict?>,
    val probabilities: List<Double?>,
    val detail: String?,
) {
    /** Questions actually put to the judge. */
    val asked: Int get() = if (status == OversightStatus.REVIEWED) verdicts.count { it != null } else 0
    val unreviewed: Int get() = verdicts.count { it == null }
}

/** Reviews a projection's excerpts for relevance to [question]. Cancellation always propagates. */
fun interface BreadcrumbOversight {
    suspend fun review(question: String, projection: Projection): OversightReceipt
}

/** Oversight off: zero inference, and the receipt says so. */
object NoOversight : BreadcrumbOversight {
    override suspend fun review(question: String, projection: Projection): OversightReceipt {
        val blank = List<Verdict?>(projection.excerpts.size) { null }
        return OversightReceipt(OversightStatus.NOT_REQUESTED, null, blank, List<Double?>(blank.size) { null }, "oversight not requested")
    }
}

/**
 * System1 oversight by a finite-decision model (Jeva). One yes/no question per reviewed excerpt, asked together over
 * a state holding the task and the excerpts. The judge is injected as [ask] so production passes
 * `{ state, questions -> jevaElement.ask(state, questions) }` and tests pass a fixture; the question and answer
 * shapes are Jeva's own, validated by `Jeva.questions`.
 *
 * Failure is never approval. A thrown error, a malformed or out-of-range answer, or an answer set that does not
 * match the questions yields UNAVAILABLE with no verdicts at all; a partial answer is not trusted.
 */
class JevaOversight(
    private val policy: OversightPolicy = OversightPolicy(),
    private val ask: suspend (state: Any, questions: Map<String, Map<String, Any?>>) -> Map<String, Any?>,
) : BreadcrumbOversight {

    override suspend fun review(question: String, projection: Projection): OversightReceipt {
        val n = projection.excerpts.size
        val verdicts = arrayOfNulls<Verdict>(n)
        val probabilities = arrayOfNulls<Double>(n)
        fun receipt(status: OversightStatus, model: String?, detail: String?) =
            OversightReceipt(status, model, verdicts.toList(), probabilities.toList(), detail)

        if (n == 0) return receipt(OversightStatus.REVIEWED, null, null)

        fun stateFor(indices: List<Int>): Map<String, Any?> = mapOf(
            "task" to question,
            "spans" to indices.map { i ->
                val e = projection.excerpts[i]
                mapOf("id" to "span-$i", "document" to e.document, "lines" to "${e.firstLine + 1}-${e.lastLine + 1}", "text" to e.text)
            },
        )

        // First come, first reviewed, in reading order. Anything that does not fit is reported, never sampled or clipped.
        val chosen = ArrayList<Int>()
        for (i in 0 until n) {
            if (chosen.size >= policy.maxReviewed) break
            if (projection.excerpts[i].text.length > policy.maxExcerptChars) continue
            if (jsonOf(stateFor(chosen + i)).length > policy.maxStateChars) continue
            chosen.add(i)
        }
        if (chosen.isEmpty()) return receipt(OversightStatus.UNAVAILABLE, null, "no excerpt fits the review bounds")

        val questions = LinkedHashMap<String, Map<String, Any?>>()
        for (i in chosen) {
            questions["span-$i"] = mapOf(
                "type" to "noul",
                "instructions" to "Is excerpt span-$i in the request state relevant to the task question?",
                "criteria" to mapOf(
                    "true" to "the excerpt helps answer the task question",
                    "false" to "the excerpt does not help answer the task question",
                ),
            )
        }

        val reply = try {
            ask(stateFor(chosen), questions)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return receipt(OversightStatus.UNAVAILABLE, null, "judge failed: ${failure::class.simpleName}")
        }

        val judged = DoubleArray(n)
        val model: String? = try {
            val answers = reply["answers"] as? Map<*, *> ?: error("reply has no answers")
            require(answers.keys == questions.keys) { "answers do not match the questions asked" }
            for (i in chosen) {
                val answer = answers["span-$i"] as? Map<*, *> ?: error("no answer for span-$i")
                judged[i] = Jeva.probability(answer["noul"], "noul 'span-$i'")
            }
            reply["model"] as? String
        } catch (malformed: IllegalArgumentException) {
            return receipt(OversightStatus.UNAVAILABLE, null, "judge reply malformed: ${malformed.message?.take(120)}")
        } catch (malformed: IllegalStateException) {
            return receipt(OversightStatus.UNAVAILABLE, null, "judge reply malformed: ${malformed.message?.take(120)}")
        }

        for (i in chosen) {
            val p = judged[i]
            probabilities[i] = p
            verdicts[i] = when {
                p >= policy.relevantAt -> Verdict.RELEVANT
                p <= policy.notRelevantAt -> Verdict.NOT_RELEVANT
                else -> Verdict.UNCERTAIN
            }
        }
        val skipped = n - chosen.size
        return receipt(OversightStatus.REVIEWED, model, if (skipped > 0) "$skipped of $n excerpts were outside the review bounds and are unreviewed" else null)
    }
}

/**
 * A projection with the judge's relevance verdicts applied. Only an explicit NOT_RELEVANT verdict removes an excerpt;
 * uncertain, unreviewed and unavailable all keep it. An excerpt that carries a protected constraint is never removed
 * on relevance grounds, since a limit or prohibition is exactly what a relevance judge is least placed to discount.
 * Excerpt objects are passed through untouched: oversight changes membership, never text.
 */
class OversightedProjection(
    val projection: Projection,
    val receipt: OversightReceipt,
    val kept: Series<Excerpt>,
    val flagged: Series<Excerpt>,
    /** Positions, in the projection's excerpt order, of the flagged excerpts. */
    val flaggedAt: Set<Int>,
) {
    fun render(): String {
        val out = StringBuilder()
        for (i in 0 until kept.size) out.append(kept[i].rendered())
        return out.toString()
    }
}

fun applyOversight(projection: Projection, receipt: OversightReceipt): OversightedProjection {
    require(receipt.verdicts.size == projection.excerpts.size) { "receipt is not aligned with this projection" }
    val kept = ArrayList<Excerpt>()
    val flagged = ArrayList<Excerpt>()
    val flaggedAt = LinkedHashSet<Int>()
    for (i in 0 until projection.excerpts.size) {
        val e = projection.excerpts[i]
        if (receipt.verdicts[i] == Verdict.NOT_RELEVANT && "constraint:neighbor" !in e.reasons) { flagged.add(e); flaggedAt.add(i) } else kept.add(e)
    }
    return OversightedProjection(projection, receipt, kept.toSeries(), flagged.toSeries(), flaggedAt)
}
