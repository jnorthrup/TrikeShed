package borg.trikeshed.cursor

import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import kotlin.jvm.JvmOverloads

class SimpleCursor @JvmOverloads constructor(
    val scalars: Series<ColumnMeta>,
    val data: Series<Series<Any>>,
    val o: Series<RecordMeta> = scalars α { it: ColumnMeta ->
        it as? RecordMeta ?: RecordMeta(
            it.name.toString(),
            it.type as? IOMemento ?: IOMemento.IoString
        )
    },
    val metaProviders: Series<`ColumnMeta↻`> = o.size j { index: Int -> { o[index] } },
    val c: Join<Int, (Int) -> RowVec> = data α { row -> (row α { it as Any? }).joins(metaProviders) },
) : Cursor by c
