package borg.trikeshed.lcnc.ccek

import borg.trikeshed.context.ElementState
import borg.trikeshed.context.nuid.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class LcncWorkgroupTest {
    class Host : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Host>
    }

    suspend fun fixture(body: suspend (CcekReactorBinding, NuidFanoutElement) -> Unit) {
        val reactor = MuxReactorElement()
        reactor.open()
        val fanout = NuidFanoutElement()
        fanout.open()
        try { body(ccekReactorBinding(reactor), fanout) }
        finally { withContext(NonCancellable) { try { fanout.drain() } finally { reactor.drain() } } }
        assertEquals(ElementState.CLOSED, fanout.state)
        assertFalse(fanout.supervisor.children.any())
    }

    fun request(scope: String = "local.review.worker") =
        nuid(Capability.Custom("lcnc", "assembly"), Nonce.RandomBytes(), Subnet.parse(scope))

    fun group(name: String, scope: String = "local.review") =
        Workgroup(name, Subnet.parse(scope), traitSpaceOf(Capability.Custom("lcnc", "assembly")))

    @Test fun acceptedAssemblyCanPollinateAChildVoteAfterExternalAdmissionStops() = runTest {
        fixture { binding, fanout ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = mutableListOf<AgentRunRequest>()
            val agents = InMemoryAgentRuns(emptyList()) { request ->
                calls += request
                AgentRunResult(request.runId, request.jobId, request.agent, exit = 0, summary = "Observed nested task")
            }
            val childRequest = request("local.vote.worker")
            val tallyRequest = request("local.vote.tally.worker")
            val child = LcncProgram("vote", s_[
                LcncNode("agent", "scope.in", mapOf("name" to "agent", "kind" to "json")),
                LcncNode("summary", "scope.in", mapOf("name" to "summary", "kind" to "text")),
                LcncNode("ballots", "scope.in", mapOf("name" to "ballots", "kind" to "json")),
                LcncNode("vote", "panel.vote", mapOf("quorum" to "2")),
                LcncNode("agent.out", "scope.out", mapOf("name" to "agent", "kind" to "json")),
                LcncNode("summary.out", "scope.out", mapOf("name" to "summary", "kind" to "text")),
                LcncNode("ballots.out", "scope.out", mapOf("name" to "ballots", "kind" to "json")),
                LcncNode("triage.out", "scope.out", mapOf("name" to "triage", "kind" to "text")),
            ], s_[
                LcncWire("agent", "value", "agent.out", "value"), LcncWire("summary", "value", "summary.out", "value"),
                LcncWire("ballots", "value", "vote", "ballots"), LcncWire("vote", "transcript", "ballots.out", "value"),
                LcncWire("vote", "triage", "triage.out", "value"),
            ])
            val registry = PanelVoteNode.registry() + AgentNodes.registry(agents) { "nested-agent" }
            val reduce = registry.getValue("panel.vote")
            var tallyNode: borg.trikeshed.ccek.ArticulatedNode? = null
            val tallyCid = binding.registerLcncWorkgroup(fanout, group("vote", "local.vote.tally"), child,
                registry + ("panel.vote" to LcncNodeRunner { node, inputs ->
                    val selected = checkNotNull(currentCoroutineContext()[NuidInvocation])
                    assertEquals(listOf("tasks", "relay", "vote"), selected.lineage.view.map { it.a.name })
                    assertEquals("relay", selected.value.b.parent?.a?.name)
                    tallyNode = currentCoroutineContext()[borg.trikeshed.ccek.ArticulatedNode]
                    reduce.run(node, inputs)
                }))
            val relay = LcncProgram("relay", s_[
                LcncNode("input", "scope.in", mapOf("name" to "evidence", "kind" to "json")),
                LcncNode("relay", "test.relay"),
                LcncNode("output", "scope.out", mapOf("name" to "evidence", "kind" to "json")),
            ], s_[LcncWire("input", "value", "relay", "evidence"), LcncWire("relay", "evidence", "output", "value")])
            val childCid = binding.registerLcncWorkgroup(fanout, group("relay", "local.vote"), relay,
                mapOf("test.relay" to LcncNodeRunner { _, inputs ->
                    val parent = NuidInvocation.require()
                    assertEquals("relay", parent.a.name)
                    assertEquals("tasks", parent.b.parent?.a?.name)
                    @Suppress("UNCHECKED_CAST")
                    val evidence = assertIs<Map<String, Any?>>(inputs["evidence"])
                    val result = fanout.runLcncWorkgroup(tallyRequest, evidence)
                    assertEquals("vote", result.a.winner)
                    assertEquals(tallyCid, result.b.a)
                    assertEquals("relay", NuidInvocation.require().a.name)
                    mapOf("evidence" to result.b.b.returns)
                }))
            val parent = LcncProgram("tasks", s_[
                LcncNode("agent", "agent.run", mapOf("agent" to "stub", "brief" to "inspect nested task", "jobId" to "nested-task")),
                LcncNode("ballots", "scope.in", mapOf("name" to "ballots", "kind" to "json")),
                LcncNode("delegate", "test.delegate"),
                LcncNode("evidence", "scope.out", mapOf("name" to "evidence", "kind" to "json")),
            ], s_[
                LcncWire("agent", "receipt", "delegate", "agent"), LcncWire("agent", "summary", "delegate", "summary"),
                LcncWire("ballots", "value", "delegate", "ballots"), LcncWire("delegate", "evidence", "evidence", "value"),
            ])
            var parentClaim = 0L
            var childClaim = 0L
            val parentRegistry = registry + ("test.delegate" to LcncNodeRunner { _, inputs ->
                val current = LcncWorkgroupKey.require()
                parentClaim = current.b.a.claimId
                assertEquals("tasks", NuidInvocation.require().a.name)
                entered.complete(Unit)
                release.await()
                val result = fanout.runLcncWorkgroup(childRequest, mapOf("evidence" to inputs))
                childClaim = result.a.claimId
                assertEquals("relay", result.a.winner)
                assertEquals(childCid, result.b.a)
                assertEquals("tasks", LcncWorkgroupKey.require().a.name)
                assertEquals(parentClaim, NuidInvocation.require().b.claimId)
                mapOf("evidence" to result.b.b.returns["evidence"])
            })
            binding.registerLcncWorkgroup(fanout, group("tasks"), parent, parentRegistry)
            val ballots = listOf(mapOf("seat" to "first", "approve" to true), mapOf("seat" to "second", "approve" to false))
            val pending = async { fanout.runLcncWorkgroup(request(), mapOf("ballots" to ballots)) }
            entered.await()
            fanout.stopAdmission()
            assertFailsWith<IllegalStateException> { fanout.dispatch(childRequest) }
            val drained = async { fanout.drain() }
            assertFalse(drained.isCompleted)
            release.complete(Unit)
            val result = pending.await()
            drained.await()
            assertTrue(parentClaim > 0 && childClaim > parentClaim)
            val evidence = assertIs<Map<*, *>>(result.b.b.returns["evidence"])
            assertEquals("research", evidence["triage"])
            assertEquals("Observed nested task", evidence["summary"])
            assertEquals("nested-task", assertIs<Map<*, *>>(evidence["agent"])["jobId"])
            assertEquals(listOf(true, false), assertIs<List<*>>(evidence["ballots"]).map { assertIs<Map<*, *>>(it)["approve"] })
            assertEquals(1, calls.size)
            val events = assertNotNull(tallyNode).recording().filterIsInstance<borg.trikeshed.ccek.ForgeSignal.AppendBlock>()
            assertEquals(listOf("started", "finished"), events.map { it.properties["state"] })
            for (event in events) {
                assertEquals("relay", event.properties["parentWorkgroup"])
                assertEquals(childClaim.toString(), event.properties["parentClaimId"])
                val lineage = assertIs<List<*>>(borg.trikeshed.parse.reify(assertNotNull(event.properties["claimLineage"])))
                assertEquals(listOf("tasks", "relay", "vote"), lineage.map { assertIs<Map<*, *>>(it)["workgroup"] })
                assertEquals(parentClaim, (assertIs<Map<*, *>>(lineage.first())["claimId"] as Number).toLong())
            }
            assertEquals(ElementState.CLOSED, fanout.state)
        }
    }

    @Test fun routedAssemblyGathersAgentReceiptsVotesAndPassesRetainedInputsToAnotherAssembly() = runTest {
        fixture { binding, fanout ->
            val calls = mutableListOf<AgentRunRequest>()
            val agents = InMemoryAgentRuns(emptyList()) { request ->
                calls += request
                AgentRunResult(request.runId, request.jobId, request.agent, exit = 0,
                    transcriptCid = "transcript-${request.jobId}", patchCid = "patch-${request.jobId}",
                    summary = "Observed ${request.jobId}")
            }
            var ordinal = 0
            val registry = PureNodes.registry { 0L } + PanelVoteNode.registry() +
                CouncilNodes.registry(CouncilDialog { error("No model dispatch in this assembly") }) +
                AgentNodes.registry(agents) { "agent-${++ordinal}" }
            val program = LcncProgram("work", s_[
                LcncNode("tasks", "scope", children = s_[
                    LcncNode("first", "agent.run", params = mapOf("agent" to "stub", "brief" to "inspect first", "jobId" to "task-a")),
                    LcncNode("second", "agent.run", params = mapOf("agent" to "stub", "brief" to "inspect second", "jobId" to "task-b")),
                    LcncNode("summary", "text.fold"),
                    LcncNode("first.out", "scope.out", params = mapOf("name" to "first", "kind" to "json")),
                    LcncNode("second.out", "scope.out", params = mapOf("name" to "second", "kind" to "json")),
                    LcncNode("summary.out", "scope.out", params = mapOf("name" to "summary", "kind" to "text")),
                ]),
                LcncNode("ballots", "scope.in", params = mapOf("name" to "ballots", "kind" to "json")),
                LcncNode("vote", "panel.vote", params = mapOf("quorum" to "2")),
                LcncNode("tasks.out", "scope.out", params = mapOf("name" to "tasks", "kind" to "json")),
                LcncNode("ballots.out", "scope.out", params = mapOf("name" to "ballots", "kind" to "json")),
                LcncNode("triage.out", "scope.out", params = mapOf("name" to "triage", "kind" to "text")),
                LcncNode("summary.root", "scope.out", params = mapOf("name" to "summary", "kind" to "text")),
            ], s_[
                LcncWire("first", "summary", "summary", "parts"), LcncWire("second", "summary", "summary", "parts"),
                LcncWire("first", "receipt", "first.out", "value"), LcncWire("second", "receipt", "second.out", "value"),
                LcncWire("summary", "text", "summary.out", "value"),
                LcncWire("ballots", "value", "vote", "ballots"),
                LcncWire("tasks", "returns", "tasks.out", "value"),
                LcncWire("vote", "transcript", "ballots.out", "value"), LcncWire("vote", "triage", "triage.out", "value"),
                LcncWire("tasks", "summary", "summary.root", "value"),
            ])
            assertEquals(emptyList(), LcncTypeCheck.check(program))
            val sourceCid = binding.registerLcncWorkgroup(fanout, group("tasks"), program, registry)
            // These are explicit operator ballots. Successful process execution does not manufacture approval.
            val ballots = listOf(mapOf("seat" to "review-a", "approve" to true), mapOf("seat" to "review-b", "approve" to true))
            val result = fanout.runLcncWorkgroup(request(), mapOf("ballots" to ballots))
            assertEquals("tasks", result.a.winner)
            assertTrue(assertNotNull(result.a.completion).isCompleted)
            assertEquals(listOf("task-a", "task-b"), calls.map { it.jobId })
            assertEquals(sourceCid, result.b.a)
            val gathered = result.b.b.returns
            assertEquals("advance", gathered["triage"])
            val receipts = assertIs<Map<*, *>>(gathered["tasks"])
            assertEquals("task-a", assertIs<Map<*, *>>(receipts["first"])["jobId"])
            assertEquals("transcript-task-b", assertIs<Map<*, *>>(receipts["second"])["transcriptCid"])
            assertContains(gathered["summary"].toString(), "Observed task-a")
            assertContains(gathered["summary"].toString(), "Observed task-b")

            val received = mutableListOf<Map<String, Any?>>()
            val recipient = LcncProgram("recipient", s_[LcncNode("read", "capture")], emptySeriesOf())
            lateinit var recipientCid: borg.trikeshed.job.ContentId
            var receiptNode: borg.trikeshed.ccek.ArticulatedNode? = null
            val forwarded = request("local.publish.worker")
            recipientCid = binding.registerLcncWorkgroup(fanout, group("recipient", "local.publish"), recipient,
                mapOf("capture" to LcncNodeRunner { _, _ ->
                    val invocation = LcncWorkgroupKey.require()
                    assertEquals("recipient", invocation.a.name)
                    assertEquals(recipientCid, invocation.b.b)
                    assertSame(forwarded, invocation.b.a.nuid)
                    assertNotNull(currentCoroutineContext()[Host])
                    receiptNode = currentCoroutineContext()[borg.trikeshed.ccek.ArticulatedNode]
                    val frame = assertNotNull(currentCoroutineContext()[LcncScopeFrame])
                    val evidence = assertIs<Map<String, Any?>>(frame.binding("evidence"))
                    received += evidence
                    mapOf("evidence" to evidence)
                }))
            withContext(Host()) {
                fanout.runLcncWorkgroup(forwarded, mapOf("evidence" to gathered))
            }
            assertEquals(listOf(gathered), received)
            val node = assertNotNull(receiptNode)
            assertEquals(ElementState.CLOSED, node.lifecycleState)
            val events = node.recording().filterIsInstance<borg.trikeshed.ccek.ForgeSignal.AppendBlock>()
            assertEquals(listOf("started", "finished"), events.map { it.properties["state"] })
            assertTrue(events.all { it.properties["programCid"] == recipientCid.value &&
                it.properties["workgroup"] == "recipient" && it.properties["subnet"] == forwarded.subnet.toString() })
        }
    }

    @Test fun registrationFreezesGeometryAndPropagatesHandlerFailureWithoutACompletedAssembly() = runTest {
        fixture { binding, fanout ->
            val params = mutableMapOf("value" to "\"registered\"")
            val program = LcncProgram("frozen", s_[LcncNode("value", "json.value", params)], emptySeriesOf())
            binding.registerLcncWorkgroup(fanout, group("frozen"), program, PureNodes.registry { 0L })
            params["value"] = "\"mutated\""
            val result = fanout.runLcncWorkgroup(request())
            assertEquals("registered", result.b.b.nodeOutputs["value"]?.get("value"))
        }
        fixture { binding, fanout ->
            binding.registerLcncWorkgroup(fanout, group("failure"),
                LcncProgram("failure", s_[LcncNode("failure", "fail")], emptySeriesOf()),
                mapOf("fail" to LcncNodeRunner { _, _ -> error("assembly failed") }))
            val failure = assertFailsWith<IllegalStateException> { fanout.runLcncWorkgroup(request()) }
            assertEquals("assembly failed", failure.message)
        }
    }

    @Test fun unresolvedNamedConstructionRefusesRegistrationBeforeClaimAdmission() = runTest {
        fixture { binding, fanout ->
            val program = LcncProgram("unresolved", s_[
                LcncNode("ring", "scope", params = mapOf("program" to "external")),
            ], emptySeriesOf())
            val failure = assertFailsWith<IllegalArgumentException> {
                binding.registerLcncWorkgroup(fanout, group("unresolved"), program, emptyMap())
            }
            assertContains(failure.message.orEmpty(), "pinned program loader")
            assertNull(fanout.dispatch(request()).winner)
        }
    }

    @Test fun callerInvocationMetadataCannotReplaceTheActualSelectedWorkgroup() = runTest {
        fixture { binding, fanout ->
            val selected = group("selected")
            val request = request()
            val spoof = NuidInvocation(group("spoofed", "foreign") j NuidFanoutElement.Claim(-1, request, null))
            val program = LcncProgram("selected", s_[LcncNode("capture", "test.capture")], emptySeriesOf())
            val cid = binding.registerLcncWorkgroup(fanout, selected, program,
                mapOf("test.capture" to LcncNodeRunner { _, _ ->
                    val actual = NuidInvocation.require()
                    val lcnc = LcncWorkgroupKey.require()
                    assertSame(selected, actual.a)
                    assertTrue(actual.b.claimId > 0)
                    assertSame(request, actual.b.nuid)
                    assertSame(actual.a, lcnc.a)
                    assertSame(actual.b, lcnc.b.a)
                    assertNotNull(currentCoroutineContext()[Host])
                    mapOf("workgroup" to actual.a.name, "claimId" to actual.b.claimId)
                }))
            val result = withContext(Host() + spoof) {
                val out = fanout.runLcncWorkgroup(request)
                assertSame(spoof, currentCoroutineContext()[NuidInvocation], "Borrowed caller context remains caller-owned")
                out
            }
            assertEquals("selected", result.a.winner)
            assertEquals(cid, result.b.a)
            assertEquals("selected", result.b.b.nodeOutputs["capture"]?.get("workgroup"))
            assertEquals(result.a.claimId, result.b.b.nodeOutputs["capture"]?.get("claimId"))
        }
    }
}
