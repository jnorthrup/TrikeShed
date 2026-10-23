package borg.trikeshed.cursor.query

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.kursive.sql.SqlParser
import borg.trikeshed.parse.kursive.sql.plan
import borg.trikeshed.userspace.nio.platform.spi.ColumnOperations
import borg.trikeshed.userspace.nio.platform.spi.KotlinColumnOperations
import borg.trikeshed.userspace.nio.spi.platformNioProviders
import kotlinx.benchmark.*

/**
 * TPC-H Q1 and Q6 from SQL text over generated `lineitem` rows: the statement is parsed, planned and
 * executed on every invocation, so the numbers include the whole path a client pays. [path] picks how:
 *  - `rows`:  boxed rows through the closure compiler (the S1 path, the baseline);
 *  - `jit`:   the same boxed rows through the platform's ExprCompiler (a class per statement);
 *  - `dense`: a column-major source on the pure-Kotlin kernels;
 *  - `ffi`:   the same source on the platform's native kernels.
 */
@State(Scope.Benchmark)
open class TpchShapeBench {
    @Param("1000000") var rows = 1_000_000
    @Param("dense") var path = "dense"

    private lateinit var source: Cursor
    private var ops: ColumnOperations = KotlinColumnOperations
    private var compiler: ExprCompiler = ClosureExprCompiler

    @Setup
    fun open() {
        val data = TpchLineitem.generate(rows)
        val providers = platformNioProviders()
        when (path) {
            "rows" -> source = data.size j { r: Int -> data[r] }
            "jit" -> {
                source = data.size j { r: Int -> data[r] }
                compiler = providers.filterIsInstance<ExprCompiler>().first()
            }
            "dense" -> source = data
            "ffi" -> {
                source = data
                ops = providers.filterIsInstance<ColumnOperations>().firstOrNull { it.identity != "kotlin" }
                    ?: error("no native column provider on this host (build it with buildJvmKernels)")
            }
            else -> error("unknown path $path")
        }
    }

    @Benchmark
    fun q1(): Int = SqlParser.parse(TpchLineitem.Q1)!!.plan({ source }, ops, compiler).result.size

    @Benchmark
    fun q6(): Any? = SqlParser.parse(TpchLineitem.Q6)!!.plan({ source }, ops, compiler).result.let { it[0].values[0] }
}
