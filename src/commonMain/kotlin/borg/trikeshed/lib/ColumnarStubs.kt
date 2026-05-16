package borg.trikeshed.lib

/**
 * Columnar type stubs — RED test scaffolding for unimplemented columnar features.
 * These types exist solely so that columnar tests compile; they will throw on use.
 */

enum class ColumnType {
    Long, Double, CharSequence, Bytes
}

/** Bridge ColumnType to IOMemento */
val ColumnType.io: borg.trikeshed.isam.meta.IOMemento get() = when (this) {
    ColumnType.Long -> borg.trikeshed.isam.meta.IOMemento.IoLong
    ColumnType.Double -> borg.trikeshed.isam.meta.IOMemento.IoDouble
    ColumnType.CharSequence -> borg.trikeshed.isam.meta.IOMemento.IoString
    ColumnType.Bytes -> borg.trikeshed.isam.meta.IOMemento.IoString
}

data class ColumnSchema(
    val name: CharSequence,
    val type: ColumnType,
    val indexPluginName: CharSequence? = null,
) {
    init {
        require(name.isNotEmpty()) { "ColumnSchema name must not be empty" }
    }
}

/** Bridge ColumnSchema to ColumnMeta */
fun ColumnSchema.toMeta(): borg.trikeshed.cursor.ColumnMeta =
    borg.trikeshed.cursor.ColumnMeta(name, type.io)

object IsamCursor {
    fun open(path: CharSequence): IsamCursor = throw UnsupportedOperationException("IsamCursor.open not implemented")
}

interface IndexCursor {
    fun seek(blockOffset: Long)
    fun next(): Boolean
    fun current(): Long
}