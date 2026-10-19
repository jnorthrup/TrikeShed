package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.modelmux.*
import kotlin.test.*

class LcncModelSelectionTest {
    val catalog: Series<EndpointModel> = s_["provider-a" j "shared-model", "provider-b" j "shared-model"]

    fun program(seatType: String = "mux.chat") = LcncProgram("legal-panel", s_[
        LcncNode("clerk", "mux.chat", mapOf("system" to "Retain the record", "models" to "[]")),
        LcncNode("council", "scope", mapOf("limit" to "3"), x = 180.0, y = 25.0,
            width = 600.0, height = 400.0, children = s_[
                LcncNode("evidence", "scope.in", mapOf("name" to "record", "kind" to "text")),
                LcncNode("judge", seatType, mapOf("system" to "Compare the evidence",
                    "maxTokens" to "73", "temperature" to "0.4"), x = 20.0, y = 80.0),
            ]),
    ], s_[LcncWire("evidence", "value", "judge", "prompt?")],
        controls = LcncConfixControls(humanOversight = true, subnet = "legal.review", matingPoints = s_[
            LcncPatchMatingPoint("evidence-judge", "evidence", "value", "judge", "prompt?"),
        ]), view = LcncView(10.0, 20.0, 0.7), seq = 12)

    @Test fun aNestedSeatRetainsTheSelectedEndpointModelOrderAndItsAuthoredLimits() {
        val original = program()
        val selected = LcncModelSelection.select(original, "judge", s_[catalog[1], catalog[0]], catalog)
        val seats = LcncModelSelection.seats(selected)
        assertEquals(2, seats.size)
        assertEquals("clerk", seats[0].id)
        assertEquals("judge", seats[1].id)
        assertEquals(original.nodes[0].params, seats[0].params)
        val options = BrainChatOptions.parse(seats[1].params)
        assertEquals(73, options.maxTokens)
        assertEquals(0.4, options.temperature)
        assertEquals(2, options.models.size)
        assertEquals(catalog[1].pair, options.models[0].pair)
        assertEquals(catalog[0].pair, options.models[1].pair)
        assertEquals("Compare the evidence", seats[1].params["system"])
        assertFalse("models" in original.nodes[1].children[1].params)
        assertSame(original.wires, selected.wires)
        assertSame(original.controls, selected.controls)
        assertEquals(original.view, selected.view)
        assertEquals(original.seq, selected.seq)
        assertEquals(original.nodes[1].params, selected.nodes[1].params)
        assertEquals(original.nodes[1].width, selected.nodes[1].width)
        assertEquals(original.nodes[1].height, selected.nodes[1].height)
        assertEquals("evidence", selected.nodes[1].children[0].id)
        assertEquals(20.0, selected.nodes[1].children[1].x)
    }

    @Test fun modelSelectionSurvivesConfixAlongsideTheConcentricWorkgroupDeclaration() {
        val selected = LcncModelSelection.select(program("council.seat"), "judge", s_[catalog[1]], catalog)
        val restored = LcncProgramConfix.fromJson(selected.name, LcncProgramConfix.toJson(selected))
        assertEquals("legal.review", restored.controls.subnet)
        assertTrue(restored.controls.humanOversight)
        assertEquals(selected.controls.matingPoints[0], restored.controls.matingPoints[0])
        assertEquals(selected.wires[0], restored.wires[0])
        assertEquals(selected.seq, restored.seq)
        assertEquals(selected.view, restored.view)
        assertEquals("council", restored.nodes[1].id)
        assertEquals("judge", restored.nodes[1].children[1].id)
        assertEquals("council.seat", restored.nodes[1].children[1].type)
        val options = BrainChatOptions.parse(restored.nodes[1].children[1].params)
        assertEquals(1, options.models.size)
        assertEquals(catalog[1].pair, options.models[0].pair)
    }

    @Test fun emptyDuplicateUnknownAndAmbiguousSelectionsCannotChangeTheConstruction() {
        val original = program()
        val before = LcncProgramConfix.toJson(original)
        val invalid: Series<Series<EndpointModel>> = s_[emptySeriesOf(), s_[catalog[0], catalog[0]],
            s_["unknown-provider" j "shared-model"], s_["provider-a" j "unknown-model"]]
        for (routes in invalid.view) {
            assertFailsWith<IllegalArgumentException> {
                LcncModelSelection.select(original, "judge", routes, catalog)
            }
        }
        for (nodeId in s_["missing", "evidence", "council"].view) {
            assertFailsWith<IllegalArgumentException> {
                LcncModelSelection.select(original, nodeId, s_[catalog[0]], catalog)
            }
        }
        val duplicate = original.copy(nodes = s_[original.nodes[0], original.nodes[1], LcncNode("judge", "mux.chat")])
        assertFailsWith<IllegalArgumentException> {
            LcncModelSelection.select(duplicate, "judge", s_[catalog[0]], catalog)
        }
        assertEquals(before, LcncProgramConfix.toJson(original))
    }
}
