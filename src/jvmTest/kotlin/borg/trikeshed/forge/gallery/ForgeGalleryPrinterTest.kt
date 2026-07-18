package borg.trikeshed.forge.gallery

import borg.trikeshed.forge.blackboard.ForgeBlackboardView
import borg.trikeshed.parse.json.JsonSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForgeGalleryPrinterTest {

    @Test
    fun renderListsEverySectionAndWidget() {
        val text = ForgeGalleryPrinter.render()
        ForgeGallerySection.values().forEach { section ->
            assertTrue("── ${section.name}" in text, "section header missing for $section")
        }
        ForgeGalleryCatalog.widgets().forEach { widget ->
            assertTrue(widget.id in text, "widget id missing from print: ${widget.id}")
        }
    }

    @Test
    fun renderIncludesCornerButtonIdsAndHotkeys() {
        val text = ForgeGalleryPrinter.render()
        listOf("back-to-board", "depth-toggle", "fit-viewport", "center-selected").forEach { id ->
            assertTrue(id in text, "corner button id $id missing from print")
        }
        listOf("[1]", "[d]", "[f]", "[c]").forEach { hotkey ->
            assertTrue(hotkey in text, "hotkey $hotkey missing from print")
        }
    }

    @Test
    fun renderJsonIsPortableSeed() {
        val payload = ForgeGalleryPrinter.renderJson()
        val parsed = JsonSupport.parse(payload) as Map<*, *>
        val catalog = parsed["catalog"] as Map<*, *>
        assertEquals(ForgeGalleryCatalog.CATALOG_VERSION, catalog["version"])
        val blackboard = parsed["blackboard"] as Map<*, *>
        assertEquals(ForgeBlackboardView.DEFAULT.surface, blackboard["surface"])
        assertTrue((blackboard["cornerButtons"] as List<*>).isNotEmpty())
    }
}