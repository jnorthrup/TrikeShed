package borg.trikeshed.lcnc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * /panels gates: the swimlane page maps every card to its owning CCEK assemblage
 * rendered as NESTED CONCENTRIC RING BANDS. The page is a pure projection —
 * these tests pin the mapping and the nesting, not the pixels.
 */
class PanelsHtmlTest {

    private fun board(vararg cards: Triple<String, String, String>): Map<String, Any?> = mapOf(
        "sequence" to 7L,
        "columns" to emptyList<Any?>(),
        "items" to cards.map { (id, lane, title) ->
            mapOf("id" to id, "title" to title, "status" to lane, "priority" to 1, "revision" to 3, "order" to 0)
        },
    )

    private fun modules(): Map<String, Any?> = ConcentricSurface.render()

    @Test
    fun everyColumnRendersItsFullConcentricStack() {
        val html = ConcentricSurface.panelsHtml(board(), modules())
        for ((wire, boxes) in ConcentricSurface.COLUMN_RINGS) {
            assertTrue(html.contains("${wire.uppercase()}</div>"), "column $wire rendered")
            for (r in boxes) {
                assertTrue(html.contains(r.element), "column $wire names box element: ${r.element}")
                assertTrue(html.contains(r.state), "column $wire declares box state: ${r.state}")
            }
        }
        // Depth tabs D0…Dn label containment; the deepest stack is running's.
        val maxDepth = ConcentricSurface.COLUMN_RINGS.values.maxOf { it.size } - 1
        assertTrue(html.contains("D$maxDepth ·"), "depth tabs label the nesting")
    }

    @Test
    fun bandsNestOutermostFirst() {
        // todo = [daemon-root, frame-r1-store]: the frame box's markup must appear
        // AFTER the daemon box's opening and BEFORE its closing div.
        val html = ConcentricSurface.panelsHtml(board(Triple("t1", "todo", "ingest")), modules())
        val daemonOpen = html.indexOf("SupervisorJob · LitebikeListenerElement")
        val frameOpen = html.indexOf("LcncScopeFrame r1 · BoardStoreElement intake")
        assertTrue(daemonOpen in 0 until frameOpen, "outer box opens before inner box")
        val cardIdx = html.indexOf(">ingest</b>")
        assertTrue(cardIdx > frameOpen, "card renders inside the box that owns it")
    }

    @Test
    fun cardBandRulePlacesVmAndBotCardsInTheDeepRings() {
        assertEquals("sub-vm", ConcentricSurface.cardBand("running", "vm.tika extract"))
        assertEquals("bot-seat", ConcentricSurface.cardBand("running", "read.construct chapter"))
        assertEquals("frame-r2-fanout", ConcentricSurface.cardBand("running", "plain agent run"))
        assertEquals("frame-r1-store", ConcentricSurface.cardBand("todo", "vm.anything"), "rule only bends running")
    }

    @Test
    fun downstreamContextsBranchRightInsteadOfNesting() {
        val html = ConcentricSurface.panelsHtml(board(), modules())
        // sub-vm and bot-seat are downstream: they leave the contained stack on a ─▶
        // and sit in a violet-margined dbox — growth lands in margin, not the spine.
        val branchExits = html.split("─▶").size - 1
        assertTrue(branchExits >= 2, "downstream boxes exit on ─▶ (found $branchExits exits)")
        assertTrue(html.contains("box dbox"), "downstream boxes render in the margin dbox shape")
    }

    @Test
    fun fractalIOEveryBoxSeedsFromUpstreamAndEmitsDownstream() {
        val html = ConcentricSurface.panelsHtml(board(), modules())
        // Column scale: the spine is a seed→emit chain (each column seeds from the
        // previous column's emit — ready seeds from todo's emit, etc.).
        for ((wire, seedEmit) in ConcentricSurface.COLUMN_SEEDS) {
            assertTrue(html.contains("seed: ${seedEmit.first}"), "column $wire names its upstream seed")
            assertTrue(html.contains("emit: ${seedEmit.second}"), "column $wire names its downstream emit")
        }
        // Chain continuity: todo's emit IS ready's seed, at the column scale.
        assertEquals(ConcentricSurface.COLUMN_SEEDS["todo"]!!.second,
            ConcentricSurface.COLUMN_SEEDS["ready"]!!.first, "spine emit feeds the next seed")
        // Box scale: keys resolve state from the upstream coroutine seed.
        assertTrue(html.contains("seed: r1 warm base (frame outputs)"), "r2 fan-out seeds from the r1 warm base")
        assertTrue(html.contains("seed: QuotaLegion admission (lease)"), "bot seat seeds from the lease")
        // Card scale: the expanded chain carries its box's seed line.
        val html2 = ConcentricSurface.panelsHtml(board(Triple("v1", "running", "vm.tika extract")), modules())
        assertTrue(html2.contains("seed: VmSpec (params · world seed)"), "vm card chain names its seed")
    }

    @Test
    fun vmCardSitsInsideSubVmBandWithFullChain() {
        val html = ConcentricSurface.panelsHtml(
            board(Triple("v1", "running", "vm.tika extract")),
            modules(),
        )
        val subVmBand = html.indexOf("VmSupervisor.current · VmHandle.eval")
        val cardIdx = html.indexOf(">vm.tika extract</b>")
        assertTrue(subVmBand in 0 until cardIdx, "vm card sits inside the sub-vm box")
        // The expanded chain spells the whole outer→inner assemblage.
        assertTrue(html.contains("assemblage: "), "card chain renders the full stack")
        assertTrue(html.contains("ArticulatedNode fan-out"), "chain includes the fan-out box")
        assertTrue(html.contains("Seat(lane·role·policy)"), "chain includes the bot seat box")
    }

    @Test
    fun moduleDrawerRenderSubVmLegosWithRunFeint() {
        val html = ConcentricSurface.panelsHtml(board(), modules())
        assertTrue(html.contains("vm.tika"), "tika lego in the drawer")
        assertTrue(html.contains("/api/lcnc/run"), "drawer click lowers to the generic runner dispatch")
    }

    @Test
    fun titlesAreEscaped() {
        val hostile = mapOf<String, Any?>(
            "sequence" to 1L,
            "columns" to emptyList<Any?>(),
            "items" to listOf(mapOf(
                "id" to "x", "title" to "<script>alert(1)</script>", "status" to "todo",
                "priority" to 0, "revision" to 1, "order" to 0,
            )),
        )
        val html = ConcentricSurface.panelsHtml(hostile, modules())
        // The card TITLE must be escaped; the page's own run-feint <script> block is
        // legitimate, so assert on the escaped card body only.
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), "card titles are escaped")
    }
}
