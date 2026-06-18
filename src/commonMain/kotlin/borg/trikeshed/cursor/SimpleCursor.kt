package borg.trikeshed.cursor

import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import kotlin.jvm.JvmOverloads

/**
 * SimpleCursor — explicit composition over delegation.
 * 
 * Cursor = Series<RowVec> = Join<Int, (Int) -> RowVec>
 * This class wraps the Join directly without relying on Kotlin delegation
 * to avoid XVM-era parameter mismatches.
 */
@JvmInline
value class SimpleCursor(
    val join: Join<Int, (Int) -> RowVec>,
) : Series<RowVec> by join {

    companion object {
        @JvmOverloads
        @JvmStatic
        fun build(
            scalars: Series<ColumnMeta>,
            data: Series<Series<Any>>,
        ): SimpleCursor {
            val o: Series<RecordMeta> = scalars α { it: ColumnMeta ->
                val rm: RecordMeta = it as? RecordMeta ?: RecordMeta(
                    name = it.name.toString(),
                    type = it.type as? IOMemento ?: IOMemento.IoString,
                    begin = -1,
                    end = -1,
                    decoder = { _: ByteArray -> null },
                    encoder = { _: Any? -> ByteArray(0) },
                )
                rm
            }
            val metaProviders: Series<`ColumnMeta↻`> = o.size j { index: Int -> { o[index] } }
            val c: Join<Int, (Int) -> RowVec> = data α { row ->
                (row α { it as Any? }).joins(metaProviders)
            }
            return SimpleCursor(c)
        }
    }
}