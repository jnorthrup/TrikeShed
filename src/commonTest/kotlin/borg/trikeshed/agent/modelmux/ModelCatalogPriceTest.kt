package borg.trikeshed.agent.modelmux

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import kotlin.test.*

class ModelCatalogPriceTest {
    @Test fun unquotedAndZeroPricesRemainDistinctThroughColumnMaterialization() {
        val catalog = ModelCatalog(s_[
            ModelCatalogEntry("provider", "unknown", false, -1, 0),
            ModelCatalogEntry("provider", "zero", false, -1, 0, pricePerMillionPromptTokens = 0.0),
            ModelCatalogEntry("provider", "priced", false, -1, 0, pricePerMillionPromptTokens = 2.5),
        ])
        assertNull(catalog[0].values[5])
        assertEquals(0.0, catalog[1].values[5])
        assertEquals(2.5, catalog[2].values[5])
        val column = catalog.dense(5)
        assertContentEquals(_a[false, true, true], column.valid)
        val prices = column.data as DoubleArray
        assertTrue(prices[0].isNaN())
        assertEquals(0.0, prices[1])
        assertEquals(2.5, prices[2])
    }
}
