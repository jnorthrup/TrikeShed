package borg.trikeshed.agent.modelmux

import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyStrict

/** Decode bounded local receipts, rejecting partial JSON and sequence gaps. */
fun missionEvents(source: String): Series<borg.trikeshed.agent.modelmux.MissionEvent> {
    require(source.length <= 8_388_608) { "Mission journal exceeds inspection bound" }
    val rows = source.lineSequence().filter { it.isNotBlank() }.toList()
    require(rows.size <= 10_000) { "Too many mission receipts" }
    return rows.mapIndexed { index, line ->
        val row = reifyStrict(line) as? Map<*, *> ?: error("Invalid mission receipt")
        require((row["sequence"] as? Number)?.toDouble() == index + 1.0) { "Mission receipt sequence gap" }
        val claim = (row["claim_id"] as? Number)?.toDouble() ?: error("Missing claim identity")
        require(claim.isFinite() && claim > 0 && claim <= 9_007_199_254_740_991.0 && claim == claim.toLong().toDouble()) {
            "Invalid claim identity"
        }
        val task = row["task"] as? String ?: error("Missing task identity")
        val worker = row["worker"] as? String ?: error("Missing worker identity")
        require(task.isNotBlank() && worker.isNotBlank()) { "Empty receipt identity" }
        val targets = if ("targets" !in row) emptySeriesOf() else {
            val values = row["targets"] as? List<*> ?: error("Invalid receipt targets")
            values.map { it as? String ?: error("Invalid receipt target") }.toSeries()
        }
        borg.trikeshed.agent.modelmux.MissionEvent(
            task,
            borg.trikeshed.agent.modelmux.MissionPhase.valueOf(
                row["phase"] as? String ?: error("Missing receipt phase")
            ),
            claim.toLong(),
            worker,
            borg.trikeshed.agent.modelmux.MissionRole.valueOf(
                (row["type"] as? String ?: "work").uppercase()
            ),
            targets
        )
    }.toSeries()
}
