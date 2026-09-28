package borg.trikeshed.forge.dashboard.serialization

import borg.trikeshed.parse.jsonOf

import borg.trikeshed.forge.dashboard.model.*
import borg.trikeshed.lib.toArray

object DashboardSerialization {
    fun toJson(card: KanbanCard): String = jsonOf(
        mapOf(
            "id" to card.id,
            "title" to card.title,
            "state" to card.state
        )
    )

    fun toJson(node: CcekNode): String = jsonOf(
        mapOf(
            "nuid" to node.nuid,
            "capability" to node.capability
        )
    )
}
