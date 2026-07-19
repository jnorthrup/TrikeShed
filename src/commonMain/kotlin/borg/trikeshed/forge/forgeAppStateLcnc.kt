package borg.trikeshed.forge

import borg.trikeshed.kanban.ForgeKanbanCorrelation
import borg.trikeshed.kanban.KanbanCard
import borg.trikeshed.lcnc.isam.LcncBlock

data class LcncWorkPackageContent(
    val lane: String,
    val facet: String,
    val causalKey: String,
    val title: String,
    val description: String
)

val LcncBlock.lane: String? get() = (content as? LcncWorkPackageContent)?.lane
val LcncBlock.facet: String? get() = (content as? LcncWorkPackageContent)?.facet
val LcncBlock.causalKey: String? get() = (content as? LcncWorkPackageContent)?.causalKey
val LcncBlock.title: String? get() = (content as? LcncWorkPackageContent)?.title
val LcncBlock.description: String? get() = (content as? LcncWorkPackageContent)?.description

fun correlationToBlock(correlation: ForgeKanbanCorrelation, card: KanbanCard): LcncBlock {
    return LcncBlock(
        id = "task:${correlation.taskId}",
        type = "work-package",
        parentId = null,
        content = LcncWorkPackageContent(
            lane = card.columnId.value,
            facet = if (correlation.ready) "ready" else "dependency-gated",
            causalKey = correlation.causalKey,
            title = card.title,
            description = card.description,
        )
    )
}
