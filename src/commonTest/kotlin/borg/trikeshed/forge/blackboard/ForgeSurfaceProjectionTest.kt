package borg.trikeshed.forge.blackboard

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.dag.ReteAgent
import borg.trikeshed.graph.CausalGraphNode
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.graph.causalGraphNode
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import modelmux.ModelSelectionEvent
import modelmux.QuotaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M3 — the one surface contract: a rete Fire and a modelmux QuotaSnapshot both
 * project onto valid [ForgeBlackboardView]s through [ForgeSurfaceProjection].
 *
 * The load-bearing invariant is the view's own init constraint:
 * `layout3D.map { sectionId }.toSet() == sections.toSet()`. A view that
 * violates it cannot be constructed at all, so every successful projection is
 * already proof — the assertions below make that explicit and pin geometry,
 * anchoring and idempotence.
 */
class ForgeSurfaceProjectionTest {

    private val fixedNow = 1_700_000_000_000L

    private fun fire(nodeId: String, rule: String = "node-planning") = ReteAgent.Fire(
        ruleName = rule,
        nodeId = nodeId,
        causalKey = "op\u001Fv1\u001Fseed\u001Fboard-a",
        payload = "planned",
        agentId = "node-planning-agent",
    )

    private fun node(nodeId: String): CausalGraphNode = causalGraphNode(
        nodeId = nodeId,
        opId = "op",
        opVersion = "v1",
        parentNodeIds = emptyList(),
        inputFingerprint = "seed-$nodeId",
        blackboard = blackboardContext(id = "board-a"),
        causalClock = 0L,
        topoOrdinal = 0,
        outputHash = null,
    )

    private fun assertViewInvariant(view: ForgeBlackboardView) {
        assertEquals(
            view.sections.toSet(),
            view.layout3D.map { it.sectionId }.toSet(),
            "layout3D section ids must equal sections",
        )
        assertEquals(
            view.layout3D.size,
            view.layout3D.map { it.sectionId }.toSet().size,
            "layout3D must not carry duplicate section ids",
        )
    }

    // ── (a) rete Fire → board ────────────────────────────────────────────────

    @Test
    fun fireProjectsOntoBoardSection() {
        val (view, surface, index) = ReteFireSurfaceProjection.project(
            domain = listOf(fire("n1"), fire("n2")),
            now = fixedNow,
        )

        assertViewInvariant(view)
        assertEquals("board", surface.anchorSectionId)
        assertEquals(2, surface.envelopes.size)
        assertEquals(2, index.size)

        val first = surface.envelopes[0]
        assertEquals("n1", first.payload.nodeId)
        assertEquals("planned", first.payload.payload)
        assertEquals(fixedNow, first.updatedAt)
        assertEquals(FORGE_SURFACE_TTL_MS, first.ttlMs)
        assertSame(first, index[first.sectionId])

        // Every tile carries its own section id + geometry, and the board grew by exactly 2.
        assertEquals(ForgeBlackboardView.DEFAULT.sections.size + 2, view.sections.size)
        surface.envelopes.forEach { e ->
            assertTrue(e.sectionId.startsWith("board-fire-"), e.sectionId)
            assertNotNull(ForgeBlackboardView.sectionPlacement(view, e.sectionId))
            assertTrue(e.width > 0.0 && e.height > 0.0)
            assertTrue(e.elevation > 0.0)
        }

        // Tiles hang beneath the anchor quadrant.
        val anchor = ForgeBlackboardView.sectionPlacement(ForgeBlackboardView.DEFAULT, "board")!!
        assertTrue(surface.envelopes.all { it.centerY > anchor.centerY + anchor.height / 2.0 })

        assertFalse(surface.isStaleAt(fixedNow + 1_000L))
        assertTrue(surface.isStaleAt(fixedNow + FORGE_SURFACE_TTL_MS + 1L))
    }

    @Test
    fun identicalFiresCollapseToOneTile() {
        val (view, surface, index) = ReteFireSurfaceProjection.project(
            domain = List(9) { fire("n1") },   // nine identical fires
            now = fixedNow,
        )
        assertViewInvariant(view)
        assertEquals(1, surface.envelopes.size, "identical fires name the same tile")
        assertEquals(1, index.size)
        assertEquals(ForgeBlackboardView.DEFAULT.sections.size + 1, view.sections.size)
    }

    @Test
    fun distinctFiresFillTheGridRowByRow() {
        val (view, surface, _) = ReteFireSurfaceProjection.project(
            domain = (1..9).map { fire("n$it") },
            now = fixedNow,
        )
        assertViewInvariant(view)
        assertEquals(9, surface.envelopes.size)
        assertEquals(9, surface.envelopes.map { it.sectionId }.toSet().size)
        // 4-column grid ⇒ 3 rows, 4 distinct columns.
        assertEquals(3, surface.envelopes.map { it.centerY }.toSet().size)
        assertEquals(4, surface.envelopes.map { it.centerX }.toSet().size)
    }

    @Test
    fun boardStripClearsTheDefaultQuadrants() {
        val (_, surface, _) = ReteFireSurfaceProjection.project(
            domain = (1..32).map { fire("n$it") },
            now = fixedNow,
        )
        // No tile may reach up into the page/board/gallery/graph band.
        val defaultQuadrants = ForgeBlackboardView.DEFAULT.layout3D.filter { it.sectionId in listOf("page", "board", "gallery", "graph") }
        val quadrantFloor = defaultQuadrants.maxOf { it.centerY + it.height / 2.0 }
        assertTrue(
            surface.envelopes.all { it.centerY - it.height / 2.0 > quadrantFloor },
            "a full strip must stay below y=$quadrantFloor",
        )
    }

    @Test
    fun tapCollectsAgentFiresAndProjectsThem() = runTest {
        val index = CausalGraphNodeIndex()
        val tap = ReteFireBoardTap()
        val agent = index.tapFiresToBoard(scope = backgroundScope, tap = tap)

        index.addOrGet(node("n1"))
        index.addOrGet(node("n2"))

        // The agent's rule loop is its own coroutine — await it rather than poll.
        assertEquals(2, (withTimeoutOrNull(2_000) { tap.awaitFires(2) } ?: emptyList()).size)

        val (view, surface, byId) = tap.project(now = fixedNow)
        assertViewInvariant(view)
        assertEquals(2, surface.envelopes.size)
        assertEquals(setOf("n1", "n2"), surface.envelopes.map { it.payload.nodeId }.toSet())
        assertTrue(surface.envelopes.all { it.payload.agentId == "forge-board-fire-tap" })
        assertEquals(surface.envelopes.map { it.sectionId }.toSet(), byId.keys)

        ReteAgent.stop(agent)
        agent.job.join()
    }

    @Test
    fun awaitFiresCountsFreshArrivalsNotWindowSize() = runTest {
        val index = CausalGraphNodeIndex()
        val tap = ReteFireBoardTap()
        val agent = index.tapFiresToBoard(scope = backgroundScope, tap = tap)

        index.addOrGet(node("n1"))
        index.addOrGet(node("n2"))
        assertEquals(2, (withTimeoutOrNull(2_000) { tap.awaitFires(2) } ?: emptyList()).size)

        // The window is never emptied, so a second await must count *new* arrivals
        // rather than being satisfied instantly by the two fires already held.
        index.addOrGet(node("n3"))
        index.addOrGet(node("n4"))
        val second = withTimeoutOrNull(2_000) { tap.awaitFires(2) } ?: emptyList()
        assertEquals(listOf("n1", "n2", "n3", "n4"), second.map { it.nodeId })

        ReteAgent.stop(agent)
        agent.job.join()
    }

    @Test
    fun tapRejectsAnIndexThatAlreadyHasAnAgent() = runTest {
        val index = CausalGraphNodeIndex()
        val agent = index.tapFiresToBoard(scope = backgroundScope, tap = ReteFireBoardTap())
        assertFailsWith<IllegalArgumentException> {
            index.tapFiresToBoard(scope = backgroundScope, tap = ReteFireBoardTap())
        }
        ReteAgent.stop(agent)
        agent.job.join()
    }

    // ── (b) modelmux telemetry → gallery ─────────────────────────────────────

    @Test
    fun quotaSnapshotProjectsOntoGallerySection() {
        val snapshot = QuotaSnapshot(
            provider = "anthropic",
            windowStart = 1_699_999_000_000L,
            quotaRemaining = 42.5,
            spent = 7.5,
        )
        val (view, surface, index) = projectModelMuxTelemetry(
            quotas = listOf(snapshot),
            selections = emptyList(),
            now = fixedNow,
        )

        assertViewInvariant(view)
        assertEquals("gallery", surface.anchorSectionId)
        assertEquals(1, surface.envelopes.size)

        val e = surface.envelopes[0]
        val payload = e.payload
        assertIs<ModelMuxTelemetry.Quota>(payload)
        assertEquals(snapshot, payload.snapshot)
        assertEquals("gallery-quota-anthropic-1699999000000", e.sectionId)
        assertEquals(fixedNow, e.updatedAt)
        assertNotNull(ForgeBlackboardView.sectionPlacement(view, e.sectionId))
        assertSame(e, index[e.sectionId])

        val anchor = ForgeBlackboardView.sectionPlacement(ForgeBlackboardView.DEFAULT, "gallery")!!
        assertTrue(e.centerY > anchor.centerY + anchor.height / 2.0)
    }

    @Test
    fun quotaAndSelectionShareTheGalleryStrip() {
        val (view, surface, _) = projectModelMuxTelemetry(
            quotas = listOf(QuotaSnapshot("anthropic", 1L, 10.0, 1.0)),
            selections = listOf(
                ModelSelectionEvent.ModelSelected(
                    provider = "anthropic",
                    model = "opus",
                    strategy = "quota-first",
                    requestId = "req/42",
                    at = fixedNow,
                ),
            ),
            now = fixedNow,
        )

        assertViewInvariant(view)
        assertEquals(2, surface.envelopes.size)
        assertIs<ModelMuxTelemetry.Quota>(surface.envelopes[0].payload)
        assertIs<ModelMuxTelemetry.Selection>(surface.envelopes[1].payload)
        // '/' is not section-id safe and must be squashed.
        assertEquals("gallery-model-anthropic-req-42", surface.envelopes[1].sectionId)
    }

    // ── contract-level invariants ────────────────────────────────────────────

    @Test
    fun ringsCompose_boardThenGalleryOnOneView() {
        val (boardView, boardSurface, _) = ReteFireSurfaceProjection.project(
            domain = listOf(fire("n1")),
            now = fixedNow,
        )
        val (bothView, gallerySurface, _) = projectModelMuxTelemetry(
            quotas = listOf(QuotaSnapshot("gemini", 5L, 1.0, 0.0)),
            selections = emptyList(),
            base = boardView,
            now = fixedNow,
        )

        assertViewInvariant(bothView)
        assertEquals(ForgeBlackboardView.DEFAULT.sections.size + 2, bothView.sections.size)
        assertNotNull(ForgeBlackboardView.sectionPlacement(bothView, boardSurface.envelopes[0].sectionId))
        assertNotNull(ForgeBlackboardView.sectionPlacement(bothView, gallerySurface.envelopes[0].sectionId))
    }

    @Test
    fun emptyDomainLeavesTheDefaultViewUntouched() {
        val (view, surface, index) = ReteFireSurfaceProjection.project(emptyList(), now = fixedNow)
        assertViewInvariant(view)
        assertEquals(ForgeBlackboardView.DEFAULT.sections, view.sections)
        assertEquals(ForgeBlackboardView.DEFAULT.layout3D, view.layout3D)
        assertTrue(surface.envelopes.isEmpty())
        assertTrue(index.isEmpty())
    }

    @Test
    fun reprojectingKeepsEveryTileWhereTheViewPutIt() {
        val fires = listOf(fire("n1"), fire("n2"), fire("n3"))
        val (once, first, _) = ReteFireSurfaceProjection.project(fires, now = fixedNow)

        // Same fires, different order, onto the view they already live on.
        val (twice, second, _) =
            ReteFireSurfaceProjection.project(fires.reversed(), base = once, now = fixedNow + 5L)

        assertViewInvariant(twice)
        assertEquals(once.sections, twice.sections, "no new sections for tiles already on the board")
        assertEquals(once.layout3D, twice.layout3D)

        val again = second.envelopes.associateBy { it.sectionId }
        first.envelopes.forEach { e ->
            val e2 = again.getValue(e.sectionId)
            // Placement is the view's, so surface and view can never disagree…
            assertEquals(e.centerX, e2.centerX)
            assertEquals(e.centerY, e2.centerY)
            assertEquals(e.width, e2.width)
            assertEquals(e.height, e2.height)
            assertEquals(e.elevation, e2.elevation)
            // …while the envelope itself is freshly stamped.
            assertEquals(fixedNow + 5L, e2.updatedAt)
        }
    }

    @Test
    fun withSurfaceIsIdempotent() {
        val (once, surface, _) = ReteFireSurfaceProjection.project(listOf(fire("n1")), now = fixedNow)
        val twice = once.withSurface(surface.envelopes)
        assertViewInvariant(twice)
        assertEquals(once.sections, twice.sections)
        assertEquals(once.layout3D, twice.layout3D)
    }

    @Test
    fun projectRejectsAnAnchorMissingFromTheBaseView() {
        val narrow = ForgeBlackboardView(
            surface = "forge.blackboard.narrow",
            sections = listOf("page"),
            defaultCamera = ForgeBlackboardCamera(),
            cornerButtons = emptyList(),
            layout3D = listOf(ForgeBlackboardSection3D("page", 0.0, 0.0, 100.0, 100.0, 1.0)),
        )
        assertFailsWith<IllegalArgumentException> {
            ReteFireSurfaceProjection.project(listOf(fire("n1")), base = narrow, now = fixedNow)
        }
    }

    @Test
    fun sectionHashIsDeterministicAndPartAware() {
        assertEquals(forgeSectionHash("a", "b"), forgeSectionHash("a", "b"))
        assertEquals(8, forgeSectionHash("a").length)
        assertTrue(forgeSectionHash("a", "b") != forgeSectionHash("ab"), "part boundaries must matter")
        assertTrue(forgeSectionHash("") .all { it in "0123456789abcdef" })
    }

    @Test
    fun sectionTokenSquashesSeparators() {
        assertEquals("op-v1-seed-board-a", "op\u001Fv1\u001Fseed\u001Fboard-a".forgeSectionToken())
        assertEquals("x", "///".forgeSectionToken())
        assertEquals("a_b-c", "a_b/c".forgeSectionToken())
    }
}
