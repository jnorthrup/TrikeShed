package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.job.CasStore
import borg.trikeshed.kanban.KanbanCardState
import borg.trikeshed.kanban.KanbanPredicate
import borg.trikeshed.kanban.KanbanPredicateRegistry
import borg.trikeshed.modelmux.BrainChatOptions
import borg.trikeshed.modelmux.BrainClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The separate Kanban graph consumes an explicit card flag. The shipped LCNC
 * programs use the shared structured ruling parser and one guarded clarification
 * scope; they do not drive these card transitions, counsel LOOP/JOIN, or ABORT.
 */
object TribunalPredicates {
    const val NEEDS_CLARIFICATION = "needsClarification"
    const val RESOLVED_RULING = "resolvedRuling"

    fun registry(base: KanbanPredicateRegistry = KanbanPredicateRegistry()): KanbanPredicateRegistry =
        base.plus(NEEDS_CLARIFICATION, KanbanPredicate { card: KanbanCardState, _ -> card.io["clarification"] == true })
            .plus(RESOLVED_RULING, KanbanPredicate { card: KanbanCardState, _ ->
                val ruling = card.io["lcnc.case.ruling"] as? Map<*, *>
                val evidence = ruling?.get("evidenceCid") as? String
                card.io["clarification"] == false && ruling != null && ruling["needsClarification"] == false &&
                    ruling["mistrial"] == false && evidence != null && runCatching { ContentId(evidence) }.isSuccess
            })
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
    withContext(muxContext.minusKey(Job) + currentCoroutineContext()) { chat(system, prompt)() }
}

/** The same authored options and route restrictions used by the HTTP chat surface. */
fun modelMuxDialog(brain: BrainClient, muxContext: CoroutineContext): TribunalDialog =
    TribunalDialog { node, system, prompt ->
        val options = BrainChatOptions.parse(node.params)
        withContext(muxContext.minusKey(Job) + currentCoroutineContext()) {
            brain.chat(options, listOf("system" to system, "user" to prompt), "tribunal-seat")
        }
    }

/** Retain the verdict bytes before asking the host to commit its lifecycle. */
fun tribunalRecord(cas: CasStore, complete: suspend (ContentId) -> String): suspend (String) -> String = { text ->
    require(text.isNotBlank()) { "tribunal.record: empty verdict" }
    val bytes = text.encodeToByteArray()
    val cid = ContentId.of(bytes)
    check(cas.put(bytes) == cid) { "tribunal.record: CAS acknowledgement digest mismatch" }
    cas.sync()
    check(cas.get(cid)?.contentEquals(bytes) == true) { "tribunal.record: verdict bytes not retained" }
    complete(cid)
}

/** Model dialog and prose verdict recording. KG assertions belong to [KgNodes]. */
object TribunalNodes {

    fun registry(
        dialog: TribunalDialog,
        record: suspend (String) -> String = { error("tribunal.record: no record service bound") },
    ): Map<String, LcncNodeRunner> = mapOf(
        // The model seat: params carry the role (`system`) and the fallback
        // prompt; a wired `prompt` input (the prior seat's content) wins —
        // inputs-over-params, the same precedence kanban.submit honours.
        // A `brief` param names the ROOT frame binding (the human oversight
        // brief) the first seat reads when no input is fed — and it OUTRANKS
        // the canned `prompt` param, because human oversight beating the
        // default motion is the preset's whole point (the end-to-end gate
        // pins it). Resolution: input → brief binding → prompt param.
        "mux.chat" to boundLcnc(TribunalDialogKey(dialog)) { service, node, inputs ->
            // A stored system prompt cabled onto `system?` wins over the param.
            val system = (inputs["system"] as? String) ?: (inputs["system?"] as? String) ?: node.params["system"] ?: ""
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
            val (content, model) = service.value.seat(node, system, prompt!!)
            // The already selected prompt is evidence, not another resolution of the brief.
            mapOf("content" to content, "model" to model, "prompt" to prompt)
        },
        // The host binds tribunalRecord: retained prose plus a lifecycle receipt.
        // This is not KG parsing, belief admission, or verification of the verdict's truth.
        "tribunal.record" to boundLcnc(TribunalRecordKey(record)) { service, node, inputs ->
            // Inputs are keyed by the wire's literal toPort (gather() in
            // LcncRunner does not strip the `?`), so an optional `text?`
            // wire lands under that exact key — check both spellings, the
            // same defensive dance `mux.chat`'s prompt resolution does.
            val text = (inputs["text"] as? String)
                ?: (inputs["text?"] as? String)
                ?: node.params["text"].orEmpty()
            require(text.isNotBlank()) { "tribunal.record: empty verdict" }
            require(CouncilNodes.ruling(text)["needsClarification"] != true) {
                "tribunal.record: unresolved clarification; no terminal ruling to record"
            }
            val recorded = service.value.invoke(text)
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
    fun servedTypes(): Set<String> = setOf("mux.chat", "tribunal.record", "display")
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
