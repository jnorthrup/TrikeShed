package borg.trikeshed.cursor

import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import kotlin.jvm.JvmOverloads

class SimpleCursor @JvmOverloads constructor(
    val scalars: Series<ColMeta>,
    val data: Series<Series<Any>>,
    val o: Series<RecordMeta> = scalars α {
        (it as? RecordMeta) ?: RecordMeta(it.name, it.first as? IOMemento ?: IOMemento.IoString)
    },
    val c: Join<Int, (Int) -> RowVec> = data α { it.zip(o) },
) : Cursor by c