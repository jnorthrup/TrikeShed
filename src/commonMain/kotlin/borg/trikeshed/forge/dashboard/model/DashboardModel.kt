package borg.trikeshed.forge.dashboard.model

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf

data class KanbanCard(
    val id: String,
    val title: String,
    val state: String
)

data class FlywheelState(
    val lastCycleAt: Long,
    val timeline: Series<Long> = emptySeriesOf()
)

data class CcekNode(
    val nuid: String,
    val capability: String
)
