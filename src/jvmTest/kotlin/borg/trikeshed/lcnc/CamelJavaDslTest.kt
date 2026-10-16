package borg.trikeshed.lcnc

import borg.trikeshed.vm.HypervisorVmHost
import borg.trikeshed.vm.VmLanguage
import borg.trikeshed.graal.subvm.CamelRuntime
import borg.trikeshed.graal.subvm.GuestModules
import java.lang.reflect.Proxy
import java.lang.System
import java.util.concurrent.CopyOnWriteArrayList
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.job.ContentId
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Native Camel processors, expressions and dynamic endpoints, through the ordinary panel node. */
class CamelJavaDslTest {
    @Test fun javaDslUsesBoundInputAndActualCamelExpressions() = runTest {
        HypervisorVmHost().use { host ->
            val source = """
                Map<?, ?> review = (Map<?, ?>) input;
                from(entry).routeId(id)
                    .process(exchange -> {
                        exchange.getMessage().setHeader("ReviewParty", review.get("party"));
                        exchange.getMessage().setHeader("Destination", review.get("destination"));
                    })
                    .setBody(simple("${'$'}{header.ReviewParty}: request-evidence"))
                    .toD("${'$'}{header.Destination}");
            """.trimIndent()
            val output = SubVmLegos.camel(host).run(node(source), mapOf("messages" to mapOf(
                "party" to "reviewer-医", "destination" to "mock:native-review")))
            assertEquals("reviewer-医: request-evidence", output["routed"])
            val receipt = assertIs<Map<*, *>>(output["receipt"])
            assertEquals("java", receipt["language"])
            assertEquals("native-dsl", receipt["authoring"])
            assertEquals("reviewer-医", assertIs<Map<*, *>>(receipt["headers"])["ReviewParty"])
            assertTrue(host.ids().isEmpty(), "One-shot native routes must not retain a VM")
        }
    }

    @Test fun malformedSourceDoesNotFallBackToTheFromToRoute() = runTest {
        HypervisorVmHost().use { host ->
            val failure = assertFails { SubVmLegos.camel(host).run(node("not valid java"), emptyMap()) }
            assertTrue(messages(failure).contains(".java:1:"), messages(failure))
            for (source in listOf("", " \n ")) {
                assertTrue(messages(assertFails { SubVmLegos.camel(host).run(node(source), emptyMap()) }).contains("nonblank"))
            }
        }
    }

    @Test fun nativeHeadersCrossAsConfixValuesAndRejectOpaqueObjects() = runTest {
        HypervisorVmHost().use { host ->
            val runner = SubVmLegos.camel(host)
            val output = runner.run(node("""
                from(entry).process(exchange -> exchange.getMessage().setHeader("review",
                    Map.of("confirmed", false, "confidence", 1.25))).to("mock:typed");
            """.trimIndent()), emptyMap())
            val headers = assertIs<Map<*, *>>(assertIs<Map<*, *>>(output["receipt"])["headers"])
            assertEquals(mapOf("confirmed" to false, "confidence" to 1.25), headers["review"])
            val failure = assertFailsWith<IllegalArgumentException> {
                runner.run(node("""
                    from(entry).process(exchange -> exchange.getMessage().setHeader("review",
                        new Object() { public String toString() { return "not evidence"; } })).to("mock:opaque");
                """.trimIndent()), emptyMap())
            }
            assertTrue(messages(failure).contains("Confix data"), messages(failure))
        }
    }

    @Test fun nativeSourceRequiresOwnTrustAndCannotCompeteWithAPlan() = runTest {
        HypervisorVmHost().use { host ->
            val runner = SubVmLegos.camel(host)
            val untrusted = node("not valid java", mapOf("trust" to "UNTRUSTED"))
            assertTrue(messages(assertFails { runner.run(untrusted, emptyMap()) }).contains("OWN"))
            val ambiguous = node("from(entry).to(\"mock:ok\");", mapOf("route" to "{\"from\":\"direct:x\",\"steps\":[]}"))
            assertTrue(messages(assertFails { runner.run(ambiguous, emptyMap()) }).contains("both"))
            for (limit in listOf("wallMillis", "statements", "calls")) {
                assertFailsWith<IllegalArgumentException> {
                    runner.run(node("from(entry).to(\"mock:ok\");", mapOf(limit to "1")), emptyMap())
                }
            }
        }
    }

    @Test fun nativeReviewPresetExecutesBothWorkflowBranchesWithItsSourceReceipt() = runTest {
        HypervisorVmHost().use { host ->
            val runners = PureNodes.registry { 0L }.toMutableMap()
            runners["vm.camel"] = SubVmLegos.camel(host)
            runners["display"] = TribunalNodes.registry(TribunalDialog { _, _, _ -> error("No model call expected") }).getValue("display")
            val preset = LcncWorkspacePresets.camelJava()
            assertTrue(preset.controls.humanOversight)
            assertTrue(LcncTypeCheck.check(preset).isEmpty())
            for ((state, expected) in listOf("missing" to "request-evidence", "present" to "review-evidence")) {
                val program = preset.copy(nodes = preset.nodes α { node ->
                    if (node.id == "source") node.copy(params = mapOf("value" to jsonOf(mapOf(
                        "party" to "party-雪", "status" to state, "destination" to "mock:native-preset")))) else node
                })
                val output = LcncRunner(runners).runProcedure(program, emptyMap()).nodeOutputs
                assertEquals("party-雪: $expected", output.getValue("route")["routed"])
                val receipt = assertIs<Map<*, *>>(output.getValue("receipt")["x"])
                assertEquals("native-dsl", receipt["authoring"])
                val source = preset.nodes.view.single { it.id == "route" }.params.getValue("source")
                assertEquals(ContentId.of(source.encodeToByteArray()).value, receipt["sourceCid"])
            }
        }
    }

    @Test fun staticUnreachableAndDynamicUnprovisionedEndpointsAreRefused() = runTest {
        HypervisorVmHost().use { host ->
            for (source in listOf(
                "from(entry).choice().when(constant(false)).to(\"smtp:invalid.example\").end();",
                "from(entry).setHeader(\"destination\", constant(\"smtp:invalid.example\")).toD(\"\${header.destination}\");",
            )) {
                val failure = assertFails { SubVmLegos.camel(host).run(node(source), emptyMap()) }
                assertTrue(messages(failure).contains("not mounted"), messages(failure))
            }
        }
    }

    @Test fun aNativeErrorHandlerCannotTurnPolicyRefusalIntoSuccess() = runTest {
        HypervisorVmHost().use { host ->
            val source = """
                from(entry).setHeader("destination", constant("smtp:invalid.example"))
                    .doTry().toD("${'$'}{header.destination}")
                    .doCatch(Exception.class).setBody(constant("masked")).end();
            """.trimIndent()
            val failure = assertFails { SubVmLegos.camel(host).run(node(source), emptyMap()) }
            assertTrue(messages(failure).contains("not mounted"), messages(failure))
        }
    }

    @Test fun refusalDuringCamelOwnedShutdownCannotReturnASuccessReceipt() = runTest {
        HypervisorVmHost().use { host ->
            val source = """
                from(entry).wireTap("direct:late-native").setBody(constant("main returned"));
                from("direct:late-native").delay(200).toD("smtp:invalid.example");
            """.trimIndent()
            val failure = assertFails { SubVmLegos.camel(host).run(node(source), emptyMap()) }
            assertTrue(messages(failure).contains("not mounted"), messages(failure))
        }
    }

    @Test fun actualNativeContextsStopOnSuccessConfigurationFailureAndProcessorFailure() {
        val loader = assertNotNull(GuestModules.loaderFor("camel"))
        val trackerClass = loader.loadClass("org.apache.camel.spi.CamelContextTracker")
        val filterClass = loader.loadClass("org.apache.camel.spi.CamelContextTracker\$Filter")
        val observed = CopyOnWriteArrayList<Any>()
        val thread = Thread.currentThread()
        val previousLoader = thread.contextClassLoader
        val filter = Proxy.newProxyInstance(loader, arrayOf(filterClass)) { proxy, method, args ->
            when (method.name) {
                "accept", "test" -> {
                    if (Thread.currentThread() === thread) args?.firstOrNull()?.let { context ->
                        if (observed.none { it === context }) observed += context
                    }
                    false
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "CamelJavaDslTest.Filter"
                else -> null
            }
        }
        val tracker = trackerClass.getConstructor(filterClass).newInstance(filter)
        trackerClass.getMethod("open").invoke(tracker)
        try {
            for (language in listOf(VmLanguage.JAVA, VmLanguage.GROOVY)) {
            observed.clear()
            for ((index, source) in listOf(
                "from(entry).to(\"mock:native-cleanup\");",
                "throw new IllegalStateException(\"configuration refused\");",
                if (language == VmLanguage.JAVA) "from(entry).process(exchange -> { throw new IllegalStateException(\"processor refused\"); });"
                else "from(entry).process { exchange -> throw new IllegalStateException('processor refused') }",
                "from(\"direct:not-the-entry\").to(\"mock:native-cleanup\");",
                if (language == VmLanguage.JAVA) "from(entry).process(exchange -> exchange.getMessage().setHeader(\"opaque\", new Object()));"
                else "from(entry).process { exchange -> exchange.message.setHeader('opaque', new Object()) }",
            ).withIndex()) {
                val result = runCatching { CamelRuntime.executeNative(language, "native-cleanup", source, "direct:native-cleanup", null,
                    CamelRuntime.Request("body")) }
                assertEquals(index == 0, result.isSuccess, result.exceptionOrNull()?.let(::messages))
                assertSame(previousLoader, thread.contextClassLoader)
                assertEquals(index + 1, observed.size)
                assertEquals("Stopped", observed.last().javaClass.getMethod("getStatus").invoke(observed.last()).toString())
            }
            assertFalse(CamelRuntime.isRunning("native-cleanup"))
            }
        } finally {
            trackerClass.getMethod("close").invoke(tracker)
            observed.forEach { context -> runCatching { context.javaClass.getMethod("stop").invoke(context) } }
        }
    }

    private fun node(source: String, extra: Map<String, String> = emptyMap()) = LcncNode("native-review", "vm.camel",
        mapOf("authoring" to "native-dsl", "source" to source, "language" to "java", "from" to "direct:native-review",
            "to" to "mock:ignored", "body" to "input", "trust" to "OWN") + extra)

    private fun messages(failure: Throwable): String =
        generateSequence(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
}
