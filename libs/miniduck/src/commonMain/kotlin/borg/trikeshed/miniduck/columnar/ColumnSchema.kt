package borg.trikeshed.miniduck.columnar

data class ColumnSchema(val name: String, val type: ColumnType, val indexPluginName: String? = null) {
    init { require(name.isNotEmpty()) { "ColumnSchema name must not be empty" } }
}
