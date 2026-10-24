package borg.trikeshed.agent.modelmux

import borg.trikeshed.agent.keymux.*
import borg.trikeshed.context.nuid.*
import borg.trikeshed.lib.*
import borg.trikeshed.htx.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.*
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessResult
import kotlin.test.Test

/** Mission -> NUID dispatch -> awaited result: receipts, acceptance gating, contracts, journaling, host-side model work and real Bash. */
class MissionExecutionTest {
    @Test fun missionDispatchReceiptsAcceptanceAndRealBash() {
        runBlocking {
            val fanout = NuidFanoutElement()
            fanout.open()
            val capability = Capability.Process("mission-test")
            val address = nuid(capability, Nonce.RandomBytes(), Subnet.core)
            val order = mutableListOf<String>()
            fanout.register(Workgroup("worker", Subnet.core, TraitSpace { s_[capability] })) { claim ->
                val value = claim.payload as String
                yield()
                order.add(value)
                check(value != "fail") { "worker failure" }
                "completed:$value"
            }
            try {
                val modelPlan = bashMissionPlan("""{"tasks":[{"id":"infer","kind":"model","model":"Beam-1.0-501B-A23B","request":{"max_tokens":512,"messages":[{"role":"user","content":"proof"}]}}]}""", address, address)
                check(modelPlan[0].payload is ModelWork)
                check((modelPlan[0].payload as ModelWork).request.src.toArray().decodeToString().contains("\"max_tokens\":512,"))
                val planned = bashMissionPlan("""{"tasks":[{"id":"one","args":["-c","printf one"]},{"id":"two","depends_on":["one"],"args":["-c","printf two"]}]}""", address)
                check(planned.size == 2 && planned[1].dependencies[0] == "one")
                check(runCatching { bashMissionPlan("""{"tasks":[{"id":"bad","args":"eval me"}]}""", address) }.isFailure)
                val tasks = s_[
                    MissionTask("second", address, s_["first"], "second"),
                    MissionTask("first", address, emptySeriesOf(), "first"),
                ]
                val events = mutableListOf<MissionEvent>()
                val results = executeMission(fanout, tasks, observe = { events.add(it) })
                check(events.map { it.phase } == listOf(MissionPhase.ADMITTED, MissionPhase.COMPLETED,
                    MissionPhase.ADMITTED, MissionPhase.COMPLETED))
                check(events.all { it.worker == "worker" && it.claimId > 0 })
                check(order == listOf("first", "second"))
                check(results.size == 2)
                check(results[1].b == "completed:second")
                val before = order.size
                val cyclic = s_[MissionTask("cycle", address, s_["cycle"], "must-not-run")]
                check(runCatching { executeMission(fanout, cyclic) }.isFailure)
                check(order.size == before)
                val invalid = s_[MissionTask("missing", address, s_["absent"], "must-not-run")]
                check(runCatching { executeMission(fanout, invalid) }.isFailure)
                events.clear()
                val failure = runCatching { executeMission(fanout, s_[
                    MissionTask("failure", address, emptySeriesOf(), "fail"),
                    MissionTask("blocked", address, s_["failure"], "must-not-run"),
                ], observe = { events.add(it) }) }
                check(failure.isFailure)
                check("must-not-run" !in order)
                check(events.map { it.phase } == listOf(MissionPhase.ADMITTED, MissionPhase.FAILED))
                events.clear()
                val validation = runCatching { executeMission(fanout, s_[
                    MissionTask("reject", address, emptySeriesOf(), "first", accept = { false }),
                    MissionTask("blocked-validation", address, s_["reject"], "must-not-run"),
                ], observe = { events.add(it) }) }
                check(validation.isFailure)
                check(events.map { it.phase } == listOf(MissionPhase.ADMITTED, MissionPhase.REJECTED))
                check("must-not-run" !in order)
                val contractTasks = s_[
                    MissionTask("implement", address, payload = "implement", role = MissionRole.WORK, targets = s_["OUTCOME"]),
                    MissionTask("validate", address, s_["implement"], "validate", role = MissionRole.VALIDATE, targets = s_["OUTCOME"]),
                    MissionTask("seal", address, s_["validate"], "seal", role = MissionRole.GATE, targets = s_["OUTCOME"]),
                ]
                val contractEvents = mutableListOf<MissionEvent>()
                executeMission(fanout, contractTasks, assertions = s_["OUTCOME"], observe = { contractEvents.add(it) })
                check(contractEvents.last().role == MissionRole.GATE)
                check(contractEvents.last().targets[0] == "OUTCOME")
                check(missionAssertionState(contractEvents.toSeries())[0].b == AssertionPhase.PASSED)
                val failedReceipt = MissionEvent("validate", MissionPhase.FAILED, 99, "worker", MissionRole.VALIDATE, s_["OUTCOME"])
                check(missionAssertionState((contractEvents + failedReceipt).toSeries())[0].b == AssertionPhase.FAILED)
                check(runCatching { missionAssertionState(s_[contractEvents.last()]) }.isFailure)
                val priorContractEffects = order.size
                val bypass = s_[contractTasks[0], contractTasks[1],
                    MissionTask("bypass", address, s_["implement"], "must-not-run", role = MissionRole.GATE, targets = s_["OUTCOME"])]
                check(runCatching { executeMission(fanout, bypass, assertions = s_["OUTCOME"]) }.isFailure)
                check(order.size == priorContractEffects)
                val mux = ModelMux(KeyMux {}) {
                    model("credential-required", setOf("chat"), baseUrl = "https://example.invalid/v1", provider = "proof")
                }
                val modelCapability = Capability.Process("model-completion")
                fanout.register(Workgroup("model", Subnet.core, TraitSpace { s_[modelCapability] })) { claim ->
                    executeModelWork(mux, claim.payload as ModelWork)
                }
                events.clear()
                val missingCredential = runCatching { executeMission(fanout, s_[MissionTask("model-key-required",
                    nuid(modelCapability, Nonce.RandomBytes(), Subnet.core), payload = ModelWork("credential-required",
                        borg.trikeshed.parse.confix.confixDoc("""{"messages":[{"role":"user","content":"proof"}]}""")))],
                    observe = { events.add(it) }) }
                check(missingCredential.isFailure)
                check(missingCredential.exceptionOrNull()?.message?.contains("key", ignoreCase = true) == true) {
                    "Expected KeyMux credential failure, got ${missingCredential.exceptionOrNull()}"
                }
                check(events.map { it.phase } == listOf(MissionPhase.ADMITTED, MissionPhase.FAILED))
                fanout.unregister("model")
                var authenticated = false
                val transport = openHtxElement(routeService = object : HtxRouteService {
                    override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                        authenticated = request.headers.view.any { it.a.equals("Authorization", ignoreCase = true) &&
                            it.b == "Bearer offline-test-key" }
                        val response = HtxResponse(200, ByteSeries("""{"id":"fixture","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"fixture-result"}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""".encodeToByteArray()))
                        return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                            request = request, response = response))
                    }
                })
                val reactor = MuxReactorElement()
                val authenticatedMux = ModelMux(KeyMux {
                    bind("llm.proof.key", TestKeySource(value = "offline-test-key"))
                }) {
                    model("fixture", setOf("chat"), baseUrl = "https://example.invalid/v1", provider = "proof")
                }
                try {
                    fanout.register(Workgroup("model", Subnet.core, TraitSpace { s_[modelCapability] })) { claim ->
                        withContext(transport + reactor) { executeModelWork(authenticatedMux, claim.payload as ModelWork) }
                    }
                    val response = executeMission(fanout, s_[MissionTask("model-fixture-success",
                        nuid(modelCapability, Nonce.RandomBytes(), Subnet.core), payload = ModelWork("fixture",
                            borg.trikeshed.parse.confix.confixDoc("""{"messages":[{"role":"user","content":"proof"}]}""")))])
                    val document = response[0].b as borg.trikeshed.parse.confix.ConfixDoc
                    check(document.src.toArray().decodeToString().contains("fixture-result"))
                    check(authenticated)
                } finally {
                    fanout.unregister("model")
                    try { reactor.close() } finally { transport.close() }
                }
                val nio = NioSupervisor()
                nio.open()
                try {
                    val files = checkNotNull(nio.service<borg.trikeshed.userspace.nio.file.spi.FileOperations>())
                    val directory = files.createTempDir("mission-journal-proof")
                    try {
                        val path = "$directory/events.jsonl"
                        val journal = MissionJournal(files, path)
                        journal.record(MissionEvent("proof", MissionPhase.COMPLETED, 1, "bash"))
                        journal.close()
                        check(files.readString(path).contains("COMPLETED"))
                        check(runCatching { MissionJournal(files, path) }.isFailure)
                    } finally { files.deleteRecursively(directory) }
                    val operations = checkNotNull(nio.service<ProcessOperations>())
                    val recursionCapability = Capability.Process("harness-refine")
                    val recursionAddress = nuid(recursionCapability, Nonce.RandomBytes(), Subnet.core)
                    val recursionWorker = Workgroup("harness-refine", Subnet.core, TraitSpace { s_[recursionCapability] })
                    var executions = 0
                    val step = contrastiveHarnessStep(fanout, recursionWorker, recursionAddress,
                        HarnessRevision("baseline", s_["bash" j "printf baseline"]),
                        execute = { revision ->
                            val process = withContext(operations) { executeBashWork(BashWork(s_["-c", revision.components[0].b])) }
                            check(process.exitCode == 0)
                            HarnessObservation("observation-${++executions}", revision.id, "bash-fixture/no-llm", "fixed-test",
                                process.stdout.decodeToString())
                        },
                        propose = { scope, reference ->
                            check(reference.deliverable == "baseline")
                            val name = scope.name.lowercase()
                            HarnessRevision(name, s_["bash" j "printf $name"])
                        },
                        compose = { reference, probes ->
                            check(probes[0].b.deliverable == "invariant" && probes[1].b.deliverable == "domain")
                            HarnessComposition(HarnessRevision("composed", probes[1].a.components),
                                s_[reference.id, probes[1].b.id])
                        })
                    check(executions == 4 && step.successor.deliverable == "domain")
                    check(fanout.slotOf("harness-refine") == null)
                    fanout.register(Workgroup("bash", Subnet.core,
                        TraitSpace { s_[Capability.Process("bash")] })) { claim ->
                        withContext(operations) { executeBashWork(claim.payload as BashWork) }
                    }
                    val bashAddress = nuid(Capability.Process("bash"), Nonce.RandomBytes(), Subnet.core)
                    val processResults = executeMission(fanout, s_[MissionTask("bash-output", bashAddress,
                        payload = BashWork(s_["-c", "printf '%s' \"\u0024{1}\"", "proof", "literal;not-evaluated"]),
                        accept = { result -> (result as ProcessResult).exitCode == 0 })])
                    check((processResults[0].b as ProcessResult).stdout.decodeToString() == "literal;not-evaluated")
                } finally { nio.drain(); nio.close() }
                check(borg.trikeshed.mux.MissionCli.execute(s_["-c", "true"]) == 0)
            } finally {
                fanout.drain()
                fanout.close()
            }
        }
    }
}
