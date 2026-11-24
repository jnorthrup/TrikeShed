package narchy.spacegraph.representations

import narchy.spacegraph.zoom.ScaleSample

// Port of upstream src/core/ladder.ts at 125b369: rung ordering, eligibility gating, hysteresis and
// per-entity resolution. Payload semantics stay with each ladder; this file never inspects them.

interface RungGate {
    val kinds: List<String>?
    val minImportance: Double?
}

interface LadderEntry {
    val id: String
    val kind: String
    val importance: Double?
}

interface RungStop {
    val at: Double
    val id: String
}

/** Build rungs from scale stops; a stop without a payload is dropped and `at` is clamped to [0, 1]. */
fun <Stop : RungStop, Payload, Candidate> rungsFromStops(
    stops: List<Stop>,
    payloads: Map<String, Payload>,
    makeRung: (at: Double, id: String, payload: Payload) -> Candidate,
): List<Candidate> = stops.mapNotNull { stop ->
    val payload = payloads[stop.id] ?: return@mapNotNull null
    makeRung(stop.at.coerceIn(0.0, 1.0), stop.id, payload)
}

/** Ordering, gating, hysteresis and id-keyed resolution; subclasses map a rung to a selection. */
abstract class LadderBase<Entry : LadderEntry, Candidate, Selection>(
    rungs: List<Candidate> = emptyList(),
    hysteresis: Double? = null,
) where Candidate : Rung, Candidate : RungGate {
    private val rungs: MutableList<Candidate> = rungs.sortedBy { it.at }.toMutableList()
    private val hysteresis: Double = hysteresis ?: 0.0

    val ids: List<String>
        get() = rungs.map { it.id }

    fun register(rung: Candidate): LadderBase<Entry, Candidate, Selection> {
        rungs.add(rung)
        rungs.sortBy { it.at }
        return this
    }

    /** One entry's rung at one scale sample, with hysteresis against its previous rung. */
    fun select(entry: Entry, sample: ScaleSample, previous: String? = null): Selection? {
        val importance = entry.importance ?: sample.importance
        val candidates = rungs.filter { rung ->
            (rung.kinds == null || rung.kinds!!.contains(entry.kind)) &&
                (rung.minImportance == null || importance >= rung.minImportance!!)
        }
        val rung = selectRung(candidates, sample.normalized, previous, hysteresis) ?: return null
        return createSelection(entry, sample, rung)
    }

    /** Every entry's selection, keyed by the id the host uses for selection and permalinks. */
    fun resolve(
        entries: List<Entry>,
        sample: ScaleSample,
        previous: Map<String, String> = emptyMap(),
    ): Map<String, Selection> {
        val out = LinkedHashMap<String, Selection>()
        for (entry in entries) {
            val selection = select(entry, sample, previous[entry.id]) ?: continue
            out[entry.id] = selection
        }
        return out
    }

    protected abstract fun createSelection(entry: Entry, sample: ScaleSample, rung: Candidate): Selection
}
