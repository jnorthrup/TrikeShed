package borg.trikeshed.miniduck.schema

data class ColumnSchema(
    val ordinal: Int,
    val name: String,
)

data class TableSchema(
    val name: String,
    val columns: List<ColumnSchema>,
)
