package org.xvm.cursor

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.xvm.List as ShadowList

class ListDetourTest {
    @Test
    fun `test default list creation detour without codec`() {
        ShadowList.codec = null
        val list = ShadowList.listOf("apple", "banana", "cherry")
        assertEquals(listOf("apple", "banana", "cherry"), list)
    }

    @Test
    fun `test list creation detour with custom codec`() {
        // Setup codec that reverses strings in the list
        ShadowList.codec = { original ->
            original.map { (it as String).reversed() }
        }

        try {
            val list = ShadowList.listOf("apple", "banana", "cherry")
            assertEquals(listOf("elppa", "ananab", "yrrehc"), list)
        } finally {
            ShadowList.codec = null
        }
    }
}
