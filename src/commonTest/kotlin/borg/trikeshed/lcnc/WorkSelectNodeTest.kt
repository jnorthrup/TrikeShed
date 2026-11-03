package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class WorkSelectNodeTest {
    private fun offer(id: String, kind: String, price: Long, expires: Long = 2000L): Map<String, Any?> = _m[
        "id" j id, "kind" j kind, "feature" j "classification", "currency" j "USD",
        "totalMicros" j price.toString(), "expiresAt" j expires, "source" j "operator quote", "available" j true,
        "endpoint" j "provider", "model" j id,
    ]

    private fun inputs(offers: List<Map<String, Any?>>): Map<String, Any?> = _m[
        "offers" j offers, "feature" j "classification", "currency" j "USD", "ceilingMicros" j "100",
    ]

    private suspend fun select(offers: List<Map<String, Any?>>): Map<String, Any?> =
        PureNodes.registry { 1000L }.getValue(WorkSelectNode.TYPE)
            .run(LcncNode("choice", WorkSelectNode.TYPE), inputs(offers))

    @Test fun selectsLowestDeclaredPriceAcrossModelAndService(): Unit = runTest {
        val result = select(_l[offer("m", "model", 80), offer("s", "service", 50)])
        assertEquals("s", assertIs<Map<*, *>>(result["selection"])["id"])
        assertEquals(false, result["model"])
        assertEquals(true, result["service"])
        assertEquals("planning_only_unverified_quotes", result["status"])
        val modelResult = select(_l[offer("m", "model", 20), offer("s", "service", 50)])
        assertEquals(true, modelResult["model"])
        assertEquals(false, modelResult["service"])
    }

    @Test fun excludesUnknownExpiredUnavailableAndOverBudgetQuotes(): Unit = runTest {
        val result = select(_l[
            offer("unknown", "model", 0) - "totalMicros",
            offer("expired", "service", 1, 1000),
            offer("unavailable", "model", 1) + _m["available" j false],
            offer("expensive", "service", 101),
            offer("currency", "model", 1) + _m["currency" j "EUR"],
        ])
        assertNull(result["selection"])
        assertEquals(false, result["model"])
        assertEquals(false, result["service"])
        val decisions = assertIs<List<*>>(result["decisions"])
        assertEquals(_l["price_unknown", "quote_expired_or_invalid", "availability_not_declared", "above_ceiling", "currency_mismatch"],
            decisions.map { assertIs<Map<*, *>>(it)["reason"] })
    }

    @Test fun refusesAmbiguousIdsAndInexactLimits(): Unit = runTest {
        assertFailsWith<IllegalArgumentException> {
            select(_l[offer("same", "model", 1), offer("same", "service", 2)])
        }
        val runner = PureNodes.registry { 1000L }.getValue(WorkSelectNode.TYPE)
        assertFailsWith<IllegalStateException> {
            runner.run(LcncNode("choice", WorkSelectNode.TYPE), inputs(emptyList()) + _m["ceilingMicros" j 0.5])
        }
        for (invalid in _l["00", "01", "+1", "-1", "0.1", "9007199254740992"]) {
            assertFailsWith<IllegalStateException> {
                runner.run(LcncNode("choice", WorkSelectNode.TYPE), inputs(emptyList()) + _m["ceilingMicros" j invalid])
            }
        }
        val invalidPrice = select(_l[offer("rounded", "model", 1) + _m["totalMicros" j 0.99999999999999999]])
        assertNull(invalidPrice["selection"])
    }

    @Test fun guardedRingsRunOnlyTheSelectedBranch(): Unit = runTest {
        var calls = ""
        var offers = _l[offer("model", "model", 10), offer("service", "service", 20)]
        val registry = PureNodes.registry { 1000L } + _m[
            "test.source" j LcncNodeRunner { _, _ -> inputs(offers) },
            "test.model" j LcncNodeRunner { _, _ -> calls += "m"; emptyMap() },
            "test.service" j LcncNodeRunner { _, _ -> calls += "s"; emptyMap() },
        ]
        val program = LcncProgram("work", s_[
            LcncNode("source", "test.source"),
            LcncNode("choice", WorkSelectNode.TYPE),
            LcncNode("model", LcncContracts.SCOPE, children = s_[LcncNode("modelCall", "test.model")]),
            LcncNode("service", LcncContracts.SCOPE, children = s_[LcncNode("serviceCall", "test.service")]),
        ], s_[
            LcncWire("source", "offers", "choice", "offers"),
            LcncWire("source", "feature", "choice", "feature"),
            LcncWire("source", "currency", "choice", "currency"),
            LcncWire("source", "ceilingMicros", "choice", "ceilingMicros"),
            LcncWire("choice", "model", "model", "when"),
            LcncWire("choice", "service", "service", "when"),
        ])
        LcncRunner(registry).runAll(program)
        assertEquals("m", calls)
        offers = _l[offer("model", "model", 30), offer("service", "service", 20)]
        LcncRunner(registry).runAll(program)
        assertEquals("ms", calls)
        offers = _l[offer("expired", "service", 1, 1000)]
        LcncRunner(registry).runAll(program)
        assertEquals("ms", calls)
    }
}
