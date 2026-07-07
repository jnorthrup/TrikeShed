package borg.trikeshed.isam

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*

class MonoCursor<T>(
    val names: Series<CharSequence>,
    val type: IOMemento,
    val series: Series<Series<T>>
) : Cursor {
    private val colMetas = names.view.mapIndexed { idx, name ->
        RecordMeta(name.toString(), type, idx * (type.networkSize ?: 0), (idx + 1) * (type.networkSize ?: 0))
    }

    override val a: Int get() = series.a
    override val b: (Int) -> RowVec = { rowIdx ->
        val rowVals = series.b(rowIdx)
        names.size j { colIdx ->
            rowVals.b(colIdx) j { colMetas[colIdx] }
        }
    }
}
