package borg.trikeshed.cursor

import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*

class SimpleCursor constructor(
    val scalars: Series<ColumnMeta>,
    val data: Series<Series<Any>>,
    val o: Series<RecordMeta> = scalars α { it: ColumnMeta ->
        it as? RecordMeta ?: RecordMeta(
            it.name,
            it.first as? IOMemento ?: IOMemento.IoString
        )
    },
    val metaProviders: Series<`ColumnMeta↻`> = o.size j { index: Int -> { o[index] } },
    val c: Join<Int, (Int) -> RowVec> = data α { row -> (row α { it as Any? }) j metaProviders },
) : Cursor by c
