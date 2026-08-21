package borg.trikeshed.forge

import borg.trikeshed.parse.json.JsonSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shell ForgeApp renders must be the one `web/script.js` drives: same template, every slot
 * filled, the seed parseable and shaped the way the script reads it.
 */
class ForgeAppShellTest {

    private val html = ForgeApp.renderHtml(userId = "forge-shell-test")

    @Test
    fun everyTemplateSlotIsFilled() {
        for (slot in listOf(ForgeApp.SEED_SLOT, ForgeApp.STYLES_SLOT, ForgeApp.SCRIPT_SLOT, ForgeApp.GALLERY_SLOT)) {
            assertFalse(html.contains(slot), "unfilled slot $slot")
        }
        assertFalse(html.contains("{{"), "stray mustache in rendered shell")
    }

    @Test
    fun shellIsTheWebTemplateScriptJsExpects() {
        // ids script.js resolves at load; a missing one is a null-deref on first paint
        for (id in listOf(
            "forge-seed", "page-tree", "breadcrumb", "doc-title", "doc-icon", "doc-blocks", "doc-scroll",
            "board-scroll", "board-canvas", "slash-menu", "seed-note", "sync-note",
            "btn-view-doc", "btn-view-board", "btn-view-graph", "btn-board", "btn-graph", "btn-home", "btn-new-page",
            "graph-scroll", "graph-canvas", "graph-empty", "graph-zoom-pill", "graph-fit",
        )) {
            assertTrue(html.contains("id=\"$id\""), "shell lacks #$id")
        }
    }

    @Test
    fun pwaWiringIsRelativeNotRootScoped() {
        assertTrue(html.contains("href=\"./manifest.webmanifest\""))
        assertTrue(html.contains("register('./sw.js')"))
        assertFalse(html.contains("href=\"/manifest.webmanifest\""), "root-absolute manifest would pin the PWA to the origin root")
        assertTrue(html.contains("./icons/forge-icon.svg"))
    }

    @Test
    fun seedParsesAndCarriesWhatTheScriptReads() {
        val start = html.indexOf("<script id=\"forge-seed\" type=\"application/json\">") + "<script id=\"forge-seed\" type=\"application/json\">".length
        val end = html.indexOf("</script>", start)
        val seedText = html.substring(start, end)
        assertFalse(seedText.contains("</"), "a literal </ inside the JSON script element ends it early")
        @Suppress("UNCHECKED_CAST")
        val seed = JsonSupport.parse(seedText.replace("<\\/", "</")) as Map<String, Any?>
        assertEquals("forge-shell-test", seed["userId"])
        val board = assertNotNull(seed["board"] as? Map<*, *>)
        assertTrue((board["columns"] as List<*>).isNotEmpty())
        val layout = assertNotNull(seed["graphLayout"] as? Map<*, *>)
        val nodes = layout["nodes"] as List<*>
        val edges = layout["edges"] as List<*>
        val causal = seed["causalGraph"] as List<*>
        assertEquals(causal.size, nodes.size, "one laid-out node per causal node")
        val ids = nodes.map { (it as Map<*, *>)["id"] }.toSet()
        for (e in edges) {
            val edge = e as Map<*, *>
            assertTrue(edge["from"] in ids && edge["to"] in ids, "edge endpoints must be laid-out nodes")
        }
        assertNotNull(layout["camera"])
    }

    @Test
    fun renderIsDeterministicForTheSameUser() {
        assertEquals(html.length, ForgeApp.renderHtml(userId = "forge-shell-test").length)
    }
}