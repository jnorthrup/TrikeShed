package borg.trikeshed.lcnc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The concentric composition surface is a PURE projection of daemon state:
 * contracts in → panels out. These gates pin the three properties that make
 * it a UI replacement rather than a mock: every contract yields a complete
 * wizard schema, sub-VM legos appear in the module drawer, and a ring panel
 * nests its children (the concentric band shape) with warm outputs attached.
 */
class ConcentricSurfaceTest {

    @Test
    fun wizardRosterIsCompleteOverAllContracts() {
        val roster = ConcentricSurface.wizardRoster(LcncContracts.all())
        assertEquals(LcncContracts.all().size, roster.size, "one wizard per contract — no port without a schema")
        for (wizard in roster) {
            val params = wizard["params"] as? Map<*, *>
            val steps = wizard["steps"] as? List<*>
            assertEquals(params?.size, steps?.size, "every declared param is a wizard step: ${wizard["type"]}")
        }
    }

    @Test
    fun moduleDrawerListsSubVmLegos() {
        val surface = ConcentricSurface.render()
        @Suppress("UNCHECKED_CAST")
        val modules = surface["modules"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val legos = modules["legos"] as List<Map<String, Any?>>
        val types = legos.map { it["type"] }.toSet()
        for (expected in listOf("vm.tika", "vm.corenlp", "vm.camel", "vm.graalce")) {
            assertTrue(expected in types, "module drawer missing $expected")
        }
        // Facet spellings come from the provider capability reports (ids: java, js, …).
        @Suppress("UNCHECKED_CAST")
        val facets = modules["facets"] as List<String>
        assertTrue(facets.all { it.isNotBlank() }, "facet spellings are provider ids, never blank")
    }

    @Test
    fun laneAssemblageIsItsBandsAndRendersAsLanes() {
        // One source of truth: a lane's element/seed/emit ARE its RingBand's —
        // held by reference, never re-spelled. The canvas hydrates `lanes`
        // from render() and authors nothing.
        assertTrue(ConcentricSurface.LANE_ASSEMBLAGE.isNotEmpty(), "the lane assemblage exists")
        for (lane in ConcentricSurface.LANE_ASSEMBLAGE) {
            val band = ConcentricSurface.RING_BANDS.first { it.id == lane.band.id }
            assertTrue(lane.band === band, "lane '${lane.id}' must hold its band by reference, not a copy")
            assertTrue(lane.matchPrefixes.isNotEmpty(), "lane '${lane.id}' claims at least one type prefix")
        }
        val surface = ConcentricSurface.render()
        @Suppress("UNCHECKED_CAST")
        val lanes = surface["lanes"] as List<Map<String, Any?>>
        assertEquals(ConcentricSurface.LANE_ASSEMBLAGE.size, lanes.size, "render() serves every lane")
        for ((i, served) in lanes.withIndex()) {
            val src = ConcentricSurface.LANE_ASSEMBLAGE[i]
            assertEquals(src.id, served["id"])
            assertEquals(src.matchPrefixes, served["match"])
            assertEquals(src.band.id, served["band"])
            assertEquals(src.band.element, served["element"])
            assertEquals(src.band.seedIn, served["seed"])
            assertEquals(src.band.emitOut, served["emit"])
        }
    }

    @Test
    fun ringPanelNestsChildrenAndCarriesWarmOutputs() {
        val confixDoc = """
            {"nodes":[
                {"id":"outer","type":"scope.enter","params":{"x":"1"},
                 "children":[{"id":"inner","type":"kanban.activeSheets"}]},
                {"id":"flat","type":"read.construct"}
            ],
            "wires":[{"from":"inner","to":"flat"}]}
        """.trimIndent()
        val trace = listOf(mapOf("node" to "inner", "outputs" to mapOf<String, Any?>("rows" to 3L)))
        val panel = ConcentricSurface.ringPanel("demo", confixDoc, trace)
        @Suppress("UNCHECKED_CAST")
        val bands = panel["bands"] as List<Map<String, Any?>>
        assertEquals(2, bands.size, "one band per top-level node")

        val outer = bands[0]
        assertEquals(true, outer["ring"], "a node with children renders as a ring band")
        assertEquals(1, outer["children"], "child count carried")

        val flat = bands[1]
        assertEquals(false, flat["ring"], "a childless node renders flat")

        val traced = trace.last { it["node"] == "inner" }["outputs"]
        @Suppress("UNCHECKED_CAST")
        val innerBand = (outer["bands"] as List<Map<String, Any?>>).single()
        assertEquals(traced, innerBand["outputs"], "warm outputs land on the nested band that owns the node")
        assertEquals(null, outer["outputs"], "the ring band itself carries no outputs of its own")
        assertEquals(null, flat["outputs"], "untraced nodes carry no warm base")
    }
}
