package borg.trikeshed.lcnc

/**
 * One seat's model call, fully described — everything the daemon needs to
 * route the call (preferred model, token budget, temperature, spend-receipt
 * contextId) and everything the record needs for provenance (case, panel,
 * seat, role, round). The council does NOT ride [TribunalDialog]'s
 * `(node, system, prompt)` seam: that seam drops per-seat params, and
 * amending it would ripple through hermesEnvDialog callers and both legacy
 * execution tests. `mux.chat` / preset-tribunal are untouched — the council
 * crosses its own seam.
 */
data class SeatCall(
    val node: LcncNode,
    val caseId: String,
    val panel: String,
    val seat: String,
    val role: String,
    val round: Int,
    val system: String,
    val prompt: String,
    val preferredModel: String?,
    val maxTokens: Int,
    val temperature: Double,
    val contextId: String,
)

/**
 * What a seat call came back with. [Ok] carries the content and the id of
 * the model that ACTUALLY answered (provenance — the requested model may
 * have failed over). [Refused] is the degrade-loudly outcome: the error and
 * the per-provider failover trail, which the `council.seat` runner turns
 * into a `[SEAT FAILED: …]` banner that flows into every downstream fold
 * and prompt — never a silent empty ruling (design brief hard requirement).
 */
sealed interface SeatOutcome {
    data class Ok(val content: String, val answeredBy: String) : SeatOutcome
    data class Refused(val error: String, val attempted: List<String>) : SeatOutcome
}

/**
 * The council's model seam — injectable so the whole council runs green in
 * tests with zero model spend (design brief: "Test seam"). The daemon binds
 * it to `BrainClient.chatSeat(...)` under the mux context; tests script it.
 * A well-behaved dialog returns [SeatOutcome.Refused] rather than throwing,
 * but the `council.seat` runner guards either way: a throwing dialog is
 * caught and becomes a Refused banner on the record.
 */
fun interface CouncilDialog {
    suspend fun seat(call: SeatCall): SeatOutcome
}
