package borg.trikeshed.lcnc.collections.associative

import kotlin.test.Test
import kotlin.test.assertEquals

class LcncAssociativeTest {
    @Test
    fun testSupportedPropertyTypesOnly() {
        val expectedTypes = setOf(
            "TITLE", "TEXT", "NUMBER", "SELECT", "CHECKBOX", "DATE"
        )
        val actualTypes = PropertyType.values().map { it.name }.toSet()
        assertEquals(expectedTypes, actualTypes, "PropertyType should only contain supported types")
    }
}
