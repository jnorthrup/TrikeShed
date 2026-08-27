package borg.trikeshed.ccek

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import keymux.KeyMux
import modelmux.ModelMux
import modelmux.acp.AcpAction
import modelmux.autoOf
import modelmux.costOptimizedOf
import modelmux.priorityOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 5 gates, W3.2/W3.3: a Seat selects by policy; the event stream tells
 * the truth about what ran; no seat ever holds a credential path or value.
 */
class SeatSelectionTest {

    private fun muxWith(vararg ids: String): ModelMux {
        val keyMux = KeyMux {}
        return ModelMux(keyMux) {
            for (id in ids) model(id, caps = setOf("chat"))
        }
    }

    private fun names(result: modelmux.RouteResult): List<String> =
        (0 until result.a.size).map { result.a[it].a }

    @Test
    fun prioritySeatPreservesRosterOrder() {
        val mux = muxWith("alpha", "beta", "gamma")
        val seat = Seat(
            laneId = "deliberate",
            role = "judge",
            contextId = "ctx-tribunal",
            selection = priorityOf(),
        )
        val result = seat.select(mux, action = "chat" as AcpAction)
        assertEquals(3, result.a.size)
        assertEquals(listOf("alpha", "beta", "gamma"), names(result),
            "priority keeps catalog order")
        // Trust repair visible on the mux itself:
        assertEquals("capability+priority", mux.strategyName)
    }

    @Test
    fun seatSelectionReportsTheStrategyThatActuallyRan() {
        val mux = muxWith("m1")
        val seat = Seat("lane", "worker", "ctx", selection = costOptimizedOf())
        seat.select(mux, "chat")
        assertTrue(mux.strategyName.startsWith("capability+"),
            "strategyName must name the composed discipline that ran: ${mux.strategyName}")
    }

    @Test
    fun autoSeatStillSelectsFromCapabilityFilteredCandidates() {
        val mux = muxWith("only-one")
        val seat = Seat("running", "researcher", "ctx-research", selection = autoOf())
        val result = seat.select(mux, "chat")
        assertEquals(1, result.a.size)
        assertEquals("only-one", result.a[0].a)
    }

    @Test
    fun quorumWeightDefaultsToOneSeatOneVote() {
        val judge = Seat("deliberate", "judge", "c1", selection = priorityOf())
        val lay = Seat("deliberate", "observer", "c2",
            selection = priorityOf(), quorumWeight = 0.25)
        assertEquals(1.0, judge.quorumWeight)
        assertEquals(0.25, lay.quorumWeight)
    }
}
