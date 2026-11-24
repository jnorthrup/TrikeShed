package narchy.spacegraph.representations

// Port of upstream src/core/rung.ts at 125b369 (shared selector for host-ordered ladders).
// One rung covers a normalized scale interval; a dead zone around its boundary keeps a rung from flickering.

interface Rung {
    val id: String
    val at: Double
}

/** Pick the last rung at or below `value`, holding the previous rung inside the dead zone. */
fun <T : Rung> selectRung(
    rungs: List<T>,
    value: Double,
    previous: String? = null,
    hysteresis: Double = 0.0,
): T? {
    if (rungs.isEmpty()) return null

    var index = 0
    rungs.forEachIndexed { position, rung -> if (rung.at <= value) index = position }
    val candidate = rungs[index]
    val current = rungs.firstOrNull { it.id == previous }

    if (current == null || current === candidate) return candidate
    if (candidate.at > current.at && value < candidate.at + hysteresis) return current
    if (candidate.at < current.at && value > current.at - hysteresis) return current
    return candidate
}
