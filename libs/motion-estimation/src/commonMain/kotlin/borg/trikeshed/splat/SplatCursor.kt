package borg.trikeshed.splat

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.α
import borg.trikeshed.lib.view
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.ConfixCell
import borg.trikeshed.parse.confix.ConfixIndex
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.reify

// ── Cursor integration ───────────────────────────────────────────
// Convert a Series<RowVec> to Series<Splat> by mapping each RowVec to Splat
fun Series<RowVec>.toSplatSeries(): Series<Splat> {
    val size = this.size
    val items = Array<Splat>(size)
    for (i in 0 until size) {
        val row = this[i]
        val arr = Array<Any?>(2)
        arr[0] = this[i]
        arr[1] = { borg.trikeshed.cursor.ColumnMeta("splat", borg.trikeshed.cursor.IOMemento.IoObject, null) }
        items[i] = (2 j { i: Int -> arr[i] }) as Splat
    }
    return size j { idx: Int -> items[idx] }
}

fun Series<Splat>.toSplatSet(): Cursor {
    val size = this.size
    val arr = Array<RowVec>(size)
    for (i in 0 until size) arr[i] = this[i].toRowVec()
    return size j { idx: Int -> arr[idx] }
}

fun Splat.toRowVec(): RowVec {
    val arr = Array<Any?>(2)
    arr[0] = this
    arr[1] = { borg.trikeshed.cursor.ColumnMeta("splat", borg.trikeshed.cursor.IOMemento.IoObject, null) }
    return (2 j { i: Int -> arr[i] }) as RowVec
}

fun splatSetOf(vararg splats: Splat): Cursor {
    val rowsArray = splats.map { it.toRowVec() }.toTypedArray()
    val size = rowsArray.size
    return size j { idx: Int -> rowsArray[idx] }
}

fun RowVec.toSplat(): Splat = this.get(0) as Splat