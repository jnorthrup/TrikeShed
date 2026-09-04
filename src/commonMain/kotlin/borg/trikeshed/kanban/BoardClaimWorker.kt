package borg.trikeshed.kanban

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.kanban.rules.BoardRules
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BoardClaimWorker — what a `claim` activation becomes once the sink has it:
 *
 *   1. Move(RUNNING, owner=claim:brain), idempotency `<jobId>#claim#<rev>`;
 *      wait for THAT commit on the store's committed flow (subscribed before
 *      the send — the flow has no replay), [commitTimeoutMs] → give up, and
 *      the receipt says so. Nothing else happens for a claim that never landed.
 *   2. The card's title is the brief. `prompt.chat` — the daemon's own brain
 *      (ModelMux over KeyMux; quota it already has) — answers in ≤ 3 sentences,
 *      maxTokens 256. The model is the first entry of `mux.models`, which is
 *      the newest model Hermes ran here (LcncContracts: "the live list leads,
 *      and its first entry is the newest") — `prompt.chat` itself refuses a
 *      blank model, so the worker resolves it the way the picklist would.
 *   3. Receipt `kanban/claim/<jobId>` = {owner, model, ok, content|error, atMs,
 *      revision}, then Move(REVIEW) at the card's CURRENT revision (idempotency
 *      `<jobId>#claim-review#<rev>`), owner untouched. A failed brain call still
 *      goes to REVIEW with ok=false — a human reads the receipt. Nothing here
 *      ever moves a card to DONE; the store's claim guard would refuse it anyway.
 *
 * A daemon with NO `prompt.chat` runner at all (a reduced/test context) takes
 * no claim: the receipt says "no brain", the card stays READY for whoever has
 * one. A brain that is present and fails is step 3's ok=false, not this.
 *
 * Pure over the store, the blackboard and a runner lookup (looked up at claim
 * time so a registry the daemon fills after the module attached still counts).
 */
class BoardClaimWorker(
    private val store: BoardStoreElement,
    private val blackboard: ConfixBlackboard,
    private val runner: (String) -> LcncNodeRunner?,
    private val clock: () -> Long,
    private val commitTimeoutMs: Long = 10_000L,
) {
    companion object {
        const val RECEIPT_PREFIX: String = "kanban/claim/"
        const val LANGUAGE: String = "kanban-claim"
        /**
         * 1024, not 256: the newest Hermes card is a thinking model (glm-5.3-flash)
         * that spends its budget on reasoning first; at 256 every claim on
         * 2026-09-04 came back "provider billed 256 completion tokens but returned
         * no content". One claim costs ~1k tokens of quota the ledger already proved.
         */
        const val MAX_TOKENS: String = "1024"

        fun brief(jobId: String, title: String): String =
            "Card $jobId: $title. Propose the concrete next action in ≤ 3 sentences."
    }

    /** One claim, start to finish. Returns the receipt as written (plus `review`: the REVIEW move's verdict). */
    suspend fun claim(jobId: String, expectedRevision: Long, owner: String = BoardRules.CLAIM_OWNER): Map<String, Any?> = coroutineScope {
        // ── 0. no brain, no claim ─────────────────────────────────────────────
        val chat = runner("prompt.chat")
        if (chat == null) {
            return@coroutineScope receipt(
                jobId, owner, model = "", ok = false, revision = expectedRevision,
                body = "error" to "no brain: this daemon has no prompt.chat runner, claim not taken",
            ) + ("review" to "not attempted")
        }

        // ── 1. RUNNING, and the commit that proves it ─────────────────────────
        val events = Channel<BoardCommitted>(Channel.UNLIMITED)
        val subscribed = CompletableDeferred<Unit>()
        val tap = launch {
            store.committed
                .onSubscription { subscribed.complete(Unit) }
                .collect { if (it.jobId == jobId) events.trySend(it) }
        }
        subscribed.await()
        val reply = CompletableDeferred<BoardApply>()
        store.intake.send(
            BoardIntake(
                mapOf(
                    "type" to "move",
                    "jobId" to jobId,
                    "idempotencyKey" to "$jobId#${BoardRules.CLAIM}#$expectedRevision",
                    "expectedRevision" to expectedRevision,
                    "toColumn" to BoardCol.RUNNING.wire,
                    "owner" to owner,
                ),
                reply,
            ),
        )
        val verdict = withTimeoutOrNull(commitTimeoutMs) { reply.await() }
        val landed: BoardCommitted? = when (verdict) {
            is BoardApply.Committed -> withTimeoutOrNull(commitTimeoutMs) {
                var found: BoardCommitted? = null
                while (found == null) {
                    val ev = events.receive()
                    if (ev.sequence == verdict.sequence && ev.col == BoardCol.RUNNING) found = ev
                }
                found
            }
            else -> null
        }
        tap.cancel()
        if (landed == null) {
            val why = when (verdict) {
                is BoardApply.Rejected -> "claim refused: ${verdict.reason}"
                is BoardApply.Committed -> "claim committed (seq ${verdict.sequence}) but no RUNNING event within ${commitTimeoutMs}ms"
                null -> "no reply from the store within ${commitTimeoutMs}ms"
            }
            return@coroutineScope receipt(jobId, owner, model = "", ok = false, body = "error" to why, revision = expectedRevision) +
                ("review" to "not attempted")
        }

        // ── 2. the brief is the card; the brain is the daemon's own ────────────
        val title = store.card(jobId)?.title ?: jobId
        val model = resolveModel()
        val answer: Map<String, Any?> = try {
            chat.run(
                LcncNode(
                    id = "claim-$jobId",
                    type = "prompt.chat",
                    params = mapOf("prompt" to brief(jobId, title), "model" to model, "maxTokens" to MAX_TOKENS),
                ),
                emptyMap(),
            )
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            mapOf("ok" to false, "error" to (t.message ?: t.toString()), "model" to model, "content" to "")
        }
        val ok = answer["ok"] == true
        val body: Pair<String, Any?> =
            if (ok) "content" to (answer["content"]?.toString() ?: "")
            else "error" to (answer["error"]?.toString()?.takeIf { it.isNotBlank() } ?: "brain answered without content")

        // ── 3. receipt, then REVIEW at the card's current revision — never DONE ──
        val current = store.card(jobId)?.revision ?: landed.snapshot.revision
        val written = receipt(jobId, owner, model = answer["model"]?.toString() ?: model, ok = ok, body = body, revision = current)
        val reviewReply = CompletableDeferred<BoardApply>()
        store.intake.send(
            BoardIntake(
                mapOf(
                    "type" to "move",
                    "jobId" to jobId,
                    "idempotencyKey" to "$jobId#claim-review#$current",
                    "expectedRevision" to current,
                    "toColumn" to BoardCol.REVIEW.wire,
                ),
                reviewReply,
            ),
        )
        val review = when (val r = withTimeoutOrNull(commitTimeoutMs) { reviewReply.await() }) {
            is BoardApply.Committed -> "review r${r.revision}"
            is BoardApply.Rejected -> "review refused: ${r.reason}"
            null -> "review: no reply within ${commitTimeoutMs}ms"
        }
        written + ("review" to review)
    }

    /** `mux.models#models[0].id` — the newest model Hermes ran here, or "" when the daemon offers none. */
    private suspend fun resolveModel(): String {
        val models = runner("mux.models") ?: return ""
        val out = try {
            models.run(LcncNode(id = "claim-models", type = "mux.models"), emptyMap())
        } catch (t: CancellationException) {
            throw t
        } catch (_: Throwable) {
            return ""
        }
        val list = when (val m = out["models"]) {
            is List<*> -> m
            is Array<*> -> m.toList()
            else -> emptyList<Any?>()
        }
        return (list.firstOrNull() as? Map<*, *>)?.get("id")?.toString().orEmpty()
    }

    private fun receipt(jobId: String, owner: String, model: String, ok: Boolean, body: Pair<String, Any?>, revision: Long): Map<String, Any?> {
        val value = linkedMapOf<String, Any?>(
            "owner" to owner,
            "model" to model,
            "ok" to ok,
            body.first to body.second,
            "atMs" to clock(),
            "revision" to revision,
        )
        blackboard.put(RECEIPT_PREFIX + jobId, value, LANGUAGE)
        return value
    }
}
