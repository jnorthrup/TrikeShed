package borg.trikeshed.forge.dashboard.serialization

import borg.trikeshed.forge.dashboard.model.*
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.lib.toArray

object DashboardSerialization {
    fun toJson(card: KanbanCard): String = JsonSupport.stringify(
        mapOf(
            "id" to card.id,
            "title" to card.title,
            "state" to card.state
        )
    )

    fun toJson(flywheel: FlywheelState): String = JsonSupport.stringify(
        mapOf(
            "lastCycleAt" to flywheel.lastCycleAt,
            "timeline" to flywheel.timeline.toArray().toList()
        )
    )

    fun toJson(node: CcekNode): String = JsonSupport.stringify(
        mapOf(
            "nuid" to node.nuid,
            "capability" to node.capability
        )
    )
}
