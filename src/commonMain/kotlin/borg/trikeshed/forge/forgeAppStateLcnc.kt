package borg.trikeshed.forge

import borg.trikeshed.kanban.ForgeKanbanCorrelation
import borg.trikeshed.kanban.KanbanCard
import borg.trikeshed.lcnc.isam.LcncBlock

import kotlinx.serialization.Serializable

@Serializable
sealed interface LcncBlockContent

@Serializable
data class LcncWorkPackageContent(
    val lane: String,
    val facet: String,
    val causalKey: String,
    val title: String,
    val description: String
) : LcncBlockContent

val LcncBlock.lane: String?
    get() = (content as? LcncWorkPackageContent)?.lane ?: (content as? Map<*, *>)?.get("lane") as? String

val LcncBlock.facet: String?
    get() = (content as? LcncWorkPackageContent)?.facet ?: (content as? Map<*, *>)?.get("facet") as? String

val LcncBlock.causalKey: String?
    get() = (content as? LcncWorkPackageContent)?.causalKey ?: (content as? Map<*, *>)?.get("causalKey") as? String

val LcncBlock.title: String?
    get() = (content as? LcncWorkPackageContent)?.title ?: (content as? Map<*, *>)?.get("title") as? String

val LcncBlock.description: String?
    get() = (content as? LcncWorkPackageContent)?.description ?: (content as? Map<*, *>)?.get("description") as? String

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
            description = card.description
        )
    )
}
