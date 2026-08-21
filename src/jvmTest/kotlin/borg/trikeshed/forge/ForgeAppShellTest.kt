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
        for (slot in listOf(ForgeApp.SEED_SLOT, ForgeApp.STYLES_SLOT, ForgeApp.SCRIPT_SLOT, ForgeApp.GALLERY_SLOT, ForgeApp.BUNDLES_SLOT)) {
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
        // JsonSupport.parse reifies an empty "[]" as Array, a non-empty one as List.
        val edges = (layout["edges"] as? List<*>) ?: (layout["edges"] as Array<*>).toList()
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
    fun bundlesAreRelativeDeferredScriptsAfterTheSeed() {
        assertFalse(html.contains("<script src="), "stage jvm ships no bundle tags")
        val withBundle = ForgeApp.renderHtml(userId = "forge-shell-test", bundles = listOf("./js/TrikeShed.js"))
        val tag = "<script src=\"./js/TrikeShed.js\" defer></script>"
        val tagAt = withBundle.indexOf(tag)
        assertTrue(tagAt >= 0, "bundle tag present, relative, deferred")
        assertTrue(withBundle.indexOf("id=\"forge-seed\"") < tagAt, "bundle runs after the seed is in the DOM")
        assertTrue(tagAt < withBundle.indexOf("register('./sw.js')"), "bundle tag precedes SW registration")
    }

    @Test
    fun renderIsDeterministicForTheSameUser() {
        // dashboards.nio.checkedAt / flywheel.updatedAt are launch-time clocks; everything else must be stable.
        fun stable(h: String) = h.replace(Regex("\"(checkedAt|updatedAt)\":\\d+"), "\"$1\":0")
        assertEquals(stable(html), stable(ForgeApp.renderHtml(userId = "forge-shell-test")))
    }
}