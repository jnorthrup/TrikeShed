package narchy.spacegraph.layout

// Upstream test/layout/scope.test.ts at 125b369: all 18 cases ported, expected values copied from upstream.
// toBeCloseTo (jest default precision 2, i.e. |diff| < 0.005) is written as an explicit tolerance of 0.005.

import narchy.spacegraph.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopeTest {
    private val close = 0.005

    private fun assertClose(expected: Double, actual: Double) =
        assertTrue(kotlin.math.abs(expected - actual) < close, "expected $expected, got $actual")

    private fun codeOf(run: () -> Unit): ScopeErrorCode = assertFailsWith<ScopeError> { run() }.code

    private val nested = listOf(
        ScopeSpec(
            id = "root",
            strategy = ScopeStrategy.GRID,
            nodes = listOf(
                ScopeNode(id = "a", size = 100.0 to 40.0),
                ScopeNode(id = "portal", scope = "inner"),
            ),
            edges = listOf(ScopeEdge(id = "bridge", source = "a", target = "inner/x")),
        ),
        ScopeSpec(
            id = "inner",
            parent = "root",
            strategy = ScopeStrategy.GRID,
            nodes = listOf(
                ScopeNode(id = "x", size = 80.0 to 40.0),
                ScopeNode(id = "y", size = 80.0 to 40.0),
            ),
            edges = listOf(ScopeEdge(id = "inner-edge", source = "x", target = "y")),
        ),
    )

    // 'qualifies identity and indexes every node and scope'
    @Test
    fun qualifiesIdentityAndIndexesEveryNodeAndScope() {
        val tree = buildScopeTree(nested)

        assertEquals(listOf("root", "root/inner"), tree.byPath.keys.toList())
        assertEquals("root", tree.nodePaths["root/a"])
        assertEquals("root/inner", tree.nodePaths["root/inner/x"])
        assertEquals("root/inner", scopeOf(tree, "root/inner/y"))
        assertEquals("root/inner", scopeOf(tree, "root/inner"))
    }

    // 'resolves local, child-scope and absolute references'
    @Test
    fun resolvesLocalChildScopeAndAbsoluteReferences() {
        val tree = buildScopeTree(nested)

        assertEquals("root/a", resolveScopeRef(tree, "root", "a"))
        assertEquals("root/inner", resolveScopeRef(tree, "root", "inner"))
        assertEquals("root/inner/x", resolveScopeRef(tree, "root", "root/inner/x"))
        assertEquals(
            ScopeErrorCode.UNKNOWN_ENDPOINT,
            codeOf { resolveScopeRef(tree, "root", "missing") },
        )
    }

    // 'rejects cycles, duplicates, missing parents and unknown portals'
    @Test
    fun rejectsCyclesDuplicatesMissingParentsAndUnknownPortals() {
        assertEquals(
            ScopeErrorCode.CYCLE,
            codeOf {
                buildScopeTree(
                    listOf(
                        ScopeSpec(id = "a", parent = "b", nodes = emptyList()),
                        ScopeSpec(id = "b", parent = "a", nodes = emptyList()),
                    ),
                )
            },
        )
        assertEquals(
            ScopeErrorCode.DUPLICATE_SCOPE,
            codeOf {
                buildScopeTree(listOf(ScopeSpec(id = "a", nodes = emptyList()), ScopeSpec(id = "a", nodes = emptyList())))
            },
        )
        assertEquals(
            ScopeErrorCode.UNKNOWN_PARENT,
            codeOf { buildScopeTree(listOf(ScopeSpec(id = "a", parent = "none", nodes = emptyList()))) },
        )
        assertEquals(
            ScopeErrorCode.UNKNOWN_PORTAL,
            codeOf {
                buildScopeTree(listOf(ScopeSpec(id = "a", nodes = listOf(ScopeNode(id = "p", scope = "missing")))))
            },
        )
        assertEquals(
            ScopeErrorCode.DUPLICATE_NODE,
            codeOf {
                buildScopeTree(
                    listOf(ScopeSpec(id = "a", nodes = listOf(ScopeNode(id = "x"), ScopeNode(id = "x")))),
                )
            },
        )
    }

    // 'rejects ids containing the path separator'
    @Test
    fun rejectsIdsContainingThePathSeparator() {
        assertEquals(
            ScopeErrorCode.INVALID_ID,
            codeOf { buildScopeTree(listOf(ScopeSpec(id = "a/b", nodes = emptyList()))) },
        )
        assertEquals(
            ScopeErrorCode.INVALID_ID,
            codeOf { buildScopeTree(listOf(ScopeSpec(id = "a", nodes = listOf(ScopeNode(id = "x/y"))))) },
        )
    }

    // 'composes a child frame from its parent portal'
    @Test
    fun composesAChildFrameFromItsParentPortal() {
        val layout = layoutScopes(buildScopeTree(nested))
        val portal = layout.positions["root/portal"]!!
        val frame = layout.frames["root/inner"]!!

        assertEquals(portal, frame.origin)
        assertEquals("root", frame.parent)
        assertEquals(1, frame.depth)

        val local = layout.locals["root/inner/x"]!!
        val world = layout.positions["root/inner/x"]!!
        assertClose(frame.origin.x + frame.scale * local.x, world.x)
        assertClose(frame.origin.y + frame.scale * local.y, world.y)
    }

    // 'multiplies scale down a scope chain'
    @Test
    fun multipliesScaleDownAScopeChain() {
        val tree = buildScopeTree(
            listOf(
                ScopeSpec(id = "a", nodes = listOf(ScopeNode(id = "p", scope = "b"))),
                ScopeSpec(id = "b", parent = "a", scale = 0.5, nodes = listOf(ScopeNode(id = "q", scope = "c"))),
                ScopeSpec(id = "c", parent = "b", scale = 0.5, nodes = listOf(ScopeNode(id = "leaf"))),
            ),
        )
        val layout = layoutScopes(tree)

        assertEquals(1.0, layout.frames["a"]!!.scale)
        assertClose(0.5, layout.frames["a/b"]!!.scale)
        assertClose(0.25, layout.frames["a/b/c"]!!.scale)
    }

    // 'applies a scope offset in parent space'
    @Test
    fun appliesAScopeOffsetInParentSpace() {
        val tree = buildScopeTree(
            listOf(
                ScopeSpec(id = "root", nodes = listOf(ScopeNode(id = "portal", scope = "child"))),
                ScopeSpec(
                    id = "child",
                    parent = "root",
                    offset = Vec3(50.0, -20.0, 0.0),
                    nodes = listOf(ScopeNode(id = "x")),
                ),
            ),
        )
        val layout = layoutScopes(tree)
        val portal = layout.positions["root/portal"]!!
        val frame = layout.frames["root/child"]!!

        assertClose(portal.x + 50, frame.origin.x)
        assertClose(portal.y - 20, frame.origin.y)
    }

    // 'projects cross-scope edges with real world endpoints'
    @Test
    fun projectsCrossScopeEdgesWithRealWorldEndpoints() {
        val layout = layoutScopes(buildScopeTree(nested))
        val bridge = layout.edges.first { it.id == "bridge" }

        assertTrue(bridge.interScope)
        assertEquals("root/a", bridge.source)
        assertEquals("root/inner/x", bridge.target)
        assertEquals(layout.positions["root/a"], bridge.from)
        assertEquals(layout.positions["root/inner/x"], bridge.to)
    }

    // 'keeps a portal edge inside the scope that declares it'
    @Test
    fun keepsAPortalEdgeInsideTheScopeThatDeclaresIt() {
        val tree = buildScopeTree(
            listOf(
                ScopeSpec(
                    id = "root",
                    nodes = listOf(ScopeNode(id = "a"), ScopeNode(id = "portal", scope = "child")),
                    edges = listOf(ScopeEdge(id = "to-portal", source = "a", target = "portal")),
                ),
                ScopeSpec(id = "child", parent = "root", nodes = listOf(ScopeNode(id = "x"))),
            ),
        )
        val layout = layoutScopes(tree)
        val edge = layout.edges.first { it.id == "to-portal" }

        assertFalse(edge.interScope)
        assertEquals(layout.positions["root/portal"], edge.to)
        assertEquals(edge.to, layout.frames["root/child"]!!.origin)
    }

    // 'lets each scope pick its own solver and stays deterministic'
    @Test
    fun letsEachScopePickItsOwnSolverAndStaysDeterministic() {
        val tree = buildScopeTree(
            listOf(
                ScopeSpec(id = "root", strategy = ScopeStrategy.GRID, nodes = listOf(ScopeNode(id = "a"), ScopeNode(id = "b"))),
                ScopeSpec(id = "other", strategy = ScopeStrategy.FORCE, nodes = listOf(ScopeNode(id = "c"), ScopeNode(id = "d"))),
            ),
        )
        val first = layoutScopes(tree, ScopeLayoutOptions(seed = 7))
        val second = layoutScopes(tree, ScopeLayoutOptions(seed = 7))

        assertEquals(listOf("root/a", "root/b", "other/c", "other/d"), first.positions.keys.toList())
        assertEquals(first.positions.toList(), second.positions.toList())
        for (position in first.positions.values) {
            assertTrue(position.x.isFinite())
            assertTrue(position.y.isFinite())
        }
    }

    // 'holds pinned nodes at their previous local position'
    @Test
    fun holdsPinnedNodesAtTheirPreviousLocalPosition() {
        val tree = buildScopeTree(listOf(ScopeSpec(id = "root", nodes = listOf(ScopeNode(id = "a"), ScopeNode(id = "b")))))
        val held = Vec3(999.0, -123.0, 0.0)
        val layout = layoutScopes(
            tree,
            ScopeLayoutOptions(
                previous = mapOf("root" to mapOf("a" to held)),
                pinned = setOf("root/a"),
            ),
        )

        assertEquals(held, layout.positions["root/a"])
        assertEquals(held, layout.locals["root/a"])
    }

    // 'reports only new ids as added between layouts'
    @Test
    fun reportsOnlyNewIdsAsAddedBetweenLayouts() {
        val before = layoutScopes(buildScopeTree(listOf(ScopeSpec(id = "root", nodes = listOf(ScopeNode(id = "a"))))))
        val after = layoutScopes(
            buildScopeTree(listOf(ScopeSpec(id = "root", nodes = listOf(ScopeNode(id = "a"), ScopeNode(id = "b"))))),
        )
        val change = scopeChanges(before, after)

        assertEquals(listOf("root/b"), change.added.keys.toList())
        assertEquals(emptyList(), change.removed)
    }

    // 'bounds every world position'
    @Test
    fun boundsEveryWorldPosition() {
        val layout = layoutScopes(buildScopeTree(nested))
        for (position in layout.positions.values) {
            assertTrue(position.x >= layout.bounds.min.x - 1e-6)
            assertTrue(position.x <= layout.bounds.max.x + 1e-6)
            assertTrue(position.y >= layout.bounds.min.y - 1e-6)
            assertTrue(position.y <= layout.bounds.max.y + 1e-6)
        }
    }

    // 'walks ancestors and descendants in tree order'
    @Test
    fun walksAncestorsAndDescendantsInTreeOrder() {
        val tree = buildScopeTree(
            listOf(
                ScopeSpec(id = "root", nodes = emptyList()),
                ScopeSpec(id = "a", parent = "root", nodes = emptyList()),
                ScopeSpec(id = "b", parent = "a", nodes = listOf(ScopeNode(id = "leaf"))),
            ),
        )

        assertEquals(listOf("root/a/b", "root/a", "root"), scopeAncestors(tree, "root/a/b/leaf").map { it.path })
        assertEquals(listOf("root", "root/a", "root/a/b"), scopeDescendants(tree, "root").map { it.path })
    }

    // 'reports new ids across updates'
    @Test
    fun reportsNewIdsAcrossUpdates() {
        val host = ScopeHost(ScopeLayoutOptions(seed = 3))
        val first = host.update(listOf(ScopeSpec(id = "root", nodes = listOf(ScopeNode(id = "a")))))
        val second = host.update(
            listOf(ScopeSpec(id = "root", nodes = listOf(ScopeNode(id = "a"), ScopeNode(id = "b")))),
        )

        assertEquals(listOf("root/a"), first.change.added.keys.toList())
        assertEquals(listOf("root/b"), second.change.added.keys.toList())
        assertTrue(second.layout.positions.containsKey("root/a"))
    }

    // 'holds pinned ids at their previous local position'
    @Test
    fun holdsPinnedIdsAtTheirPreviousLocalPosition() {
        val host = ScopeHost(ScopeLayoutOptions(seed = 3, pinned = setOf("root/a")))
        val first = host.update(listOf(ScopeSpec(id = "root", nodes = listOf(ScopeNode(id = "a")))))
        val held = first.layout.locals["root/a"]!!

        val next = host.update(
            listOf(ScopeSpec(id = "root", nodes = listOf(ScopeNode(id = "a"), ScopeNode(id = "b")))),
        )

        assertEquals(held, next.layout.locals["root/a"])
    }

    private val deep = listOf(
        ScopeSpec(id = "root", nodes = listOf(ScopeNode(id = "p", scope = "a"))),
        ScopeSpec(id = "a", parent = "root", nodes = listOf(ScopeNode(id = "q", scope = "b"))),
        ScopeSpec(id = "b", parent = "a", nodes = listOf(ScopeNode(id = "leaf"))),
    )

    // 'collapses scopes below the zoom band'
    @Test
    fun collapsesScopesBelowTheZoomBand() {
        val tree = buildScopeTree(deep)
        val active = activeScopePaths(tree, ScopeSignals(band = 0))

        assertEquals(listOf("root", "root/a"), active.sorted())
        val layout = layoutScopes(tree, ScopeLayoutOptions(active = active))
        assertTrue(layout.frames["root/a"]!!.active)
        assertFalse(layout.frames["root/a/b"]!!.active)
    }

    // 'keeps the ancestors of the focus active at any depth'
    @Test
    fun keepsTheAncestorsOfTheFocusActiveAtAnyDepth() {
        val tree = buildScopeTree(deep)
        val active = activeScopePaths(tree, ScopeSignals(band = 0, focus = "root/a/b/leaf"))

        assertTrue("root/a/b" in active)
        assertTrue("root" in active)
    }
}
