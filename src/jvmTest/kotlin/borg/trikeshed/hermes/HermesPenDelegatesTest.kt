package borg.trikeshed.hermes

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.Budget
import borg.trikeshed.graal.subvm.GraalBtrfsSupervisor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.htx.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.memory.SkillRegistry
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.BeliefIntake
import borg.trikeshed.narsese.Nal
import borg.trikeshed.ontology.zipper.PlaneAdapters
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.vm.Teleported
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import keymux.KeyMux
import keymux.TestKeySource
import modelmux.ModelMux
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Phase-7 gate: the pen's verbs exercised through a REAL GraalPy guest —
 * refusals as data, double landings (blackboard fact + tool-use belief),
 * evidence clamped, scribe confined, no delete verb.
 */
class HermesPenDelegatesTest {

    @Test
    fun providerClientPreservesTheCompletionAndBufferedToolChunks() = runBlocking {
        val sent = mutableListOf<HtxRequest>()
        val response = """{"id":"completion-1","model":"wire-fixture","created":123,"choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"reasoning_content":"reason µ","tool_calls":[{"id":"call-2","type":"function","function":{"name":"lookup","arguments":"{\"q\":\"µ 🦉\"}"}}]}}],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6},"vendor":{"retained":true}}"""
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                sent.add(request)
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request, response = HtxResponse(200, body = ByteSeries(response.encodeToByteArray()))))
            }
        })
        val mux = ModelMux(KeyMux { bind("llm.fixture.key", TestKeySource()) }) {
            model("fixture", caps = setOf("chat", "tools"), wireModel = "wire-fixture", baseUrl = "https://fixture.invalid/v1")
            defaultModel("fixture")
        }
        val board = ConfixBlackboard.empty()
        try {
            HermesPythonPort(blackboard = board, pen = HermesPen(board, modelMux = mux, muxContext = htx)).use { port ->
                val inventory = port.inventorySources(emptyMap())
                assertTrue(inventory.modules.getValue(HermesModelProvider.MODULE).sleeved)
                port.importInVm(inventory, HermesModelProvider.MODULE)
                assertEquals(Teleported.Bool(true), port.evalInVm("""
                    import trikeshed_modelmux as transport
                    catalog = transport._call('mux_catalog')
                    assert catalog['defaultModel'] == 'fixture'
                    assert catalog['models'] == [{'id': 'fixture', 'capabilities': ['chat', 'tools']}]
                    client = transport.ModelMuxClient(api_key='not-authority', base_url='https://not-a-destination.invalid')
                    assert client.api_key is None
                    reply = client.chat.completions.create(model='fixture', messages=[{'role':'user','content':'first'}],
                                                          timeout=1.5, extra_body={'reasoning_effort':'low'})
                    assert reply.choices[0].message.content is None
                    assert reply.choices[0].message.tool_calls[0].function.arguments == '{"q":"µ 🦉"}'
                    assert reply.usage.total_tokens == 6
                    assert reply.vendor.retained is True
                    assert reply.model_dump()['choices'][0]['message']['content'] is None
                    assert 'content' not in reply.model_dump(exclude_none=True)['choices'][0]['message']
                    chunks = client.chat.completions.create(model='fixture', messages=[{'role':'user','content':'second'}],
                                                           stream=True, stream_options={'include_usage':True})
                    assert chunks.buffered is True
                    with chunks:
                        data, usage = list(chunks)
                        assert data.choices[0].delta.tool_calls[0].index == 0
                        assert data.choices[0].delta.reasoning_content == 'reason µ'
                        assert data.choices[0].finish_reason == 'tool_calls'
                        assert data.model == 'wire-fixture' and data.vendor.retained is True
                        assert usage.choices == [] and usage.usage.total_tokens == 6
                    assert list(chunks) == []
                    for invalid in ({'extra_headers':{'Authorization':'guest'}}, {'timeout':0},
                                    {'extra_body':{'model':'foreign'}}, {'stream_options':{'unknown':True}}):
                        try:
                            client.chat.completions.create(model='fixture', messages=[], **invalid)
                        except ValueError:
                            pass
                        else:
                            raise AssertionError('invalid client options reached the host')
                    client.close()
                    try:
                        client.chat.completions.create(model='fixture', messages=[])
                    except RuntimeError:
                        pass
                    else:
                        raise AssertionError('closed client admitted work')
                    True
                """.trimIndent()))
            }
            assertEquals(2, sent.size)
            assertEquals(1500L, sent.first().timeoutMs)
            val first = confixDoc(sent.first().body.toArray().decodeToString())
            assertEquals("wire-fixture", first.value("model"))
            assertEquals("low", first.value("reasoning_effort"))
            assertEquals(null, first.getAt("timeout"))
            assertEquals(null, first.getAt("api_key"))
            val catalog = board.snapshot().values.filterKeys { it.startsWith("hermes/python/pen/mux_catalog/") }.values.single() as Map<*, *>
            assertEquals(true, catalog["ok"])
            assertFalse(catalog.containsKey("api_key"))
        } finally { htx.close() }
    }

    private fun guest(): GraalBtrfsSupervisor = GraalBtrfsSupervisor(
        id = "pen-test-${java.lang.System.nanoTime()}",
        facet = VmFacet.GRAAL_PYTHON,
        budget = Budget(statements = 0, wallMillis = 30_000, calls = 0),
    )

    private fun skillsFixture(): File {
        val root = File.createTempFile("pen-skills", "").let { f -> f.delete(); f.apply { mkdirs() } }
        val dir = File(root, "coding/kotlin-gradle").apply { mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: kotlin-gradle\ndescription: build kotlin with gradle\n---\nbody")
        return root
    }

    private suspend fun BeliefBagElement.settle() {
        var quiet = 0; var spins = 0
        while (spins++ < 400 && quiet < 3) { delay(10); if (intake.isEmpty) quiet++ else quiet = 0 }
        delay(25)
    }

    @Test
    fun guestConversationPreservesToolCallsAndTextUsage() = runBlocking {
        val sent = mutableListOf<String>()
        val response = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"answer","tool_calls":[{"id":"call-2","type":"function","function":{"name":"lookup","arguments":"{\"q\":\"µ 🦉\"}"}}]}}],"usage":{"prompt_tokens":4,"completion_tokens":2}}"""
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                sent.add(request.body.toArray().decodeToString())
                val reply = HtxResponse(200, body = ByteSeries(response.encodeToByteArray()))
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = reply))
            }
        })
        val mux = ModelMux(KeyMux { bind("llm.fixture.key", TestKeySource()) }) {
            model("fixture", caps = setOf("chat", "tools"), baseUrl = "https://fixture.invalid/v1")
        }
        val blackboard = ConfixBlackboard.empty()
        try {
            guest().use { g ->
                HermesPen(blackboard, modelMux = mux, muxContext = htx).install(g)
                val out = g.eval("""
                    import json
                    request = {
                        'messages': [
                            {'role': 'assistant', 'content': None, 'tool_calls': [{'id':'call-1','type':'function','function':{'name':'lookup','arguments':'{}'}}]},
                            {'role': 'tool', 'tool_call_id': 'call-1', 'content': 'µ 🦉'}
                        ],
                        'tools': [{'type':'function','function':{'name':'lookup','parameters':{'type':'object'}}}]
                    }
                    structured = json.loads(host.call('mux_completion', 'fixture', json.dumps(request)))
                    assert structured['verdict'] == 'ok', structured
                    text = json.loads(host.call('mux_converse', 'fixture', 'hello'))
                    (structured['verdict'], structured['completion']['choices'][0]['message']['tool_calls'][0]['id'],
                     structured['completion']['choices'][0]['finish_reason'], text['response'], text['usage']['prompt_tokens'])
                """.trimIndent(), "pen-tool-conversation.py") as Teleported.Arr
                assertEquals(listOf(Teleported.Str("ok"), Teleported.Str("call-2"), Teleported.Str("tool_calls"),
                    Teleported.Str("answer"), Teleported.Num(4)), out.v)
            }
            assertEquals(2, sent.size)
            val request = confixDoc(sent.first())
            assertEquals("call-1", request.value("messages", 1, "tool_call_id"))
            assertEquals("µ 🦉", request.value("messages", 1, "content"))
            assertEquals("lookup", request.value("tools", 0, "function", "name"))
            assertEquals(true, (blackboard.get("hermes/python/pen/mux_completion/1") as Map<*, *>)["ok"])
        } finally { htx.close() }
    }

    @Test
    fun refusalsAreVocabularyNotExceptions() = runBlocking {
        val blackboard = ConfixBlackboard.empty()
        val pen = HermesPen(blackboard) // no mux, no bag, no skills leased
        val g = guest()
        pen.install(g)
        val out = g.eval(
            "import json\n" +
                "r1 = json.loads(host.call('mux_converse', 'some-model', 'hello'))\n" +
                "r2 = json.loads(host.call('bag_recall', 'anything'))\n" +
                "r3 = json.loads(host.call('skill_scribe', 'delete', 'a', 'b'))\n" +
                "(r1['verdict'], r2['verdict'], r3['verdict'])",
            "pen-refusals.py",
        )
        val arr = out as Teleported.Arr
        assertEquals("no-mux", (arr.v[0] as Teleported.Str).v)
        assertEquals("no-bag", (arr.v[1] as Teleported.Str).v)
        assertEquals("no-scribe", (arr.v[2] as Teleported.Str).v)
        for (verb in listOf("mux_converse", "bag_recall", "skill_scribe")) {
            val key = blackboard.keys().single { it.startsWith("hermes/python/pen/$verb/") }
            val receipt = blackboard.get(key) as Map<*, *>
            assertEquals(false, receipt["ok"], "$verb was refused, not successful")
            assertEquals("unavailable", receipt["beliefAdmission"])
        }
        g.close()
    }

    @Test
    fun modelRefusalCarriesNegativeToolEvidence() = runBlocking {
        val blackboard = ConfixBlackboard.empty()
        val bag = BeliefBagElement(capacity = 16)
        bag.open()
        try {
            guest().use { g ->
                HermesPen(blackboard, bag = bag).install(g)
                g.eval("host.call('mux_converse', 'not-leased', 'hello')", "pen-refused-model.py")
            }
        } finally { bag.drain() }
        val evidence = bag.snapshot().values.single().evidence
        assertEquals(0L, evidence.positive, "a returned refusal must not teach success")
        assertEquals(Nal.UNIT, evidence.negative)
        val receipt = blackboard.get("hermes/python/pen/mux_converse/1") as Map<*, *>
        assertEquals(false, receipt["ok"])
        assertEquals("no-mux", receipt["verdict"])
        assertEquals("admitted", receipt["beliefAdmission"])
    }

    @Test
    fun rejectedAssertionAdmissionIsNotSuccess() = runBlocking {
        for (closed in listOf(false, true)) {
            val blackboard = ConfixBlackboard.empty()
            val bag = BeliefBagElement(capacity = 16)
            // No consumer: deterministically fill the existing bounded intake without races.
            if (closed) bag.intake.close()
            else repeat(256) { assertTrue(bag.intake.trySend(BeliefIntake.DecayTick).isSuccess) }
            try {
                guest().use { g ->
                    HermesPen(blackboard, bag = bag).install(g)
                    val result = g.eval("import json\njson.loads(host.call('bag_assert', 'subject', 'predicate', True))['verdict']", "pen-rejected-intake.py")
                    assertEquals(if (closed) "bag-closed" else "bag-full", (result as Teleported.Str).v)
                }
                val receipt = blackboard.get("hermes/python/pen/bag_assert/1") as Map<*, *>
                assertEquals(false, receipt["ok"])
                assertEquals(if (closed) "closed" else "full", receipt["beliefAdmission"])
            } finally {
                bag.intake.cancel()
                bag.supervisor.cancel()
            }
        }
    }

    @Test
    fun negativeClaimIsNotARefusedOperation() = runBlocking {
        val blackboard = ConfixBlackboard.empty()
        val bag = BeliefBagElement(capacity = 16)
        bag.open()
        try {
            guest().use { g ->
                HermesPen(blackboard, bag = bag).install(g)
                g.eval("host.call('bag_assert', 'subject', 'predicate', False)", "pen-negative-claim.py")
            }
        } finally { bag.drain() }
        val signals = bag.snapshot().values
        val claim = signals.single { it.subjectCid == ContentId.of("subject".encodeToByteArray()).value }
        val tool = signals.single { it.subjectCid == ContentId.of("graal-python:bag_assert".encodeToByteArray()).value }
        assertEquals(0L, claim.evidence.positive)
        assertEquals(Nal.UNIT, claim.evidence.negative)
        assertEquals(Nal.UNIT, tool.evidence.positive, "admitting negative evidence is still a successful admission")
        val receipt = blackboard.get("hermes/python/pen/bag_assert/1") as Map<*, *>
        assertEquals(true, receipt["ok"])
        assertEquals(true, receipt["admitted"])
    }

    @Test
    fun unsuccessfulArchiveDoesNotClaimSuccess() = runBlocking {
        val blackboard = ConfixBlackboard.empty()
        val root = skillsFixture()
        val skill = File(root, "coding/kotlin-gradle/SKILL.md")
        val original = skill.readText()
        val destination = File(root, "coding/kotlin-gradle/SKILL.md.archived").apply { mkdirs() }
        File(destination, "existing").writeText("retain")
        try {
            guest().use { g ->
                HermesPen(blackboard, skillsRoot = root).install(g)
                val result = g.eval("import json\njson.loads(host.call('skill_scribe', 'archive', 'coding', 'kotlin-gradle'))['verdict']", "pen-archive-failure.py")
                assertEquals("archive-failed", (result as Teleported.Str).v)
            }
            val receipt = blackboard.get("hermes/python/pen/skill_scribe/1") as Map<*, *>
            assertEquals(false, receipt["ok"])
            assertEquals(original, skill.readText())
            assertEquals("retain", File(destination, "existing").readText())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun everyCrossingLandsTwice() = runBlocking {
        val blackboard = ConfixBlackboard.empty()
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        val pen = HermesPen(blackboard, bag = bag)
        val g = guest()
        pen.install(g)
        val admitted = g.eval("import json\njson.loads(host.call('bag_assert', 'context kotlin build', 'kotlin-gradle', True))['admitted']", "pen-assert.py")
        assertEquals(true, (admitted as Teleported.Bool).v)
        bag.settle()
        // landing 1: the blackboard fact (Rete-visible)
        val landings = blackboard.keys().filter { k -> k.startsWith("hermes/python/pen/bag_assert/") }
        assertTrue(landings.isNotEmpty(), "pen crossing must land on the blackboard")
        // landing 2: the asserted belief AND the tool-use belief about the assertion itself
        assertTrue(bag.size >= 2, "expected asserted belief + tool-use belief, got ${bag.size}")
        // guest evidence clamp: every guest-minted belief carries at most ONE unit
        for ((_, s) in bag.snapshot()) {
            assertTrue(s.evidence.total <= borg.trikeshed.narsese.Nal.UNIT, "guest evidence must be clamped to unit, got ${s.evidence.total}")
        }
        bag.drain()
        g.close()
    }

    @Test
    fun crumbWalkReturnsPicksWithTrails() = runBlocking {
        val blackboard = ConfixBlackboard.empty()
        val registry = SkillRegistry(JvmFileOperations())
        val cards = registry.ingest(skillsFixture().absolutePath)
        assertEquals(1, cards.size)
        val pen = HermesPen(blackboard, registry = registry, cards = cards, planes = PlaneAdapters(lattice = registry.lattice()))
        val g = guest()
        pen.install(g)
        val out = g.eval(
            "import json\n" +
                "r = json.loads(host.call('crumb_walk', 'build kotlin with gradle tooling', 'coding', 3))\n" +
                "(r['verdict'], len(r['picks']), r['picks'][0]['name'] if r['picks'] else '')",
            "pen-walk.py",
        )
        val arr = out as Teleported.Arr
        assertEquals("ok", (arr.v[0] as Teleported.Str).v)
        assertTrue((arr.v[1] as Teleported.Num).v.toInt() >= 1)
        assertEquals("kotlin-gradle", (arr.v[2] as Teleported.Str).v)
        g.close()
    }

    @Test
    fun scribeIsConfinedAndHasNoDelete() = runBlocking {
        val blackboard = ConfixBlackboard.empty()
        val root = skillsFixture()
        val pen = HermesPen(blackboard, skillsRoot = root)
        val g = guest()
        pen.install(g)
        val out = g.eval(
            "import json\n" +
                "ok = json.loads(host.call('skill_scribe', 'create', 'coding', 'new-skill', '---\\nname: new-skill\\ndescription: d\\n---\\nbody'))\n" +
                "esc = json.loads(host.call('skill_scribe', 'create', '..', 'escape', 'x'))\n" +
                "de = json.loads(host.call('skill_scribe', 'delete', 'coding', 'new-skill'))\n" +
                "(ok['verdict'], esc['verdict'], de['verdict'])",
            "pen-scribe.py",
        )
        val arr = out as Teleported.Arr
        assertEquals("ok", (arr.v[0] as Teleported.Str).v)
        assertTrue((arr.v[1] as Teleported.Str).v in setOf("bad-name", "confined"), "escape must be refused")
        assertEquals("no-such-action", (arr.v[2] as Teleported.Str).v, "there is no delete verb")
        assertTrue(File(root, "coding/new-skill/SKILL.md").isFile)
        g.close()
    }
}
