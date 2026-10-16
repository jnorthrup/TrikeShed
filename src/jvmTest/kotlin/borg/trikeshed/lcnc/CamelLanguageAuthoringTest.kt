package borg.trikeshed.lcnc

import borg.trikeshed.graal.subvm.CamelRuntime
import borg.trikeshed.graal.subvm.VmLanguages
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.vm.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CamelLanguageAuthoringTest {
    @Test fun everyLanguageAuthorsTheExecutedEndpointAndHeaderWithoutRetainingGuests() = runBlocking<Unit> {
        val host = HypervisorVmHost()
        val running = CamelRuntime.running().map { it.id }
        try {
            val runners = PureNodes.registry { 0L }.toMutableMap()
            runners[SubVmLegos.POLYGLOT] = SubVmLegos.polyglot(host)
            runners[SubVmLegos.CAMEL] = SubVmLegos.camel(host)
            runners["display"] = LcncNodeRunner { _, inputs -> mapOf("rendered" to inputs["x"]) }
            for (language in VmLanguage.entries) {
                VmLanguages.require(host, language)
                val preset = LcncWorkspacePresets.camel(language)
                assertTrue(preset.controls.humanOversight)
                assertTrue(LcncTypeCheck.check(preset).isEmpty(), language.id)
                // The author, not vm.camel's fallback from/to parameters, chooses this endpoint.
                val input = mapOf("destination" to "mock:authored-${language.id}", "party" to "reviewer-雪")
                val program = preset.copy(nodes = preset.nodes α { node ->
                    if (node.id == "source") node.copy(params = mapOf("value" to jsonOf(input))) else node
                })
                val output = LcncRunner(runners).runProcedure(program, emptyMap()).nodeOutputs
                val definition = output.getValue("function").getValue("value")
                val routed = output.getValue("route")
                assertEquals(true, routed["ok"], language.id)
                assertEquals(input, reifyStrict(routed["routed"] as String), language.id)
                val receipt = assertIs<Map<*, *>>(routed["receipt"])
                assertEquals(definition, receipt["plan"], language.id)
                assertEquals("reviewer-雪", assertIs<Map<*, *>>(receipt["headers"])["ReviewParty"], language.id)
                assertEquals(receipt, output.getValue("receipt")["rendered"], language.id)
                assertEquals(running, CamelRuntime.running().map { it.id })
                assertTrue(host.ids().none { host.get(it)?.isAlive == true }, language.id)
            }
        } finally { host.close() }
    }

    @Test fun malformedOrUntrustedAuthoredRoutesCannotFallBackToFromTo() = runBlocking<Unit> {
        val host = HypervisorVmHost()
        try {
            val runner = SubVmLegos.camel(host)
            val node = LcncNode("refused", SubVmLegos.CAMEL,
                mapOf("from" to "direct:fallback", "to" to "mock:fallback"))
            for (route in listOf(null, mapOf("from" to "direct:x", "steps" to listOf(mapOf("toD" to "smtp:x"))))) {
                assertFailsWith<IllegalArgumentException> { runner.run(node, mapOf("route?" to route)) }
            }
            assertFailsWith<IllegalArgumentException> {
                runner.run(node.copy(params = node.params + ("route" to "null")), emptyMap())
            }
            val valid = mapOf("from" to "direct:x", "steps" to listOf(mapOf("to" to "mock:x")))
            assertFailsWith<IllegalArgumentException> {
                runner.run(node.copy(params = node.params + ("trust" to "UNTRUSTED")), mapOf("route?" to valid))
            }
            assertTrue(host.ids().isEmpty())
        } finally { host.close() }
    }

    @Test fun directConfixDefinitionExecutesChoiceWithoutClaimingGuestDepthSupport() = runBlocking<Unit> {
        val host = HypervisorVmHost()
        try {
            val source = """{"from":"direct:choice","steps":[{"choice":{"when":[{"bodyEquals":"missing","steps":[{"setBody":"request-evidence"}]}],"otherwise":[{"setBody":"review-evidence"}]}},{"to":"mock:review"}]}"""
            val runner = SubVmLegos.camel(host)
            for ((body, expected) in listOf("missing" to "request-evidence", "present" to "review-evidence")) {
                val out = runner.run(LcncNode("choice", SubVmLegos.CAMEL, mapOf("route" to source, "body" to body)), emptyMap())
                assertEquals(expected, out["routed"])
            }
            assertTrue(host.ids().isEmpty())
        } finally { host.close() }
    }
}
