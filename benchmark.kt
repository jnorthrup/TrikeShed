import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.graph.query.QueryEngine
import kotlin.system.measureTimeMillis

fun main() {
    val numRows = 1_000_000
    val meta1: () -> ColumnMeta = { ColumnMeta("a", IOMemento.IoInt) }
    val meta2: () -> ColumnMeta = { ColumnMeta("target", IOMemento.IoDouble) }
    val meta3: () -> ColumnMeta = { ColumnMeta("c", IOMemento.IoString) }

    // Create a mock cursor with ReifiedSplitSeries2 to reflect real-world usage where possible
    val metas = 3 j { idx -> when(idx) { 0 -> meta1; 1 -> meta2; else -> meta3 } }

    val cursor: Cursor = numRows j { rowIndex ->
        val values = 3 j { colIndex ->
            when (colIndex) {
                0 -> rowIndex
                1 -> rowIndex * 1.5
                else -> "string$rowIndex"
            }
        }
        ReifiedSplitSeries2(values, metas)
    }

    val engine = QueryEngine(cursor)

    // Warmup
    engine.extractDoubleColumn("target")

    // Measure
    val time = measureTimeMillis {
        engine.extractDoubleColumn("target")
    }

    println("Time: $time ms")
}
