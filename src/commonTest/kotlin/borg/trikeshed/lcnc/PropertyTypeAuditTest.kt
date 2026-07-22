/*
 * Copyright (c) 2026 TrikeShed Authors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
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
            "URL", "EMAIL", "PHONE_NUMBER",
        )
        val actualTypes = PropertyType.values().map { it.name }.toSet()
        assertEquals(expectedTypes, actualTypes)
    }

    @Test
    fun testUnimplementedRelationalTypesRemainAbsent() {
        val removedTypes = setOf("MULTI_SELECT", "PEOPLE", "FILES", "FORMULA", "RELATION", "ROLLUP")
        val actualTypes = PropertyType.values().map { it.name }.toSet()
        assertTrue(actualTypes.intersect(removedTypes).isEmpty())
    }
}
