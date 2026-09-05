package borg.trikeshed.forge.sheet

import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.parse.json.ValueBudget
import kotlin.test.*

class ProjectionBudgetTest {
    @Test fun traversalRejectsWideDeepAndLargeValuesBeforeSerialization() {
        assertEquals("work_limit", ValueBudget(maxNodes = 10).violation(List(1000) { it }))
        assertEquals("payload_limit", ValueBudget(maxChars = 10).violation("x".repeat(100)))
        assertEquals("depth_limit", ValueBudget(maxDepth = 2).violation(listOf(listOf(listOf(1)))))
        assertNull(ValueBudget().violation(mapOf("text" to "hello", "n" to 1)))
    }

    @Test fun sheetBudgetIsFamilyWideAndNeverLeavesDanglingReferences() {
        val doc = confixDoc(JsonSupport.stringify(mapOf("first" to List(100) { mapOf("x" to it) }, "last" to 3)))
        val sheets = confixSheets("root", "root", doc, maxSheets = 3, maxRows = 5)
        assertTrue(sheets.size <= 3)
        assertTrue(sheets.sumOf { it.rows.size } <= 5)
        assertTrue(sheets.any { it.truncated })
        val ids = sheets.map { it.id }.toSet()
        val refs = sheets.flatMap { it.rows }.flatten().filterIsInstance<SheetRef>()
        assertTrue(refs.all { it.sheet in ids })
    }

    @Test fun escapedKeysRetainDistinctSheetIdentity() {
        val doc = confixDoc("""{"a/b":{"x":1},"a":{"b":{"x":2}}}""")
        val sheets = confixSheets("root", "root", doc)
        assertEquals(sheets.size, sheets.map { it.id }.distinct().size)
        assertTrue(sheets.any { it.id == "root/a~1b" })
        assertTrue(sheets.any { it.id == "root/a/b" })
    }
}
