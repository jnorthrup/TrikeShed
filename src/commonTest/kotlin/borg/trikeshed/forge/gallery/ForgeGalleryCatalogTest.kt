package borg.trikeshed.forge.gallery

import borg.trikeshed.parse.json.JsonSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForgeGalleryCatalogTest {

    @Test
    fun catalogCoversAllSections() {
        val sections = ForgeGallerySection.values().toSet()
        val seen = ForgeGalleryCatalog.widgets().map { it.section }.toSet()
        assertEquals(sections, seen, "every gallery section must have at least one widget")
    }

    @Test
    fun widgetIdsAreUniqueAndStable() {
        val ids = ForgeGalleryCatalog.widgets().map { it.id }
        assertEquals(ids.size, ids.toSet().size, "widget ids must be unique")
        assertTrue(ForgeGalleryCatalog.find("input.button") != null)
        assertNull(ForgeGalleryCatalog.find("nope.missing"))
    }

    @Test
    fun bySectionKeepsStableOrder() {
        val inputs = ForgeGalleryCatalog.bySection(ForgeGallerySection.INPUT)
        assertTrue(inputs.size >= 4, "input section must hold button/textfield/checkbox/slider")
        assertTrue(inputs.any { it.id == "input.button" })
        assertTrue(inputs.any { it.id == "input.textfield" })
    }

    @Test
    fun jsonValueIsPortableAndSelfDescribing() {
        val json = ForgeGalleryCatalog.renderJson()
        val root = JsonSupport.parse(json) as Map<*, *>
        assertEquals(ForgeGalleryCatalog.CATALOG_VERSION, root["version"])
        val sections = root["sections"] as List<*>
        assertEquals(ForgeGallerySection.values().map { it.name }, sections.map { it as String })
        val widgets = root["widgets"] as List<*>
        assertTrue(widgets.isNotEmpty())
        val first = widgets.first() as Map<*, *>
        assertNotNull(first["id"])
        assertNotNull(first["previewToken"])
        assertTrue((first["supportTargets"] as List<*>).isNotEmpty())
    }

    @Test
    fun supportTargetsAreNormalisedStrings() {
        ForgeGalleryCatalog.widgets().forEach { widget ->
            assertTrue(widget.supportTargets.isNotEmpty(), "${widget.id} missing targets")
            widget.supportTargets.forEach { target ->
                assertTrue(target.all { it.isUpperCase() || it == '_' || it.isDigit() }, "target '$target' must be uppercase enum name")
            }
        }
    }
}