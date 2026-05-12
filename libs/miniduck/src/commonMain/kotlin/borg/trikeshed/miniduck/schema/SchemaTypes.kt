package borg.trikeshed.miniduck.schema

data class ColumnSchema(
    val ordinal: Int,
    val name: CharSequence,
)

data class TableSchema(
    val name: CharSequence,
    val columns: List<ColumnSchema>,
)
