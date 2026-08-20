package modelmux

import borg.trikeshed.lib.*
import keymux.KeyMux
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoutingStrategyTest {

    // Deliberately unordered on every axis so no two strategies can agree by accident.
    // Names are opaque labels: no strategy may read them, and no assertion below asserts
    // on a provider *because* of its identity — only on the neutral facts' ordering.
    private val alpha = ModelCatalogEntry("alpha", "a-1", freeTier = false, quotaRemaining = 900, latencyEstimateMs = 100)
    private val bravo = ModelCatalogEntry("bravo", "b-1", freeTier = true, quotaRemaining = 50, latencyEstimateMs = 4000)
    private val charlie = ModelCatalogEntry("charlie", "c-1", freeTier = true, quotaRemaining = 500, latencyEstimateMs = 2000)
    private val delta = ModelCatalogEntry("delta", "d-1", freeTier = true, quotaRemaining = 500, latencyEstimateMs = 250)

    private val candidates: Series<ModelCatalogEntry> = listOf(alpha, bravo, charlie, delta).toSeries()

    /** Project the ranking to its labels for assertion — the ranking itself never leaves Series form. */
    private fun Series<ModelCatalogEntry>.providers(): List<String> =
        (this α { it.provider }).toList()

    @Test
    fun priority_preservesStableInputOrder() {
        val ranked = priorityOf<Unit>().invoke(candidates, Unit)
        assertEquals(listOf("alpha", "bravo", "charlie", "delta"), ranked.providers())
        assertEquals("priority", priorityOf<Unit>().strategyName)
    }

    @Test
    fun weighted_ranksByQuotaOverLatencyPenalty() {
        // score = quota / (1 + latencyMs/1000):
        //   alpha   900 / 1.10 = 818.2
        //   delta   500 / 1.25 = 400.0
        //   charlie 500 / 3.00 = 166.7
        //   bravo    50 / 5.00 =  10.0
        val ranked = weightedOf<Unit>().invoke(candidates, Unit)
        assertEquals(listOf("alpha", "delta", "charlie", "bravo"), ranked.providers())
    }

    @Test
    fun costOptimized_ranksFreeTierThenQuota_andBreaksTiesByInputOrder() {
        // free tier first (bravo, charlie, delta), by quota desc within the tier
        // (charlie 500, delta 500, bravo 50); charlie precedes delta on the quota tie
        // only because it came first in the input — a stable sort, not a provider preference.
        val ranked = costOptimizedOf<Unit>().invoke(candidates, Unit)
        assertEquals(listOf("charlie", "delta", "bravo", "alpha"), ranked.providers())
    }

    @Test
    fun roundRobin_rotatesStartOffsetPerInvocation() {
        val rr = roundRobinOf<Unit>()
        assertEquals(0, rr.invocations)
        assertEquals(listOf("alpha", "bravo", "charlie", "delta"), rr.invoke(candidates, Unit).providers())
        assertEquals(listOf("bravo", "charlie", "delta", "alpha"), rr.invoke(candidates, Unit).providers())
        assertEquals(listOf("charlie", "delta", "alpha", "bravo"), rr.invoke(candidates, Unit).providers())
        assertEquals(listOf("delta", "alpha", "bravo", "charlie"), rr.invoke(candidates, Unit).providers())
        // wraps back around
        assertEquals(listOf("alpha", "bravo", "charlie", "delta"), rr.invoke(candidates, Unit).providers())
        assertEquals(5, rr.invocations)
    }

    @Test
    fun roundRobin_spreadSurvivesAChangingCandidateCount() {
        // Callers filter by capability before ranking, so the candidate count varies between
        // invocations of one strategy instance. A cursor normalized against the *current* count
        // would be clamped by the short list and restart the long list at index 0 every time.
        val rr = roundRobinOf<Unit>()
        val two: Series<ModelCatalogEntry> = listOf(alpha, bravo).toSeries()
        val heads = mutableListOf<String>()
        repeat(4) {
            heads.add(rr.invoke(candidates, Unit).providers().first())
            rr.invoke(two, Unit)
        }
        assertEquals(listOf("alpha", "charlie", "alpha", "charlie"), heads)
        // with a cursor normalized against the current count, the two-candidate call would clamp
        // it and every four-candidate head would come back "alpha", starving indices 1..3.
        assertTrue(heads.toSet().size > 1, "short-list calls must not starve the long list")
    }

    @Test
    fun roundRobin_onEmptyCandidatesIsIdentity() {
        val rr = roundRobinOf<Unit>()
        val empty: Series<ModelCatalogEntry> = emptyList<ModelCatalogEntry>().toSeries()
        assertEquals(0, rr.invoke(empty, Unit).size)
        assertEquals(0, rr.invocations, "an empty route must not consume a rotation slot")
    }

    @Test
    fun costAndAuto_sinkExhaustedCandidatesBelowUsableOnes() {
        // A free tier with nothing left cannot serve the request, so it must not head the
        // ranking — the head is what the selection point reports as chosen.
        val drained = ModelCatalogEntry("echo", "e-1", freeTier = true, quotaRemaining = 0, latencyEstimateMs = 10)
        val withDrained: Series<ModelCatalogEntry> = listOf(drained, alpha, charlie).toSeries()

        assertEquals(listOf("charlie", "alpha", "echo"), costOptimizedOf<Unit>().invoke(withDrained, Unit).providers())
        assertEquals(listOf("charlie", "alpha", "echo"), autoOf<Unit>().invoke(withDrained, Unit).providers())
        // weighted needs no special case: quota is its numerator, so a drained candidate scores 0
        assertEquals("echo", weightedOf<Unit>().invoke(withDrained, Unit).providers().last())
    }

    @Test
    fun auto_isCostOrderRefinedByLatencyTiebreak() {
        // Same cost ordering as CostOptimized, but the charlie/delta quota tie now resolves
        // on latency (delta 250ms beats charlie 2000ms) instead of on input position.
        val ranked = autoOf<Unit>().invoke(candidates, Unit)
        assertEquals(listOf("delta", "charlie", "bravo", "alpha"), ranked.providers())
    }

    @Test
    fun compose_runsAThenB_andPassThroughIsTheIdentityElement() {
        val cost = costOptimizedOf<Unit>()
        val pass = PassThroughStrategy<ModelCatalogEntry, Unit>()
        val expected = cost.invoke(candidates, Unit).providers()

        // right identity: a j pass == a
        assertEquals(expected, (cost j pass).invoke(candidates, Unit).providers())
        // left identity: pass j a == a
        assertEquals(expected, (pass j cost).invoke(candidates, Unit).providers())

        // b really runs on a's output, not on the original input: priority is a no-op, so the
        // composite is whatever cost produced; and the composite names both halves.
        val composed = priorityOf<Unit>() j cost
        assertEquals(expected, composed.invoke(candidates, Unit).providers())
        assertEquals("priority j cost-optimized", composed.strategyName)

        // associativity of the ranking, as a function
        val left = ((cost j pass) j PriorityStrategy<ModelCatalogEntry, Unit>()).invoke(candidates, Unit)
        val right = (cost j (pass j PriorityStrategy<ModelCatalogEntry, Unit>())).invoke(candidates, Unit)
        assertEquals(left.providers(), right.providers())
    }

    @Test
    fun compose_bSeesAOutput_notTheOriginalInput() {
        val cost = costOptimizedOf<Unit>()
        val rr = roundRobinOf<Unit>()
        // burn three rotations so `rr` next emits the input starting at delta
        repeat(3) { rr.invoke(candidates, Unit) }
        assertEquals(listOf("delta", "alpha", "bravo", "charlie"), rr.invoke(candidates, Unit).providers())
        rr.invoke(candidates, Unit); rr.invoke(candidates, Unit); rr.invoke(candidates, Unit)

        // rotate-then-cost: cost still wins the primary ordering, but the charlie/delta quota
        // tie now falls out delta-first, because cost's stable tiebreak sees the *rotated* order.
        assertEquals(listOf("delta", "charlie", "bravo", "alpha"), (rr j cost).invoke(candidates, Unit).providers())
        // whereas on the unrotated input the same tie falls out charlie-first
        assertEquals(listOf("charlie", "delta", "bravo", "alpha"), cost.invoke(candidates, Unit).providers())
    }

    @Test
    fun modelSelected_isEmittedAtTheSelectionPoint() {
        val mux = ModelMux(KeyMux {}) {
            model("gpt-4", caps = setOf("chat", "tools"))
            model("embed-3", caps = setOf("embed"))
        }
        val seen = mutableListOf<ModelSelectionEvent.ModelSelected>()
        mux.selectionObserver = { e -> if (e is ModelSelectionEvent.ModelSelected) seen.add(e) }

        val result = mux.route("chat", "tools")
        assertEquals(1, result.a.size)

        assertEquals(1, seen.size, "exactly one ModelSelected per non-empty route")
        val event = seen.single()
        assertEquals("gpt-4", event.model)
        assertEquals("gpt-4", event.provider)
        // the default label describes what CapabilityRouter actually does — filter and preserve
        // catalog order — rather than naming a strategy that never ran
        assertEquals("capability", event.strategy)
        assertTrue(event.requestId.startsWith("req"), "requestId came from SecureIdGenerator: ${event.requestId}")
        assertTrue(event.at > 0L, "at is an epoch-millis stamp")

        // the requestId is recoverable for reconciliation against a downstream receipt
        assertEquals(event, mux.lastSelection)

        // the event serializes as one line for the forge event stream
        val line = event.toJsonLine()
        assertTrue("ModelSelected" in line && "gpt-4" in line, line)
        assertEquals(1, line.lineSequence().count())

        // an owner that ranks through a strategy stamps that strategy's own name
        mux.strategyName = autoOf<Unit>().strategyName
        mux.route("chat", "tools")
        assertEquals("auto", seen.last().strategy)
        assertEquals(2, seen.size)
    }

    @Test
    fun aThrowingObserverDoesNotFailTheRoute() {
        val mux = ModelMux(KeyMux {}) { model("gpt-4", caps = setOf("chat")) }
        mux.selectionObserver = { throw IllegalStateException("sink is closed") }

        // observability must not be able to destroy an already-computed result
        val result = mux.route("chat")
        assertEquals(1, result.a.size)
        assertEquals("gpt-4", result.a[0].a)
        assertNotNull(mux.lastSelection, "the selection is still recorded when the sink fails")
    }

    @Test
    fun modelSelected_notEmittedWhenNothingMatches() {
        val mux = ModelMux(KeyMux {}) {
            model("embed-3", caps = setOf("embed"))
        }
        var emitted: ModelSelectionEvent? = null
        mux.selectionObserver = { e -> emitted = e }

        val result = mux.route("chat", "tools")
        assertEquals(0, result.a.size)
        assertEquals(null, emitted, "an empty route selects nothing, so it must emit nothing")

        // and the same mux does emit once a route succeeds
        assertNotNull(mux.route("embed").a[0])
        assertNotNull(emitted)
    }
}
