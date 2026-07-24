package borg.trikeshed.lcnc

import borg.trikeshed.lcnc.collections.associative.PropertyType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PropertyTypeAuditTest {
    @Test
    fun testReducedEnum() {
        // Assert that the enum only contains the implemented cases
        val expectedTypes = setOf(
            "TITLE", "TEXT", "NUMBER", "SELECT", "CHECKBOX", "DATE",
            "URL", "EMAIL", "PHONE_NUMBER", "MULTI_SELECT"
        )
        val actualTypes = PropertyType.values().map { it.name }.toSet()
        assertEquals(expectedTypes, actualTypes)
    }

    @Test
    fun testUnimplementedRelationalTypesRemainAbsent() {
        val removedTypes = setOf("PEOPLE", "FILES", "FORMULA", "RELATION", "ROLLUP")
        val actualTypes = PropertyType.values().map { it.name }.toSet()
        assertTrue(actualTypes.intersect(removedTypes).isEmpty())
    }
}
