package borg.trikeshed.cursor.query

import borg.trikeshed.cursor.*

/**
 * Deterministic TPC-H-shaped `lineitem` columns for the DuckDB-parity benchmark and the tests
 * that pin the kernels to the closure path: the same generator feeds both, so a bench number and
 * a test answer describe the same data. Dates are days since 1970-01-01 (1992-01-02 ..
 * 1998-12-01), as TPC-H Q1 (`shipdate <= 10471`) and Q6 (`8766 <= shipdate < 9131`) read them.
 */
object TpchLineitem {
    const val Q1 = "SELECT returnflag, linestatus, SUM(quantity) AS sum_qty, SUM(extendedprice) AS sum_base_price, " +
        "SUM(extendedprice * (1 - discount)) AS sum_disc_price, " +
        "SUM(extendedprice * (1 - discount) * (1 + tax)) AS sum_charge, " +
        "AVG(quantity) AS avg_qty, AVG(extendedprice) AS avg_price, AVG(discount) AS avg_disc, COUNT(*) AS count_order " +
        "FROM lineitem WHERE shipdate <= 10471 GROUP BY returnflag, linestatus ORDER BY returnflag, linestatus"

    const val Q6 = "SELECT SUM(extendedprice * discount) AS revenue FROM lineitem " +
        "WHERE shipdate >= 8766 AND shipdate < 9131 AND discount >= 0.05 AND discount <= 0.07 AND quantity < 24"

    fun generate(rows: Int, seed: Long = 42): ColumnCursor {
        var x = seed
        fun next(bound: Int): Int {
            x = x * 6364136223846793005L + 1442695040888963407L
            return ((x ushr 33) % bound).toInt()
        }
        val quantity = DoubleArray(rows)
        val price = DoubleArray(rows)
        val discount = DoubleArray(rows)
        val tax = DoubleArray(rows)
        val ship = LongArray(rows)
        val flag = IntArray(rows)
        val status = IntArray(rows)
        for (r in 0 until rows) {
            val q = 1 + next(50)
            quantity[r] = q.toDouble()
            price[r] = q * (900 + next(100_000) / 100.0)
            discount[r] = next(11) / 100.0
            tax[r] = next(9) / 100.0
            ship[r] = 8036L + next(2526)
            // TPC-H: lines shipped before 1995-06-17 (day 9298) are finished, with a return flag.
            val finished = ship[r] <= 9298
            flag[r] = if (finished) (if (next(2) == 0) 0 else 2) else 1
            status[r] = if (finished) 0 else 1
        }
        return ColumnCursor.of(
            arrayOf(
                ColumnMeta("quantity", IOMemento.IoDouble), ColumnMeta("extendedprice", IOMemento.IoDouble),
                ColumnMeta("discount", IOMemento.IoDouble), ColumnMeta("tax", IOMemento.IoDouble),
                ColumnMeta("shipdate", IOMemento.IoLong),
                ColumnMeta("returnflag", IOMemento.IoString), ColumnMeta("linestatus", IOMemento.IoString),
            ),
            arrayOf(
                DenseColumn(quantity), DenseColumn(price), DenseColumn(discount), DenseColumn(tax), DenseColumn(ship),
                DenseColumn(DictColumn(flag, arrayOf("A", "N", "R"))),
                DenseColumn(DictColumn(status, arrayOf("F", "O"))),
            ),
        )
    }
}
