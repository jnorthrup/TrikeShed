package borg.trikeshed.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import borg.trikeshed.forge.forgeAppHtml
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.j
import borg.trikeshed.lib.Series
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.TypeMemento
import borg.trikeshed.cursor.`ColumnMeta↻`
import borg.trikeshed.lib.get

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
open class UxMetricsBenchmark {

    lateinit var cursorFixture: Cursor
    lateinit var pathFixture: IntArray

    @Setup(Level.Trial)
    fun setup() {
        val rows = 1000
        val meta = ColumnMeta("id", IOMemento.IoInt, null)

        val rowVecs = rows j { i: Int ->
            val numCols = 1
            numCols j { col: Int ->
                (i as Any?) j { meta }
            }
        }

        cursorFixture = rowVecs as Cursor
        pathFixture = IntArray(10) { it * 10 }
    }

    @Benchmark
    fun coldStartInteractive(): String {
        return forgeAppHtml()
    }

    @Benchmark
    fun zoomLatency(): Cursor {
        val f: Series<RowVec> = cursorFixture
        val reordered: Series<RowVec> = pathFixture.size j { i: Int -> f.b(pathFixture[i]) }
        return reordered as Cursor
    }
}
