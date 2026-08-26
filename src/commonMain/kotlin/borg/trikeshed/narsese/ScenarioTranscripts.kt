package borg.trikeshed.narsese

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * ScenarioTranscripts — pure projection of replay transcript turns into
 *
 * A replay scenario is one evidence session's dialogue re-read AFTER the
 * curator acted. The verdict comes ONLY from explicit outcome markers in
 * the transcript (`[pass]`/`[supported]`/`[keep]` vs `[fail]`/`[refuted]`/
 * `[drop]`, case-insensitive) — the same discipline
 * [CuratorImpulseRecipient.readVerdict] applies. A transcript with no
 * marker is NEUTRAL: no evidence, no signal.
 *
 * The caller (daemon-side, jvmMain) owns HOW turns are retrieved; this
 * projection only shapes them.
 */
object ScenarioTranscripts {

    /** One dialogue turn as the ledger's evidence session recorded it. */
    data class Turn(val role: String, val text: String)

    /**
     * Shape retrieved turns into a [ReplayScenario]. `turns` arrives in
     * dialogue order; role is normalized to lowercase (user/assistant/tool
     * all count — a failing tool run is hindsight evidence too).
     */
    fun scenario(
        scenarioId: String,
        impulseSubject: String,
        turns: Series<Turn>,
    ): ReplayScenario = ReplayScenario(
        scenarioId = scenarioId,
        impulseSubject = impulseSubject,
        turns = turns.size j { i: Int ->
            ReplayTurn(role = turns[i].role.lowercase(), text = turns[i].text)
        },
    )

    /** Marker scan over raw turn text — the pure verdict oracle, exposed for callers that pre-filter. */
    fun verdictOf(turns: Series<Turn>): HindsightVerdict {
        var verdict = HindsightVerdict.NEUTRAL
        for (i in 0 until turns.size) {
            val text = turns[i].text.lowercase()
            when {
                CuratorImpulseRecipient.hasSupportMarker(text) -> verdict = HindsightVerdict.SUPPORTED
                CuratorImpulseRecipient.hasRefuteMarker(text) -> verdict = HindsightVerdict.REFUTED
            }
        }
        return verdict
    }

    /** Same oracle over shaped [ReplayTurn]s — what a built [ReplayScenario] carries. (Distinct JVM name: both erase to Join.) */
    fun verdictOfReplay(turns: borg.trikeshed.lib.Join<Int, (Int) -> ReplayTurn>): HindsightVerdict {
        var verdict = HindsightVerdict.NEUTRAL
        for (i in 0 until turns.size) {
            val text = turns[i].text.lowercase()
            when {
                CuratorImpulseRecipient.hasSupportMarker(text) -> verdict = HindsightVerdict.SUPPORTED
                CuratorImpulseRecipient.hasRefuteMarker(text) -> verdict = HindsightVerdict.REFUTED
            }
        }
        return verdict
    }

    /** Empty scenario — for callers that must return a value on missing transcripts. */
    fun empty(scenarioId: String, impulseSubject: String): ReplayScenario =
        ReplayScenario(scenarioId, impulseSubject, emptySeriesOf())
}
