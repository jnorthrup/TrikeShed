package modelmux

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.htx.*
import borg.trikeshed.kernel.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import keymux.KeyMux
import keymux.TestKeySource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BreadcrumbReelTest {
    @Test fun laneTimeoutPreservesOtherOutputsAndFinishesPartialAfterDrain() = runTest {
        val completed = mutableSetOf<String>()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val lane = currentCoroutineContext()[KernelExecution]!!.seat.value
                val payload = reifyStrict(request.body.toArray().decodeToString()) as Map<*, *>
                assertEquals(if (lane == BreadcrumbLane.MICRO.id) 512 else 2048,
                    (payload["max_tokens"] as Number).toInt())
                assertFalse(payload.containsKey("chat_template_kwargs"))
                if (lane == BreadcrumbLane.MAIN.id) withTimeout(1) { awaitCancellation() }
                delay(10)
                completed += lane
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request, response = HtxResponse(200, ByteSeries(
                        """{"choices":[{"message":{"role":"assistant","content":"Evidence defines a constant."},"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":4}}"""
                            .encodeToByteArray()))))
            }
        })
        val mux = ModelMux(KeyMux { bind("llm.fixture.key", TestKeySource(value = "fixture-key")) }) {
            model("fixture", setOf("chat"), provider = "fixture", baseUrl = "https://fixture.invalid/v1", contextWindow = 8192)
        }
        val run = BreadcrumbReel("lane-timeout", mux, BreadcrumbElement(), ConfixBlackboard.empty(),
            "fixture", "fixture", s_["src/Evidence.kt" j "val evidence = 1"])
        assertEquals(BreadcrumbReelStore.HARNESS, run.snapshot()["harness"])
        assertEquals(mapOf("micro" to 512, "main" to 2048, "assessment" to 4096), run.snapshot()["outputLimits"])
        val kernel = KernelElement(coroutineContext + htx, KernelId("lane-timeout"))
        try {
            val seats = BreadcrumbLane.entries.associateWith { kernel.seat(KernelId(it.id), KernelCapability.MODEL) }
            run.run(seats)
            assertEquals("partial", run.status)
            assertEquals("failed", run.lanes.getValue(BreadcrumbLane.MAIN)["status"])
            assertEquals("TimeoutCancellationException", run.lanes.getValue(BreadcrumbLane.MAIN)["error"])
            assertEquals("completed", run.lanes.getValue(BreadcrumbLane.FULL)["status"])
            assertEquals("completed", run.lanes.getValue(BreadcrumbLane.MICRO)["status"])
            assertEquals(setOf("full", "micro"), completed)
            assertEquals("unavailable", run.assessment?.get("status"))
            assertNotNull(run.endedAt)
            for (lane in s_[BreadcrumbLane.FULL, BreadcrumbLane.MICRO].view) {
                val resultId = run.lanes.getValue(lane)["resultId"]
                val summary = run.blackboard.get("ace/breadcrumb/${run.id}/${lane.id}/$resultId") as Map<*, *>
                assertEquals("fixture", summary["model"], "Completion must preserve the final evidence sheet's model attribution")
            }
            assertEquals("fixture", run.events.first { it["kind"] == "recall" && it["lane"] == "micro" }["model"])
            assertFailsWith<IllegalArgumentException> { run.vote("main", "test fixture") }
            run.vote("tie", "test fixture")
            assertEquals(1, run.votes.size)
            run.lanes.getValue(BreadcrumbLane.MICRO)["output"] = ""
            assertFailsWith<IllegalArgumentException> { run.vote("micro", "test fixture") }
            assertFailsWith<IllegalArgumentException> { run.vote("tie", "test fixture") }
            run.vote("full", "test fixture")
            for (seat in seats.values) assertFailsWith<IllegalStateException> { seat.execute("after-drain") {} }
        } finally {
            kernel.drain()
            htx.close()
        }
    }

    @Test fun nvidiaRequestOptionIsRestrictedToConfiguredMainAndAssessmentCalls() = runTest {
        val model = "nvidia/nemotron-3-ultra-550b-a55b"
        val options = mapOf("chat_template_kwargs" to mapOf("enable_thinking" to false))
        val requests = mutableListOf<Join<String, Int>>()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val lane = currentCoroutineContext()[KernelExecution]!!.seat.value
                val payload = reifyStrict(request.body.toArray().decodeToString()) as Map<*, *>
                val limit = (payload["max_tokens"] as Number).toInt()
                requests += lane j limit
                if (lane == BreadcrumbLane.MICRO.id) {
                    assertEquals(512, limit)
                    assertFalse(payload.containsKey("chat_template_kwargs"))
                } else {
                    assertEquals(options["chat_template_kwargs"], payload["chat_template_kwargs"])
                    assertTrue(limit == 2048 || limit == 4096)
                }
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request, response = HtxResponse(200, ByteSeries(
                        """{"choices":[{"message":{"role":"assistant","content":"Evidence defines a constant."},"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":4}}"""
                            .encodeToByteArray()))))
            }
        })
        fun mux(id: String, baseUrl: String) =
            ModelMux(KeyMux { bind("llm.fixture.key", TestKeySource(value = "fixture-key")) }) {
                model(id, setOf("chat"), provider = "fixture", baseUrl = baseUrl, contextWindow = 8192)
                model("micro-fixture", setOf("chat"), provider = "fixture",
                    baseUrl = "https://fixture.invalid/v1", contextWindow = 2048)
            }
        val run = BreadcrumbReel("main-options",
            mux(model, "https://integrate.api.nvidia.com/v1/"), BreadcrumbElement(), ConfixBlackboard.empty(),
            model, "micro-fixture", s_["src/Evidence.kt" j "val evidence = 1"])
        assertEquals(options, run.snapshot()["mainRequestOptions"])
        for (binding in s_[model j "https://fixture.invalid/v1",
            "other-model" j "https://integrate.api.nvidia.com/v1"].view) {
            val other = BreadcrumbReel("other-options", mux(binding.a, binding.b),
                BreadcrumbElement(), ConfixBlackboard.empty(), binding.a, "micro-fixture",
                s_["src/Evidence.kt" j "val evidence = 1"])
            assertTrue(other.mainRequestOptions.isEmpty())
        }
        val kernel = KernelElement(coroutineContext + htx, KernelId("main-options"))
        try {
            run.run(BreadcrumbLane.entries.associateWith { kernel.seat(KernelId(it.id), KernelCapability.MODEL) })
            assertEquals("completed", run.status)
            assertEquals("completed", run.assessment?.get("status"))
            assertEquals(4, requests.size)
            assertTrue(requests.any { it.a == "full" && it.b == 2048 })
            assertTrue(requests.any { it.a == "main" && it.b == 2048 })
            assertTrue(requests.any { it.a == "micro" && it.b == 512 })
            assertTrue(requests.any { it.a == "full" && it.b == 4096 })
        } finally {
            kernel.drain()
            htx.close()
        }
    }

    @Test fun parentCancellationPropagatesOnlyAfterAdmittedEffectsDrain() = runTest {
        val admitted = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val lane = currentCoroutineContext()[KernelExecution]!!.seat.value
                admitted += lane
                if (admitted.size == BreadcrumbLane.entries.size) entered.complete(Unit)
                release.await()
                completed += lane
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request, response = HtxResponse(200, ByteSeries(
                        """{"choices":[{"message":{"role":"assistant","content":"Evidence defines a constant."},"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":4}}"""
                            .encodeToByteArray()))))
            }
        })
        val mux = ModelMux(KeyMux { bind("llm.fixture.key", TestKeySource(value = "fixture-key")) }) {
            model("fixture", setOf("chat"), provider = "fixture", baseUrl = "https://fixture.invalid/v1", contextWindow = 8192)
        }
        val run = BreadcrumbReel("parent-cancel", mux, BreadcrumbElement(), ConfixBlackboard.empty(),
            "fixture", "fixture", s_["src/Evidence.kt" j "val evidence = 1"])
        val kernel = KernelElement(coroutineContext + htx, KernelId("parent-cancel"))
        try {
            val seats = BreadcrumbLane.entries.associateWith { kernel.seat(KernelId(it.id), KernelCapability.MODEL) }
            val comparison = async { run.run(seats) }
            entered.await()
            comparison.cancel()
            yield()
            assertFalse(comparison.isCompleted, "Cancellation cannot finish while admitted model effects remain")
            assertNull(run.endedAt)
            release.complete(Unit)
            assertFailsWith<CancellationException> { comparison.await() }
            comparison.join()
            assertEquals(admitted, completed)
            assertNotNull(run.endedAt)
        } finally {
            release.complete(Unit)
            kernel.drain()
            htx.close()
        }
    }

    @Test fun assessmentRejectsOverflowAndTruncationWithoutClaimingCompletion() = runTest {
        for (ceiling in s_[1024, 8192].view) {
            var calls = 0
            val htx = openHtxElement(routeService = object : HtxRouteService {
                override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                    calls++
                    val payload = reifyStrict(request.body.toArray().decodeToString()) as Map<*, *>
                    assertEquals(4096, (payload["max_tokens"] as Number).toInt())
                    return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                        request = request, response = HtxResponse(200, ByteSeries(
                            """{"choices":[{"message":{"role":"assistant","content":"A: incomplete"},"finish_reason":"length"}],"usage":{"prompt_tokens":8,"completion_tokens":4}}"""
                                .encodeToByteArray()))))
                }
            })
            val mux = ModelMux(KeyMux { bind("llm.fixture.key", TestKeySource(value = "fixture-key")) }) {
                model("fixture", setOf("chat"), provider = "fixture", baseUrl = "https://fixture.invalid/v1",
                    contextWindow = ceiling)
            }
            val run = BreadcrumbReel("assessment-$ceiling", mux, BreadcrumbElement(), ConfixBlackboard.empty(),
                "fixture", "fixture", s_["src/Evidence.kt" j "val evidence = 1\n".repeat(100)])
            for (lane in BreadcrumbLane.entries) run.lanes.getValue(lane).putAll(mapOf(
                "status" to "completed", "resultId" to "candidate-${lane.id}", "output" to "The code defines evidence."))
            val kernel = KernelElement(coroutineContext + htx, KernelId("assessment-$ceiling"))
            try {
                run.assess(kernel.seat(KernelId("assessor"), KernelCapability.MODEL))
                val assessment = assertNotNull(run.assessment)
                assertEquals("failed", assessment["status"])
                assertNull(assessment["content"])
                assertNull(assessment["resultId"])
                if (ceiling == 1024) {
                    assertEquals(0, calls, "Over-budget source must not be submitted or silently clipped")
                    assertTrue(run.events.any { it["kind"] == "context-limit" })
                } else {
                    assertEquals(1, calls)
                    assertNotNull(assessment["receipt"], "Failed assessment keeps the actual model receipt")
                    assertTrue(run.events.any { it["kind"] == "incomplete" })
                }
            } finally {
                kernel.drain()
                htx.close()
            }
        }
    }
}
