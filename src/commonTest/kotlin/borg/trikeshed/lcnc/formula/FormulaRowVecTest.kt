package borg.trikeshed.lcnc.formula

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.`ColumnMeta↻`
import borg.trikeshed.cursor.cellsToRowVec
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lcnc.collections.associative.PropertyType
import borg.trikeshed.lcnc.collections.associative.PropertyValue
import borg.trikeshed.lib.ReifiedSplitSeries2
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.leftIdentity
import borg.trikeshed.reduction.FormulaReduction
import borg.trikeshed.reduction.ReducerRegistry
import borg.trikeshed.reduction.registerFormulaReduction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * M5 seam: the formula language evaluates on the Cursor substrate (RowVec)
 * with the same semantics as the associative Map form, and formulas are
 * admitted to the reduction registry as first-class reductions.
 */
class FormulaRowVecTest {
    private val source = """if(prop("Done"), 1, 0)"""

    private fun mapRow(done: Boolean): Map<String, PropertyValue> =
        mapOf("Done" to PropertyValue("done_id", PropertyType.CHECKBOX, done))

    private fun rowVec(done: Boolean): RowVec {
        val cells: Series<Any?> = 1 j { _: Int -> done }
        val keys: Series<String> = 1 j { _: Int -> "Done" }
        return cellsToRowVec(cells, keys)
    }

    @Test
    fun mapAndRowVecEvaluationAgree() {
        val ast = FormulaParser(source).parse()

        assertEquals(1.0, ast.evaluate(rowVec(true)))
        assertEquals(0.0, ast.evaluate(rowVec(false)))
        assertEquals(ast.evaluate(mapRow(true)), ast.evaluate(rowVec(true)))
        assertEquals(ast.evaluate(mapRow(false)), ast.evaluate(rowVec(false)))
    }

    @Test
    fun absentPropertyYieldsNullOnBothSubstrates() {
        val ast = PropFunction("Missing")

        // Map form: absent key → null. RowVec form: no column of that name → null.
        assertNull(ast.evaluate(mapRow(true)))
        assertNull(ast.evaluate(rowVec(true)))

        // …and the same holds through the whole formula: if(prop("Missing"), 1, 0) → 0.0 both ways.
        val guarded = FormulaParser("""if(prop("Missing"), 1, 0)""").parse()
        assertEquals(0.0, guarded.evaluate(mapRow(true)))
        assertEquals(guarded.evaluate(mapRow(true)), guarded.evaluate(rowVec(true)))
    }

    /** A RowVec cell may itself carry a [PropertyValue]; PropFunction unwraps it, as the Map form does. */
    @Test
    fun propertyValueCellsAreUnwrappedLikeTheMapForm() {
        val boxed: Series<Any?> = 1 j { _: Int -> PropertyValue("done_id", PropertyType.CHECKBOX, true) }
        val keys: Series<String> = 1 j { _: Int -> "Done" }
        val row = cellsToRowVec(boxed, keys)

        val ast = FormulaParser(source).parse()
        assertEquals(true, PropFunction("Done").evaluate(row))
        assertEquals(ast.evaluate(mapRow(true)), ast.evaluate(row))
    }

    @Test
    fun formulaReducerRowVecOverloadAgreesWithMapForm() {
        val reducer = FormulaReducer(source)
        assertEquals(1.0, reducer.reduce(rowVec(true)))
        assertEquals(reducer.reduce(mapRow(true)), reducer.reduce(rowVec(true)))
        assertEquals(reducer.reduce(mapRow(false)), reducer.reduce(rowVec(false)))
    }

    /**
     * `ColumnMeta.name` is a CharSequence, so a column named by something other than a String
     * must still resolve — otherwise `prop("Done")` silently yields null instead of the value.
     */
    @Test
    fun nonStringColumnNamesStillResolve() {
        val values: Series<Any?> = 1 j { _: Int -> true }
        val meta: Series<`ColumnMeta↻`> = 1 j { _: Int ->
            ColumnMeta(StringBuilder("Done"), IOMemento.IoBoolean).leftIdentity
        }
        val row: RowVec = ReifiedSplitSeries2(values, meta)

        assertEquals(true, PropFunction("Done").evaluate(row))
        assertEquals(1.0, FormulaParser(source).parse().evaluate(row))
    }

    /** The fast `execute` path and the introspectable checkpoint pipeline must agree. */
    @Test
    fun executeMatchesCheckpointPipeline() {
        val reduction = FormulaReduction(source)
        val rows: Series<RowVec> = 3 j { i: Int -> rowVec(i != 1) }
        val carrier = reduction.carrierAlg.carrier(rows)

        assertEquals(listOf<Any?>(1.0, 0.0, 1.0), reduction.execute(carrier))
        assertEquals(reduction.execute(carrier), reduction.executeWithCheckpoints(carrier).output)
    }

    @Test
    fun formulaReductionRegisteredAndExecutableViaReducerRegistry() {
        val before = ReducerRegistry.registry
        try {
            val reduction = registerFormulaReduction(source)

            val registered = ReducerRegistry.registry["formula"]
            assertNotNull(registered)
            assertSame(reduction, registered)

            val rows: Series<RowVec> = 2 j { i: Int -> rowVec(i == 0) }
            val out = registered.execute(reduction.carrierAlg.carrier(rows))
            assertEquals(listOf<Any?>(1.0, 0.0), out)
        } finally {
            ReducerRegistry.registry = before
        }
    }
}
