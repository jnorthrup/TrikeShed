package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.graal.subvm.CamelSource
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.vm.HypervisorVmHost
import borg.trikeshed.vm.VmLanguage
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CamelGroovyDslTest {
    @Test fun groovyClosuresConfigureRealCamelRoutesWithTypedInput() = runTest {
        HypervisorVmHost().use { host ->
            val source = """
                from(entry).routeId(id).process { exchange ->
                    exchange.message.setHeader('ReviewParty', input.party)
                    exchange.message.setHeader('Destination', input.destination)
                    exchange.message.setHeader('Review', [confirmed: false, confidence: 1.25d])
                }.setBody(simple('${'$'}{header.ReviewParty}: request-evidence'))
                    .toD('${'$'}{header.Destination}')
            """.trimIndent()
            val result = SubVmLegos.camel(host).run(node(source), mapOf("messages" to mapOf(
                "party" to "reviewer-医", "destination" to "mock:groovy-review")))
            assertEquals("reviewer-医: request-evidence", result["routed"])
            val receipt = assertIs<Map<*, *>>(result["receipt"])
            assertEquals("groovy", receipt["language"])
            assertEquals("native-dsl", receipt["authoring"])
            assertEquals(ContentId.of(source.encodeToByteArray()).value, receipt["sourceCid"])
            assertEquals(mapOf("confirmed" to false, "confidence" to 1.25),
                assertIs<Map<*, *>>(receipt["headers"])["Review"])
            assertTrue(host.ids().isEmpty())
        }
    }

    @Test fun malformedSourceAndHostClasspathAccessDoNotFallBack() = runTest {
        HypervisorVmHost().use { host ->
            val runner = SubVmLegos.camel(host)
            for (source in listOf("from(entry).process {", "new borg.trikeshed.job.ContentId('not-shared')")) {
                val message = messages(assertFails { runner.run(node(source), emptyMap()) })
                assertTrue(message.contains(".groovy"), message)
                assertTrue(message.contains("configure body starts at line"), message)
            }
            for (source in listOf("", " \n ")) {
                assertTrue(messages(assertFails { runner.run(node(source), emptyMap()) }).contains("nonblank"))
            }
            assertFalse(CamelSource.runCatching { checkAvailable(VmLanguage.GROOVY, "absent-native-module") }.isSuccess)
        }
    }

    @Test fun nativeGroovyRequiresOwnTrustAndRejectsUnsupportedLifetimeAndLimits() = runTest {
        HypervisorVmHost().use { host ->
            val runner = SubVmLegos.camel(host)
            val source = "from(entry).to('mock:groovy-gates')"
            for ((params, expected) in listOf(
                mapOf("trust" to "UNTRUSTED") to "OWN",
                mapOf("route" to "{\"from\":\"direct:x\",\"steps\":[]}") to "both",
                mapOf("language" to "python") to "Java and Groovy",
                mapOf("keep" to "true") to "one context",
                mapOf("world" to "/tmp") to "one context",
                mapOf("vmId" to "retained") to "one context",
            ) + listOf("wallMillis", "statements", "calls").map { mapOf(it to "1") to "no statement" }) {
                val failure = assertFails { runner.run(node(source, params), emptyMap()) }
                assertTrue(messages(failure).contains(expected), messages(failure))
            }
            assertTrue(messages(assertFails { runner.run(node(source), mapOf("route?" to emptyMap<String, Any?>())) }).contains("both"))
            assertTrue(host.ids().isEmpty())
        }
    }

    @Test fun staticDynamicHandledAndLateRefusalsRemainFailures() = runTest {
        HypervisorVmHost().use { host ->
            for (source in listOf(
                "from(entry).choice().when(constant(false)).to('smtp:invalid.example').end()",
                "from(entry).setHeader('destination', constant('smtp:invalid.example')).toD('${'$'}{header.destination}')",
                "from(entry).doTry().toD('smtp:invalid.example').doCatch(Exception).setBody(constant('masked')).end()",
                "from(entry).wireTap('direct:late-groovy').setBody(constant('main returned')); from('direct:late-groovy').delay(200).toD('smtp:invalid.example')",
            )) {
                val failure = assertFails { SubVmLegos.camel(host).run(node(source), emptyMap()) }
                assertTrue(messages(failure).contains("not mounted"), messages(failure))
            }
        }
    }

    @Test fun inputsAreCopiedAndOpaqueDecimalsAreNotSilentlyDemoted() = runTest {
        HypervisorVmHost().use { host ->
            val input = mutableMapOf("party" to "original")
            val output = SubVmLegos.camel(host).run(node("""
                input.party = 'changed'
                from(entry).process { exchange -> exchange.message.body = input.party }
            """.trimIndent()), mapOf("messages" to input))
            assertEquals("changed", output["routed"])
            assertEquals("original", input["party"])
            val failure = assertFails { SubVmLegos.camel(host).run(node("""
                from(entry).process { exchange -> exchange.message.setHeader('decimal', 1.25) }
            """.trimIndent()), emptyMap()) }
            assertTrue(messages(failure).contains("Confix data"), messages(failure))
        }
    }

    @Test fun groovyMetaclassChangesDoNotSurviveTheInvocation() = runTest {
        HypervisorVmHost().use { host ->
            SubVmLegos.camel(host).run(node("""
                String.metaClass.nativeCamelReview = { -> 'first invocation' }
                from(entry).setBody(constant('x'.nativeCamelReview()))
            """.trimIndent()), emptyMap()).also { assertEquals("first invocation", it["routed"]) }
            SubVmLegos.camel(host).run(node("""
                if (!String.metaClass.respondsTo('x', 'nativeCamelReview').isEmpty()) throw new IllegalStateException('metaclass leaked')
                from(entry).setBody(constant('fresh runtime'))
            """.trimIndent()), emptyMap()).also { assertEquals("fresh runtime", it["routed"]) }
        }
    }

    @Test fun nativeGroovyPresetExecutesBothBranchesAndRetainsExactSource() = runTest {
        HypervisorVmHost().use { host ->
            val runners = PureNodes.registry { 0L }.toMutableMap()
            runners["vm.camel"] = SubVmLegos.camel(host)
            runners["display"] = TribunalNodes.registry(TribunalDialog { _, _, _ -> error("No model call expected") }).getValue("display")
            val preset = LcncWorkspacePresets.camelNative(VmLanguage.GROOVY)
            assertTrue(preset.controls.humanOversight)
            assertTrue(LcncTypeCheck.check(preset).isEmpty())
            for ((state, expected) in listOf("missing" to "request-evidence", "present" to "review-evidence")) {
                val program = preset.copy(nodes = preset.nodes α { node ->
                    if (node.id == "source") node.copy(params = mapOf("value" to jsonOf(mapOf(
                        "party" to "party-雪", "status" to state, "destination" to "mock:groovy-preset")))) else node
                })
                val output = LcncRunner(runners).runProcedure(program, emptyMap()).nodeOutputs
                assertEquals("party-雪: $expected", output.getValue("route")["routed"])
                val receipt = assertIs<Map<*, *>>(output.getValue("receipt")["x"])
                assertEquals("groovy", receipt["language"])
                assertEquals("native-dsl", receipt["authoring"])
                assertEquals(ContentId.of(preset.nodes.view.single { it.id == "route" }.params.getValue("source")
                    .encodeToByteArray()).value, receipt["sourceCid"])
            }
        }
    }

    private fun node(source: String, extra: Map<String, String> = emptyMap()) = LcncNode("groovy-review", "vm.camel",
        mapOf("authoring" to "native-dsl", "source" to source, "language" to "groovy", "trust" to "OWN",
            "from" to "direct:groovy-review") + extra)

    private fun messages(failure: Throwable): String =
        generateSequence(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
}
