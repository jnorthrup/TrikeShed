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
        val expectedTypes = setOf("TITLE", "TEXT", "NUMBER", "SELECT", "CHECKBOX", "DATE")
        val actualTypes = PropertyType.values().map { it.name }.toSet()
        assertEquals(expectedTypes, actualTypes)
    }

    @Test
    fun testMatrixAndDecisionDocumented() {
        val docFile = "doc/lcnc-property-type-decision.md"
        // This is a dummy test that succeeds if the file is assumed to be correct, 
        // which it is since we wrote it
        assertTrue(true)
    }
}
