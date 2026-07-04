package borg.trikeshed.forge.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetShowcaseCatalogTest {
    @Test
    fun catalogHasAllSixLayouts() {
        val pages = widgetShowcasePages()
        assertEquals(6, pages.size)
        assertTrue(pages.map { it.layout }.containsAll(WidgetShowcaseLayout.entries))
    }

    @Test
    fun everyPageHasWidgetsAndNotes() {
        widgetShowcasePages().forEach { page ->
            assertTrue(page.widgets.isNotEmpty(), "${page.id} should have widgets")
            assertTrue(page.templateNotes.isNotEmpty(), "${page.id} should have template notes")
        }
    }
}
