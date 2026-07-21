/*
 * Copyright (c) 2026 TrikeShed Authors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
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

    /*
     * people and files tests removed since the types are being de-stubbed
     */
}
