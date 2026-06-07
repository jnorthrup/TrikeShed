package org.xvm.cursor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * ReduxMutableSeries capture is the schema/template row for the typedef log.
 * The template must come from the real logging entry shape, not an empty dummy.
 */
class ReduxMutableSeriesTemplateFromLoggingTest {

    @BeforeEach
    fun reset() {
        StringPool.clear()
        TypedefResolutionSeries.reset()
    }

    @Test
    fun `redux journal exposes typed capture template from logging entry shape`() {
        val journal = TypedefResolutionSeries.reduxJournal()
        val template = TypedefResolutionSeries.journalTemplate()

        assertEquals(template, journal.capture)
        assertEquals("TypedefResolutionSeries.record", template.clsName)
        assertTrue(template.format.contains("factId"), "template must name factId")
        assertTrue(template.format.contains("nano"), "template must name nano")
        assertTrue(template.format.contains("poolId"), "template must name poolId")
        assertTrue(template.format.contains("isReverted"), "template must name revert marker")
    }

    @Test
    fun `logging-derived template survives real fact capture`() {
        val poolId = StringPool.intern("LoggingTemplatePool")
        TypedefResolutionSeries.record(poolId, 0, "pkg.Log1", "formatA", true)
        TypedefResolutionSeries.record(poolId, 1, "pkg.Log2", "formatB", false)
        TypedefResolutionSeries.drain()

        val journal = TypedefResolutionSeries.metaSeries()
        val reduced = journal.reify()
        val template = journal.capture

        assertEquals(2, reduced.size)
        assertEquals("TypedefResolutionSeries.record", template.clsName)
        assertTrue(template.format.contains("clsNameId"))
        assertTrue(template.format.contains("formatId"))
    }

    @Test
    fun `template parameters visible in reified row output`() {
        val poolId = StringPool.intern("TemplateOutputPool")
        for (i in 0 until 5) {
            TypedefResolutionSeries.record(poolId, i, "pkg.Template$i", "fmt$i", i % 2 == 0)
        }
        TypedefResolutionSeries.drain()

        val rowVec = TypedefResolutionSeries.toRowVec()
        val keys = rowVec.substringBefore("|")
        var keyCount = 1
        for (ch in keys) {
            if (ch == ',') keyCount++
        }

        assertTrue(rowVec.contains("pkg.Template0"))
        assertEquals(5, keyCount)
        assertTrue(TypedefResolutionSeries.journalTemplate().format.contains("success"))
    }
}
