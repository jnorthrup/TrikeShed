package borg.trikeshed.miniduck.exec

import borg.trikeshed.miniduck.schema.SchemaManager

/** Execution context for SQL query execution. */
class ExecutionContext(
    val schemaManager: SchemaManager? = null,
    val tableSource: TableSource? = null,
)