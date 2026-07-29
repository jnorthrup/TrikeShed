package borg.trikeshed.graph.query

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import kotlin.test.Test

class TestList {
    @Test
    fun test() {
        val meta1: () -> ColumnMeta = { ColumnMeta("a", IOMemento.IoInt) }
        val meta2: () -> ColumnMeta = { ColumnMeta("target", IOMemento.IoDouble) }
        val metas = 2 j { idx: Int -> when(idx) { 0 -> meta1; else -> meta2 } }

        val values: Series<Any?> = 2 j { colIndex: Int ->
            when (colIndex) {
                0 -> 1 as Any?
                else -> 1.5 as Any?
            }
        }
        val rv = borg.trikeshed.cursor.ReifiedSplitSeries2<Any?, () -> ColumnMeta>(values, metas)
        val v = rv.values.b(1)
        println("TEST_OUT: \$v")
    }
}
