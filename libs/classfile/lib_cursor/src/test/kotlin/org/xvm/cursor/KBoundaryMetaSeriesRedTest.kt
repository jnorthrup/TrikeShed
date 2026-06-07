package org.xvm.cursor

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KBoundaryMetaSeriesRedTest {
    @Test
    fun `K boundary maps each K to a faceted RowVec and collector is Series of Series RowVec`() {
        val keys = seriesOfKeys(
            "OpK:loadModule",
            "NextK:start",
            "XSrcFile:JitConnector",
        )

        val boundary = KBoundary(
            name = Symbols.symbol("jitconnector-k-boundary"),
            keys = keys,
            row = { key: String ->
                KRowVecFactory.rowVec(
                    key = Symbols.symbol(key),
                    facet = PointcutFacet.XSrcFile,
                    columns = XSrcFileFacetFactory.columnRefs(),
                )
            },
        )

        val rows: Series<RowVec> = boundary.rows()
        val collected: Series<Series<RowVec>> = MetaSeriesCollector.collect(rows)

        assertEquals(keys.a, rows.a)
        assertEquals(keys.a, collected.a)
        assertEquals(PointcutFacet.XSrcFile.name, StringPool.resolve(rows.b(0).b(2).a as Int))
    }
}

private fun seriesOfKeys(vararg keys: String): Series<String> = keys.size j { i: Int -> keys[i] }
