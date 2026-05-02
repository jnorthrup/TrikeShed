package borg.trikeshed.miniduck.exec

/**
 * Minimal ExecutionContext stub used to satisfy platform-specific implementations and tests.
 * Constructor is permissive: accepts any schema manager and planner config.
 */
class ExecutionContext(val schemaManager: Any?, val plannerConfig: Any? = null, val tableSource: TableSource? = null)
