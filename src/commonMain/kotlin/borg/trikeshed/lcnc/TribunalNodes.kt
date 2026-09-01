package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.KanbanCardState
import borg.trikeshed.kanban.KanbanPredicate
import borg.trikeshed.kanban.KanbanPredicateRegistry
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The tribunal's kanban predicates — named once so the shipped preset's
 * `KanbanCondition` and every predicate registry that validates or drives
 * it (tests, and any future daemon-side FSM gate) resolve the SAME name to
 * the SAME check. `NEEDS_CLARIFICATION` gates the `deliberate → argue`
 * loop-back: the judge's `kg.ingest` verdict is expected to land a
 * `"clarification": true` flag in the card's `io` map when the record is
 * insufficient to rule on (nothing writes that flag automatically today —
 * the kanban FSM and the LCNC mux.chat/kg.ingest execution graph are two
 * separate systems; wiring a verdict's content into the FSM's card io is
 * daemon orchestration that doesn't exist yet). Absent the flag, the
 * predicate refuses and `deliberate` stays terminal, exactly as before.
 */
object TribunalPredicates {
    const val NEEDS_CLARIFICATION = "needsClarification"

    fun registry(base: KanbanPredicateRegistry = KanbanPredicateRegistry()): KanbanPredicateRegistry =
        base.plus(NEEDS_CLARIFICATION, KanbanPredicate { card: KanbanCardState, _ -> card.io["clarification"] == true })
}

/**
 * The tribunal's model dialog — the seam the LCNC nodes cross to talk to a
 * model. This is the "model dialog you have to write": each tribunal seat
 * (counsel, opposing counsel, judge) is a `mux.chat` node whose `system` param
 * is its role and whose `prompt` is wired from the prior seat's content.
 *
 * [dialog] is the injectable port so the graph runs in tests without a
 * provider; the daemon wires the REAL one — the hermes-env parser stack
 * (KeyMux → HarnessSource / HermesCredentialSource → ModelMux → BrainClient)
 * — via [hermesEnvDialog]. It returns the seat's (content, modelId):
 * content feeds the next seat; modelId is the provenance of the turn —
 * the id of the model that actually answered.
 */
fun interface TribunalDialog {
    suspend fun seat(node: LcncNode, system: String, prompt: String): Pair<String, String>
}

/**
 * The hermes-env dialog: resolve credentials the way Hermes does (the
 * daemon's KeyMux already carries the harness lane — $HERMES_HOME/.env,
 * auth.json credential pool, codex/opencode stores — and the BrainClient
 * routes through ModelMux with quota/lease receipts). One call per seat,
 * one receipt per call: the tribunal's token spend is metered exactly like
 * every other model traffic in the daemon.
 *
 * The dialog writes the model conversation itself: [chat] is a
 * `(system, prompt) -> String` closure the daemon binds to
 * `BrainClient.chat(listOf("system" to s, "user" to p), …)` under the mux
 * context, so the model sees the seat's role and the prior seat's record.
 */
fun hermesEnvDialog(
    chat: (system: String, prompt: String) -> suspend () -> Pair<String, String>,
    muxContext: CoroutineContext,
): TribunalDialog = TribunalDialog { node, system, prompt ->
    // The daemon's [chat] closure returns (content, resolved model id) —
    // BrainClient.chat(...) then BrainClient.lastModel() for provenance.
    withContext(muxContext) { chat(system, prompt)() }
}

/**
 * The tribunal's node family — the runners the preset's `mux.chat` /
 * `kg.ingest` / `display` nodes have been missing. Without these the preset
 * is a document that throws `LcncUnknownNodeType` on first sweep; with them
 * the trial actually runs through the daemon's single executor.
 *
 * [dialog] is the model seam (see [TribunalDialog]); [ingest] is the
 * tracked-lifecycle seam — the daemon wires it to the live
 * [TribunalInstance] so each verdict commits on the versioned record
 * (judge job active → closed) and the report carries the recorded cid.
 */
object TribunalNodes {

    fun registry(
        dialog: TribunalDialog,
        ingest: suspend (String) -> String = { it },
    ): Map<String, LcncNodeRunner> = mapOf(
        // The model seat: params carry the role (`system`) and the fallback
        // prompt; a wired `prompt` input (the prior seat's content) wins —
        // inputs-over-params, the same precedence kanban.submit honours.
        // A `brief` param names the ROOT frame binding (the human oversight
        // brief) the first seat reads when no input is fed — and it OUTRANKS
        // the canned `prompt` param, because human oversight beating the
        // default motion is the preset's whole point (the end-to-end gate
        // pins it). Resolution: input → brief binding → prompt param.
        "mux.chat" to LcncNodeRunner { node, inputs ->
            val system = node.params["system"] ?: ""
            // The prior seat's content arrives on the wire as `prompt?`
            // (gather keys by the to-port, trailing ? included); a hand-fed
            // `prompt` is honoured identically. Inputs-over-params.
            val prompt = ((inputs["prompt"] as? String)
                ?: (inputs["prompt?"] as? String)
                ?: node.params["brief"]?.takeIf { it.isNotBlank() }?.let { briefName ->
                    currentCoroutineContext()[LcncScopeFrame]?.binding(briefName)?.toString()
                }
                ?: node.params["prompt"]?.takeIf { it.isNotBlank() }
            )?.takeIf { it.isNotBlank() }
            require(prompt != null) { "mux.chat: no prompt wired, bound as '${node.params["brief"] ?: "<brief>"}', or in params" }
            val (content, model) = dialog.seat(node, system, prompt!!)
            mapOf("content" to content, "model" to model)
        },
        // The verdict seam: chat text → content-addressed report, tracked
        // on the instance. [ingest] is the daemon's lifecycle hook — it
        // commits the verdict (the judge's job active → closed) and returns
        // the recorded cid; the report carries both cids, so the displayed
        // verdict is the VERIFIED one, not just the model's last words.
        "kg.ingest" to LcncNodeRunner { node, inputs ->
            // Inputs are keyed by the wire's literal toPort (gather() in
            // LcncRunner does not strip the `?`), so an optional `text?`
            // wire lands under that exact key — check both spellings, the
            // same defensive dance `mux.chat`'s prompt resolution does.
            val text = (inputs["text"] as? String)
                ?: (inputs["text?"] as? String)
                ?: node.params["kg"].orEmpty()
            val recorded = ingest(text)
            val cid = ContentId.of(text.encodeToByteArray())
            mapOf("report" to linkedMapOf(
                "text" to text,
                "cid" to cid.value,
                "recorded" to recorded,
                "chars" to text.length,
            ))
        },
        // The display sink: passes the wired value through as-is. The panel
        // renderer reads `x` and paints it; the sink itself stays a pure
        // pass-through so the same node works for any payload kind.
        "display" to LcncNodeRunner { _, inputs ->
            mapOf("x" to (inputs["x"] ?: ""))
        },
    )

    /** The full contract: which node types the tribunal registry serves. */
    fun servedTypes(): Set<String> = setOf("mux.chat", "kg.ingest", "display")
}

/**
 * The daemon's holder for the live tribunal instance. Runners register
 * BEFORE the instance opens (open seeds its root and suspends), so the
 * ingest seam reads the instance through this holder rather than capturing
 * it at construction — the holder is the seam, not a registry of state.
 */
class TribunalInstanceHolder {
    var instance: TribunalInstance? = null
}
