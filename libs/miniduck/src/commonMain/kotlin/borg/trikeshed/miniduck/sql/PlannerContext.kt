package borg.trikeshed.miniduck.sql

import borg.trikeshed.miniduck.exec.ExecutionContext

data class PlannerConfig(
    val vectorWidth: Int = 2048,
)

class PlannerContext(
    val execCtx: ExecutionContext? = null,
    val config: PlannerConfig = execCtx?.config ?: PlannerConfig(),
)
