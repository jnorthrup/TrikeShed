package borg.trikeshed.agent.modelmux

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.htx.*
import borg.trikeshed.kernel.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.keymux.TestKeySource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BreadcrumbReelTest {
    private companion object {
        const val EVIDENCE_NAME = "src/Evidence.kt"

        // Lines that the default review prior (close, require) selects, and others it does not.
        const val EVIDENCE = "class Evidence {\n    val evidence = 1\n    val a = 0\n    val b = 0\n    val c = 0\n" +
            "    fun close() { require(open) { \"already closed\" } }\n}\n"

        // Two lines about the manifest, far enough apart that they are separate excerpts.
        const val NOTES = "The manifest must be verified before caching.\nfiller one\nfiller two\nCargo manifest lists forty crates of oranges.\n"
    }

    @Test fun laneTimeoutPreservesOtherOutputsAndFinishesPartialAfterDrain() = runTest {
        val completed = mutableSetOf<String>()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val lane = currentCoroutineContext()[KernelExecution]!!.seat.value
                val payload = reifyStrict(request.body.toArray().decodeToString()) as Map<*, *>
                assertNotEquals(BreadcrumbLane.MICRO.id, lane, "the heuristic lane makes no model request")
                assertEquals(2048, (payload["max_tokens"] as Number).toInt())
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
            "fixture", s_[EVIDENCE_NAME j EVIDENCE])
        assertEquals(BreadcrumbReelStore.HARNESS, run.snapshot()["harness"])
        assertEquals(mapOf("main" to 2048, "assessment" to 4096, "microProjectionChars" to BreadcrumbReel.MICRO_PROJECTION_CHARS), run.snapshot()["outputLimits"])
        assertEquals(mapOf("main" to "fixture", "micro" to BreadcrumbReel.HEURISTIC_LABEL), run.snapshot()["models"])
        val kernel = KernelElement(coroutineContext + htx, KernelId("lane-timeout"))
        try {
            val seats = BreadcrumbLane.entries.associateWith { kernel.seat(KernelId(it.id), KernelCapability.MODEL) }
            run.run(seats)
            assertEquals("partial", run.status)
            assertEquals("failed", run.lanes.getValue(BreadcrumbLane.MAIN)["status"])
            assertEquals("TimeoutCancellationException", run.lanes.getValue(BreadcrumbLane.MAIN)["error"])
            assertEquals("completed", run.lanes.getValue(BreadcrumbLane.FULL)["status"])
            assertEquals("completed", run.lanes.getValue(BreadcrumbLane.MICRO)["status"])
            assertEquals(setOf("full"), completed, "MICRO makes no model exchange")
            assertEquals("unavailable", run.assessment?.get("status"))
            assertNotNull(run.endedAt)
            for (lane in s_[BreadcrumbLane.FULL, BreadcrumbLane.MICRO].view) {
                val resultId = run.lanes.getValue(lane)["resultId"]
                val summary = run.blackboard.get("ace/breadcrumb/${run.id}/${lane.id}/$resultId") as Map<*, *>
                assertEquals(run.laneModel(lane), summary["model"], "Completion must preserve the final evidence sheet's model attribution")
            }
            assertEquals(BreadcrumbReel.HEURISTIC_LABEL, run.events.first { it["kind"] == "projection" && it["lane"] == "micro" }["model"])
            assertTrue(run.events.none { it["lane"] == "micro" && it["kind"] in setOf("dispatch", "recall", "recall-evidence", "summary") },
                "no summarizer step is recorded for the heuristic lane")
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
                assertNotEquals(BreadcrumbLane.MICRO.id, lane, "the heuristic lane makes no model request")
                assertEquals(options["chat_template_kwargs"], payload["chat_template_kwargs"])
                assertTrue(limit == 2048 || limit == 4096)
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request, response = HtxResponse(200, ByteSeries(
                        """{"choices":[{"message":{"role":"assistant","content":"Evidence defines a constant."},"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":4}}"""
                            .encodeToByteArray()))))
            }
        })
        fun mux(id: String, baseUrl: String) =
            ModelMux(KeyMux { bind("llm.fixture.key", TestKeySource(value = "fixture-key")) }) {
                model(id, setOf("chat"), provider = "fixture", baseUrl = baseUrl, contextWindow = 8192)
            }
        val run = BreadcrumbReel("main-options",
            mux(model, "https://integrate.api.nvidia.com/v1/"), BreadcrumbElement(), ConfixBlackboard.empty(),
            model, s_[EVIDENCE_NAME j EVIDENCE])
        assertEquals(options, run.snapshot()["mainRequestOptions"])
        for (binding in s_[model j "https://fixture.invalid/v1",
            "other-model" j "https://integrate.api.nvidia.com/v1"].view) {
            val other = BreadcrumbReel("other-options", mux(binding.a, binding.b),
                BreadcrumbElement(), ConfixBlackboard.empty(), binding.a,
                s_[EVIDENCE_NAME j EVIDENCE])
            assertTrue(other.mainRequestOptions.isEmpty())
        }
        val kernel = KernelElement(coroutineContext + htx, KernelId("main-options"))
        try {
            run.run(BreadcrumbLane.entries.associateWith { kernel.seat(KernelId(it.id), KernelCapability.MODEL) })
            assertEquals("completed", run.status)
            assertEquals("completed", run.assessment?.get("status"))
            assertEquals(3, requests.size)
            assertTrue(requests.any { it.a == "full" && it.b == 2048 })
            assertTrue(requests.any { it.a == "main" && it.b == 2048 })
            assertTrue(requests.none { it.a == "micro" }, "the heuristic lane contributes no model request")
            assertTrue(requests.any { it.a == "full" && it.b == 4096 })
            val microRow = (run.snapshot()["lanes"] as List<*>).map { it as Map<*, *> }.first { it["id"] == "micro" }
            assertEquals("completed", microRow["status"])
            assertNotNull(microRow["heuristic"], "the snapshot carries the heuristic lane's record")
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
                if (admitted.size == 2) entered.complete(Unit)   // the two model lanes
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
            "fixture", s_[EVIDENCE_NAME j EVIDENCE])
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
                "fixture", s_["src/Evidence.kt" j "val evidence = 1\n".repeat(100)])
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

    private fun idleMux() = ModelMux(KeyMux { bind("llm.fixture.key", TestKeySource(value = "fixture-key")) }) {
        model("fixture", setOf("chat"), provider = "fixture", baseUrl = "https://fixture.invalid/v1", contextWindow = 8192)
    }

    /** A route that records, then fails, anything that tries to reach a model. */
    private suspend fun tripwireHtx(calls: MutableList<String>) = openHtxElement(routeService = object : HtxRouteService {
        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
            calls += "exchange"
            error("the heuristic lane must not make a model request")
        }
    })

    private fun judge(notRelevantContaining: String? = null, unavailable: Boolean = false): BreadcrumbOversight = object : BreadcrumbOversight {
        override suspend fun review(question: String, projection: Projection): OversightReceipt {
            val n = projection.excerpts.size
            if (unavailable) return OversightReceipt(OversightStatus.UNAVAILABLE, null, List<Verdict?>(n) { null }, List<Double?>(n) { null }, "judge down")
            val verdicts = List<Verdict?>(n) { i ->
                if (notRelevantContaining != null && notRelevantContaining in projection.excerpts[i].text) Verdict.NOT_RELEVANT else Verdict.RELEVANT
            }
            return OversightReceipt(OversightStatus.REVIEWED, "fake-system1", verdicts,
                List<Double?>(n) { i -> if (verdicts[i] == Verdict.NOT_RELEVANT) 0.1 else 0.9 }, null)
        }
    }

    private fun heuristicOf(run: BreadcrumbReel): Map<*, *> = run.lanes.getValue(BreadcrumbLane.MICRO)["heuristic"] as Map<*, *>

    @Test fun microUsesSharedContextWithNoChatSummarizer() = runTest {
        val exchanges = mutableListOf<String>()
        val htx = tripwireHtx(exchanges)
        val context = SharedContext(CasStore.inMemory(), null, ContextReadPolicy(allowedScopes = setOf("reel/micro-1")))
        val run = BreadcrumbReel("micro-1", idleMux(), BreadcrumbElement(), ConfixBlackboard.empty(), "fixture",
            s_[EVIDENCE_NAME j EVIDENCE], context = context)
        try {
            val summary = withContext(htx) { run.project(BreadcrumbLane.MICRO) }

            assertTrue(summary.content.startsWith("@ $EVIDENCE_NAME:"), summary.content)
            assertTrue(summary.content.contains("fun close() { require(open) { \"already closed\" } }"), summary.content)
            assertEquals(0, exchanges.size, "no model was contacted")
            val lane = run.lanes.getValue(BreadcrumbLane.MICRO)
            assertEquals(0L, lane["inputTokens"])
            assertEquals(0L, lane["outputTokens"])
            assertNull(lane["calls"], "no model call was counted")

            val heuristic = heuristicOf(run)
            assertEquals(BreadcrumbProjector.POLICY, heuristic["policy"])
            assertEquals("default", heuristic["questionSource"])
            assertEquals(BreadcrumbReel.DEFAULT_QUESTION, heuristic["question"])
            assertEquals("NOT_REQUESTED", (heuristic["oversight"] as Map<*, *>)["status"])

            assertTrue(context.counters().localReads >= 2, "the evidence was read back through the verified shared-context path")
            assertEquals(EVIDENCE, run.evidence[0].b, "the source is unchanged")
            val original = ContentId.of("SOURCE $EVIDENCE_NAME\n$EVIDENCE".encodeToByteArray()).hex
            assertEquals(listOf(original), summary.children.view.map { it.value }, "the output's lineage is the retained original")

            assertEquals(listOf("retained", "selection", "projection", "oversight"), run.events.filter { it["lane"] == "micro" }.map { it["kind"] })
            assertEquals(BreadcrumbReel.HEURISTIC_LABEL, run.events.first { it["kind"] == "projection" }["model"])
            assertNull(run.events.first { it["kind"] == "oversight" }["model"], "no judge ran, so none is named")
        } finally {
            htx.close()
        }
    }

    @Test fun microQuestionComesFromTheHumanSelectedAgendaNeverFromGeneratedContent() = runTest {
        val agenda = s_[mapOf<String, Any?>("concept" to "evidence", "note" to " ", "content" to "close require drain")]
        val run = BreadcrumbReel("micro-2", idleMux(), BreadcrumbElement(), ConfixBlackboard.empty(), "fixture",
            s_[EVIDENCE_NAME j EVIDENCE], agenda = agenda)
        val summary = run.project(BreadcrumbLane.MICRO)
        val heuristic = heuristicOf(run)
        assertEquals("agenda", heuristic["questionSource"])
        assertEquals("evidence", heuristic["question"])
        assertTrue(summary.content.contains("val evidence = 1"), summary.content)
        assertFalse(summary.content.contains("fun close()"), "terms from an earlier output must not steer selection")
    }

    @Test fun systemOneReviewPrunesExcerptsButNeverRewritesSourceAndIsAttributedSeparately() = runTest {
        val agenda = s_[mapOf<String, Any?>("concept" to "manifest", "note" to "")]
        val run = BreadcrumbReel("micro-3", idleMux(), BreadcrumbElement(), ConfixBlackboard.empty(), "fixture",
            s_["notes.md" j NOTES], agenda = agenda, oversight = judge(notRelevantContaining = "oranges"))
        val summary = run.project(BreadcrumbLane.MICRO)

        assertTrue(summary.content.contains("The manifest must be verified before caching."), summary.content)
        assertFalse(summary.content.contains("oranges"), "the judge pruned the irrelevant excerpt from the view")
        val heuristic = heuristicOf(run)
        assertEquals(1, heuristic["flagged"])
        assertEquals(1, heuristic["shown"])
        val oversight = heuristic["oversight"] as Map<*, *>
        assertEquals("REVIEWED", oversight["status"])
        assertEquals("fake-system1", oversight["model"])

        // The judge reviewed; it did not generate. Output stays attributed to the heuristic, the judge to its own event.
        assertEquals("fake-system1", run.events.first { it["kind"] == "oversight" }["model"])
        assertEquals(BreadcrumbReel.HEURISTIC_LABEL, run.events.first { it["kind"] == "projection" }["model"])
        val board = run.blackboard.get("ace/breadcrumb/micro-3/micro/${summary.id.value}") as Map<*, *>
        assertEquals(BreadcrumbReel.HEURISTIC_LABEL, board["model"])
        assertTrue(run.evidence[0].b.contains("oranges"), "the source still holds what the view pruned")
    }

    @Test fun anUnavailableJudgeLeavesTheProjectionUnprunedAndSaysSo() = runTest {
        val agenda = s_[mapOf<String, Any?>("concept" to "manifest", "note" to "")]
        val run = BreadcrumbReel("micro-4", idleMux(), BreadcrumbElement(), ConfixBlackboard.empty(), "fixture",
            s_["notes.md" j NOTES], agenda = agenda, oversight = judge(unavailable = true))
        val summary = run.project(BreadcrumbLane.MICRO)
        assertTrue(summary.content.contains("oranges") && summary.content.contains("verified"), "unavailable review is not approval and prunes nothing")
        val oversight = heuristicOf(run)["oversight"] as Map<*, *>
        assertEquals("UNAVAILABLE", oversight["status"])
        assertNull(oversight["model"])
        assertEquals(0, heuristicOf(run)["flagged"])
    }

    @Test fun aLaneNeverCompletesEmpty() = runTest {
        val unmatched = BreadcrumbReel("micro-5", idleMux(), BreadcrumbElement(), ConfixBlackboard.empty(), "fixture", s_["a.txt" j "alpha beta\n"])
        assertFailsWith<EmptyProjectionException> { unmatched.project(BreadcrumbLane.MICRO) }
        assertTrue(unmatched.events.any { it["kind"] == "incomplete" && it["lane"] == "micro" })

        val agenda = s_[mapOf<String, Any?>("concept" to "manifest", "note" to "")]
        val allPruned = BreadcrumbReel("micro-5b", idleMux(), BreadcrumbElement(), ConfixBlackboard.empty(), "fixture",
            s_["notes.md" j "The manifest is verified.\n"], agenda = agenda, oversight = judge(notRelevantContaining = "manifest"))
        assertFailsWith<EmptyProjectionException> { allPruned.project(BreadcrumbLane.MICRO) }
    }

    @Test fun anEmptySelectionNeverConsultsSystemOneAndIsNotBlamedOnIt() = runTest {
        val consulted = mutableListOf<String>()
        val recording = object : BreadcrumbOversight {
            override suspend fun review(question: String, projection: Projection): OversightReceipt {
                consulted += question
                return judge().review(question, projection)
            }
        }
        val run = BreadcrumbReel("micro-5c", idleMux(), BreadcrumbElement(), ConfixBlackboard.empty(), "fixture",
            s_["a.txt" j "alpha beta\n"], oversight = recording)
        assertFailsWith<EmptyProjectionException> { run.project(BreadcrumbLane.MICRO) }
        assertEquals(emptyList(), consulted, "nothing was selected, so there is nothing for a model to review and no model call to spend")
        val incomplete = run.events.single { it["kind"] == "incomplete" }
        assertEquals("No source line matched the question", incomplete["title"], "the failure belongs to the lexical selection, not to oversight")
    }

    @Test fun aContextThatDoesNotServeTheReelScopeRefusesInsteadOfFallingBack() = runTest {
        val other = SharedContext(CasStore.inMemory(), null, ContextReadPolicy(allowedScopes = setOf("some/other-scope")))
        val run = BreadcrumbReel("micro-6", idleMux(), BreadcrumbElement(), ConfixBlackboard.empty(), "fixture",
            s_[EVIDENCE_NAME j EVIDENCE], context = other)
        val refused = assertFailsWith<ContextBlockException> { run.project(BreadcrumbLane.MICRO) }
        assertEquals(ContextRejection.FORBIDDEN_SCOPE, refused.reason)
    }

    @Test fun theChatSummarizerPathRefusesTheHeuristicLane() = runTest {
        val exchanges = mutableListOf<String>()
        val htx = tripwireHtx(exchanges)
        val run = BreadcrumbReel("micro-7", idleMux(), BreadcrumbElement(), ConfixBlackboard.empty(), "fixture", s_[EVIDENCE_NAME j EVIDENCE])
        val kernel = KernelElement(coroutineContext + htx, KernelId("micro-7"))
        try {
            val seat = kernel.seat(KernelId("micro"), KernelCapability.MODEL)
            assertFailsWith<IllegalArgumentException> { run.hierarchy(BreadcrumbLane.MICRO, seat) }
            assertFailsWith<IllegalArgumentException> { run.summarize(BreadcrumbLane.MICRO, seat, "t", emptySeriesOf()) }
            assertEquals(0, exchanges.size)
        } finally {
            kernel.drain()
            htx.close()
        }
    }
}
