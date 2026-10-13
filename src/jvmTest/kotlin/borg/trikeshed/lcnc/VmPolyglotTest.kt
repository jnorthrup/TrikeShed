package borg.trikeshed.lcnc

import borg.trikeshed.graal.subvm.VmLanguages
import borg.trikeshed.graal.subvm.GuestException
import borg.trikeshed.graal.subvm.GuestFailure
import borg.trikeshed.graal.subvm.InProcessIsolate
import borg.trikeshed.graal.subvm.RootObservation
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reify
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.vm.*
import java.lang.System
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class VmPolyglotTest {
    @Test fun languageRouteConstructionsPreservePartyReportsAndRequireHumanReview() = runBlocking<Unit> {
        val host = HypervisorVmHost()
        try {
            val runners = PureNodes.registry { 0L }.toMutableMap()
            runners[SubVmLegos.POLYGLOT] = SubVmLegos.polyglot(host)
            runners[SubVmLegos.CAMEL] = SubVmLegos.camel(host)
            runners["display"] = LcncNodeRunner { _, inputs -> mapOf("rendered" to inputs["x"]) }
            val input = mapOf("case" to "synthetic-review-雪", "approved" to false,
                "reports" to listOf(mapOf("party" to "A", "claim" to "appendix missing"),
                    mapOf("party" to "B", "claim" to "appendix delivered")), "evidence" to null)
            val expected = input + ("review" to "human-required")
            for (language in VmLanguage.entries) {
                VmLanguages.require(host, language)
                val preset = LcncWorkspacePresets.polyglot(language)
                assertTrue(LcncTypeCheck.check(preset).isEmpty(), language.id)
                assertTrue(preset.controls.humanOversight)
                val program = preset.copy(nodes = preset.nodes α { node ->
                    if (node.id == "source") node.copy(params = mapOf("value" to jsonOf(input))) else node
                })
                val result = LcncRunner(runners).runProcedure(program, emptyMap())
                assertEquals(expected, result.nodeOutputs.getValue("function")["value"], language.id)
                val route = result.nodeOutputs.getValue("route")
                assertEquals(true, route["ok"], language.id)
                assertEquals(expected, reify(route["routed"] as String), language.id)
                assertEquals(expected, result.nodeOutputs.getValue("value")["rendered"], language.id)
                assertTrue(host.ids().none { host.get(it)?.isAlive == true }, language.id)
            }
        } finally { host.close() }
    }

    @Test fun languageDrawerParametersOverrideGenericNodeLimitsAndExecute() = runBlocking {
        val host = HypervisorVmHost()
        try {
            val defaults = LcncContracts.all().first { it.type == SubVmLegos.POLYGLOT }.params.mapValues { it.value.v }
            for (language in VmLanguage.entries) {
                val capability = VmLanguages.require(host, language).toMap()
                val params = assertNotNull(capability["params"] as? Map<*, *>, "${language.id} drawer parameters")
                    .entries.associate { it.key.toString() to it.value.toString() }
                val node = LcncNode("drawer-${language.id}", SubVmLegos.POLYGLOT, defaults + params)
                val input = mapOf("score" to 1.25, "reviewed" to false)
                assertEquals(input, SubVmLegos.polyglot(host).run(node, mapOf("input" to input))["value"])
            }
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
        } finally { host.close() }
    }

    @Test fun clojureCompilesTypedFunctionsWithInvocationLocalNamespacesAndCamelOutput() = runBlocking {
        val host = HypervisorVmHost()
        val previous = Thread.currentThread().contextClassLoader
        try {
            assertTrue(VmLanguages.require(host, VmLanguage.CLOJURE).available)
            val runner = SubVmLegos.polyglot(host)
            val input = mapOf("text" to "quote'\"\\\n雪", "fraction" to 1.25, "empty" to null)
            val node = LcncNode("clojure-source", SubVmLegos.POLYGLOT,
                mapOf("language" to "clojure", "source" to "input", "outputKind" to "OBJECT"))
            assertEquals(input, runner.run(node, mapOf("input" to input))["value"])
            val output = runner.run(node.copy(params = node.params + ("source" to
                "{\"caseId\" (get input \"text\") \"review\" (* 2 (get input \"fraction\")) \"accepted\" false}")),
                mapOf("input" to input))
            assertEquals(mapOf("caseId" to input["text"], "review" to 2.5, "accepted" to false), output["value"])
            val counter = node.copy(params = node.params + mapOf("outputKind" to "INTEGER", "source" to
                "(def counter (atom 0)) (swap! counter inc)"))
            repeat(2) { assertEquals(1L, runner.run(counter, emptyMap())["value"]) }
            val containers = node.copy(params = node.params + ("source" to
                "{\"items\" [0.1M 7 nil] \"empty\" {}}"))
            assertEquals(mapOf("items" to listOf(0.1, 7L, null), "empty" to emptyMap<String, Any?>()),
                runner.run(containers, emptyMap())["value"])
            val routed = SubVmLegos.camel(host).run(LcncNode("clojure-route", SubVmLegos.CAMEL,
                mapOf("from" to "direct:clojure-test", "to" to "log:clojure-test")), mapOf("messages?" to output["value"]))
            assertEquals(output["value"], reify(routed["routed"] as String))
            assertSame(previous, Thread.currentThread().contextClassLoader)
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
        } finally { host.close() }
    }

    @Test fun clojureRejectsUntrustedSourceAndUnsupportedBudgetsBeforeSpawning() = runBlocking {
        val host = HypervisorVmHost()
        try {
            val runner = SubVmLegos.polyglot(host)
            val node = LcncNode("clojure-source", SubVmLegos.POLYGLOT,
                mapOf("language" to "clojure", "source" to "input"))
            for (limits in listOf(mapOf("trust" to "UNTRUSTED"), mapOf("statements" to 1L), mapOf("wallMillis" to 1L))) {
                assertFailsWith<IllegalArgumentException> { runner.run(node, limits) }
            }
            assertTrue(host.ids().isEmpty())
        } finally { host.close() }
    }

    @Test fun clojureAdmittedFuturesDrainOnBothReturnAndFailure() = runBlocking {
        val host = HypervisorVmHost()
        val marker = "trikeshed.clojure.drain.${java.util.UUID.randomUUID()}"
        try {
            val runner = SubVmLegos.polyglot(host)
            val future = "(future (Thread/sleep 50) (System/setProperty \"$marker\" \"drained\"))"
            for (result in listOf("true", "(throw (ex-info \"failed\" {}))")) {
                System.clearProperty(marker)
                val node = LcncNode("clojure-drain", SubVmLegos.POLYGLOT,
                    mapOf("language" to "clojure", "source" to "$future $result"))
                if (result == "true") assertEquals(true, runner.run(node, emptyMap())["value"])
                else assertFails { runner.run(node, emptyMap()) }
                assertEquals("drained", System.getProperty(marker))
                assertTrue(host.ids().none { host.get(it)?.isAlive == true })
            }
        } finally {
            System.clearProperty(marker)
            host.close()
        }
    }

    @Test fun clojureDiagnosticsAndLossyValuesRestoreTheLoaderAndCloseGuests() = runBlocking {
        val host = HypervisorVmHost()
        val previous = Thread.currentThread().contextClassLoader
        try {
            val runner = SubVmLegos.polyglot(host)
            suspend fun invoke(source: String) = runner.run(LcncNode("clojure-diagnostic", SubVmLegos.POLYGLOT,
                mapOf("language" to "clojure", "source" to source)), emptyMap())
            for (source in listOf("[", "missing", "(throw (ex-info \"operator failure\" {}))")) {
                val error = assertFails { invoke(source) }
                assertTrue(error.message.orEmpty().contains("clojure-diagnostic.clj"), error.toString())
                assertSame(previous, Thread.currentThread().contextClassLoader)
            }
            assertFails { invoke("(borg.trikeshed.vm.HypervisorVmHost.)") }
            for (source in listOf("(Object.)", "{1 \"number\" \"1\" \"text\"}", "{:case 1}",
                "(fn [x] x)", "##NaN", "1/3", "0.123456789012345678901M", "9223372036854775808N",
                "{\"nested\" (Object.)}")) {
                // Clojure freezes the result inside the invocation, before its loader closes.
                // A rejected crossing therefore follows the guest failure taxonomy.
                val error = assertFailsWith<GuestException>(source) { invoke(source) }
                assertEquals(GuestFailure.GUEST_ERROR, error.kind, source)
                assertTrue(error.message.orEmpty().contains("VM "), error.toString())
                assertSame(previous, Thread.currentThread().contextClassLoader)
                assertTrue(host.ids().none { host.get(it)?.isAlive == true }, source)
            }
            assertNull(invoke("nil")["value"])
            assertSame(previous, Thread.currentThread().contextClassLoader)
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
        } finally { host.close() }
    }

    @Test fun javaCompilesTypedFunctionsInAnInvocationOwnedLoaderAndComposesWithCamel() = runBlocking {
        val host = HypervisorVmHost()
        try {
            assertTrue(VmLanguages.require(host, VmLanguage.JAVA).available)
            val runner = SubVmLegos.polyglot(host)
            val input = mapOf("text" to "quote'\"\\\n雪", "fraction" to 1.25, "empty" to null)
            val node = LcncNode("java-source", SubVmLegos.POLYGLOT,
                mapOf("language" to "java", "source" to "return input;", "outputKind" to "OBJECT"))
            assertEquals(input, runner.run(node, mapOf("input" to input))["value"])
            val output = runner.run(node.copy(params = node.params + ("source" to """
                var data = (Map<?, ?>) input;
                return Map.of("caseId", data.get("text"), "review", ((Number) data.get("fraction")).doubleValue() * 2, "accepted", false);
            """.trimIndent())), mapOf("input" to input))
            assertEquals(mapOf("caseId" to input["text"], "review" to 2.5, "accepted" to false), output["value"])
            assertEquals("java", output["language"])
            val counter = node.copy(params = node.params + mapOf("outputKind" to "INTEGER", "source" to
                "class Counter { static int value = 0; } return ++Counter.value;"))
            repeat(2) { assertEquals(1L, runner.run(counter, emptyMap())["value"]) }
            val routed = SubVmLegos.camel(host).run(LcncNode("java-route", SubVmLegos.CAMEL,
                mapOf("from" to "direct:java-test", "to" to "log:java-test")), mapOf("messages?" to output["value"]))
            assertEquals(output["value"], reify(routed["routed"] as String))
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
        } finally { host.close() }
    }

    @Test fun javaRejectsUntrustedCodeAndUnsupportedBudgetsBeforeSpawning() = runBlocking {
        val host = HypervisorVmHost()
        try {
            val runner = SubVmLegos.polyglot(host)
            val node = LcncNode("java-source", SubVmLegos.POLYGLOT,
                mapOf("language" to "java", "source" to "return input;"))
            for (limits in listOf(mapOf("trust" to "UNTRUSTED"), mapOf("statements" to 1L), mapOf("wallMillis" to 1L))) {
                assertFailsWith<IllegalArgumentException> { runner.run(node, limits) }
            }
            assertTrue(host.ids().isEmpty())
        } finally { host.close() }
    }

    @Test fun javaCompilationDiagnosticsAndOpaqueResultsDoNotLeakGuests() = runBlocking {
        val host = HypervisorVmHost()
        try {
            val runner = SubVmLegos.polyglot(host)
            suspend fun invoke(source: String) = runner.run(LcncNode("java-diagnostic", SubVmLegos.POLYGLOT,
                mapOf("language" to "java", "source" to source)), emptyMap())
            val error = assertFails { invoke("int x = 1;\nreturn missing;") }
            assertTrue(error.message.orEmpty().contains("java-diagnostic.java:2:"), error.toString())
            assertFails { invoke("return new borg.trikeshed.vm.HypervisorVmHost();") }
            // A valid class may emit bytes even when another class fails compilation.
            assertFails { invoke("return input; } } class Broken { MissingType value; } class Extra { void f() {") }
            for (source in listOf("return new Object();", "return Map.of(1, \"number\", \"1\", \"text\");",
                "return Double.NaN;", "return Map.of(\"nested\", new Object());")) {
                assertFailsWith<IllegalArgumentException>(source) { invoke(source) }
            }
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
        } finally { host.close() }
    }

    @Test fun jvmInteropCannotBeInferredToBeAMemoizableLeaf() {
        val observed = mutableListOf<RootObservation>()
        InProcessIsolate("host-root", VmFacet.JVM, guestModule = "groovy", onRootReturn = { observed += it }).use { iso ->
            iso.eval("function hostValue(){ return Java.type('java.lang.System').nanoTime() }; hostValue()")
            assertTrue(observed.any { it.root == "hostValue" })
            assertTrue(observed.filter { it.root == "hostValue" }.none { it.selfContained })
        }
    }

    @Test fun groovyCompilesSourceCarriesTypedDataAndClosesItsGuest() = runBlocking {
        val host = HypervisorVmHost()
        try {
            assertTrue(VmLanguages.require(host, VmLanguage.GROOVY).available)
            val runner = SubVmLegos.polyglot(host)
            val input = mapOf("text" to "quote'\"\\\n雪", "fraction" to 1.25, "empty" to null)
            val node = LcncNode("groovy-source", SubVmLegos.POLYGLOT,
                mapOf("language" to "groovy", "source" to "return input", "outputKind" to "OBJECT"))
            assertEquals(input, runner.run(node, mapOf("input" to input))["value"])
            val out = runner.run(node.copy(params = node.params + ("source" to
                "return [caseId: input.text, review: input.fraction * 2, accepted: false]")), mapOf("input" to input))
            assertEquals(mapOf("caseId" to input["text"], "review" to 2.5, "accepted" to false), out["value"])
            assertEquals("groovy", out["language"])
            val containers = node.copy(params = node.params + ("source" to
                "return [items: [0.1G, 7G, null], empty: [:]]"))
            assertEquals(mapOf("items" to listOf(0.1, 7L, null), "empty" to emptyMap<String, Any?>()),
                runner.run(containers, emptyMap())["value"])
            // A script-defined class must not survive into the next invocation's loader.
            val counter = node.copy(params = node.params + mapOf("outputKind" to "INTEGER", "source" to
                "class Counter { static int value = 0 }; return ++Counter.value"))
            repeat(2) { assertEquals(1L, runner.run(counter, emptyMap())["value"]) }
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
            val routed = SubVmLegos.camel(host).run(LcncNode("route", SubVmLegos.CAMEL,
                mapOf("from" to "direct:groovy-test", "to" to "log:groovy-test")), mapOf("messages?" to out["value"]))
            assertEquals(out["value"], reify(routed["routed"] as String))
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
        } finally { host.close() }
    }

    @Test fun groovyRejectsUntrustedSourceAndStatementBudgetsBeforeSpawning() = runBlocking {
        val host = HypervisorVmHost()
        try {
            val runner = SubVmLegos.polyglot(host)
            val node = LcncNode("groovy-source", SubVmLegos.POLYGLOT,
                mapOf("language" to "groovy", "source" to "return input"))
            assertFailsWith<IllegalArgumentException> { runner.run(node, mapOf("trust" to "UNTRUSTED")) }
            assertFailsWith<IllegalArgumentException> { runner.run(node, mapOf("statements" to 1L)) }
            assertTrue(host.ids().isEmpty())
        } finally { host.close() }
    }

    @Test fun groovyCompilationAndValueFailuresStillCloseTheGuest() = runBlocking {
        val host = HypervisorVmHost()
        try {
            val runner = SubVmLegos.polyglot(host)
            suspend fun invoke(source: String) = runner.run(LcncNode("diagnostic", SubVmLegos.POLYGLOT,
                mapOf("language" to "groovy", "source" to source)), emptyMap())
            val error = assertFails { invoke("return [") }
            assertTrue(error.message.orEmpty().contains("diagnostic.groovy"), error.toString())
            for (source in listOf(
                "return [(1): 'number', '1': 'text']", "return new Object()", "return { input }", "return Double.NaN",
                "return [nested: new Object()]", "return 0.123456789012345678901G", "return 9223372036854775808G",
            )) {
                assertFailsWith<IllegalArgumentException>(source) { invoke(source) }
            }
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
        } finally { host.close() }
    }

    @Test fun groovyLoopLimitTerminatesAndRetainsCompilerDiagnostics() = runBlocking {
        val host = HypervisorVmHost()
        try {
            val error = assertFails {
                SubVmLegos.polyglot(host).run(LcncNode("loop", SubVmLegos.POLYGLOT,
                    mapOf("language" to "groovy", "source" to
                        "long stop = System.nanoTime() + 2_000_000_000L; while (System.nanoTime() < stop) {}; return null",
                        "wallMillis" to "25")), emptyMap())
            }
            assertTrue(error.message.orEmpty().contains("timed out"), error.toString())
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
        } finally { host.close() }
    }

    @Test fun pythonReceivesNativeNestedContainersAndComposesWithCamel() = runBlocking {
        val host = HypervisorVmHost()
        try {
            val input = mapOf("text" to "quote'\"\\\n雪", "values" to listOf(1.25, null, false),
                "nested" to mapOf("empty" to emptyList<Any>()))
            val node = LcncNode("python-containers", SubVmLegos.POLYGLOT, mapOf(
                "language" to "python", "inputKind" to "OBJECT", "outputKind" to "OBJECT", "source" to """
                assert isinstance(input, dict)
                assert isinstance(input["values"], list)
                assert isinstance(input["nested"], dict)
                assert input["values"][1] is None
                assert input["values"][2] is False
                input["values"][0] *= 2
                input["nested"]["empty"].append("review")
                return input
                """.trimIndent()))
            val expected = mapOf("text" to input["text"], "values" to listOf(2.5, null, false),
                "nested" to mapOf("empty" to listOf("review")))
            val output = SubVmLegos.polyglot(host).run(node, mapOf("input" to input))
            assertEquals(expected, output["value"])
            assertEquals(listOf(1.25, null, false), input["values"])
            val routed = SubVmLegos.camel(host).run(LcncNode("python-route", SubVmLegos.CAMEL,
                mapOf("from" to "direct:python-test", "to" to "log:python-test")), mapOf("messages?" to output["value"]))
            assertEquals(expected, reify(routed["routed"] as String))
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
        } finally { host.close() }
    }

    @Test fun pythonDecodesScalarAndArrayArgumentsAsDataNotSource() = runBlocking {
        val host = HypervisorVmHost()
        try {
            val runner = SubVmLegos.polyglot(host)
            val node = LcncNode("python-values", SubVmLegos.POLYGLOT,
                mapOf("language" to "python", "source" to "return input"))
            for (input in listOf(null, true, 42L, 1.25, "raise Exception('not source')", listOf("雪", null, false))) {
                assertEquals(input, runner.run(node, mapOf("input" to input))["value"])
            }
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
        } finally { host.close() }
    }

    @Test fun javascriptAndPythonCarryTypedInputThroughRealGuestCallsAndReleaseGuests() = runBlocking {
        val host = HypervisorVmHost()
        try {
            val runner = SubVmLegos.polyglot(host)
            val input = mapOf("text" to "quote'\"\\\n雪", "fraction" to 1.25, "empty" to null)
            for (language in listOf("js", "python")) {
                val out = runner.run(LcncNode("typed", SubVmLegos.POLYGLOT,
                    mapOf("language" to language, "source" to "return input", "inputKind" to "OBJECT", "outputKind" to "OBJECT")),
                    mapOf("input?" to input))
                assertEquals(input, out["value"])
                assertEquals(input, reify(out["text"] as String))
                assertEquals(language, out["language"])
                assertFalse(host.get(out["vmId"] as String)?.isAlive == true)
            }
        } finally { host.close() }
    }

    @Test fun outputFailureStillReleasesTheGuestAndExistingVmCannotBeBorrowed() = runBlocking {
        val host = HypervisorVmHost()
        try {
            val runner = SubVmLegos.polyglot(host)
            val node = LcncNode("typed", SubVmLegos.POLYGLOT,
                mapOf("language" to "js", "source" to "return 'wrong kind'", "outputKind" to "OBJECT"))
            assertFailsWith<IllegalArgumentException> { runner.run(node, emptyMap()) }
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
            assertFailsWith<IllegalArgumentException> { runner.run(node, mapOf("vmId" to "existing")) }
            for ((language, source) in listOf("js" to "return Array(10001).fill(1)", "python" to "return {1: 'number', '1': 'text'}")) {
                assertFailsWith<IllegalArgumentException> {
                    runner.run(LcncNode("lossy", SubVmLegos.POLYGLOT, mapOf("language" to language, "source" to source)), emptyMap())
                }
            }
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
            assertFailsWith<IllegalArgumentException> {
                runner.run(node.copy(params = node.params + ("input" to "nope")), emptyMap())
            }
            val capabilities = VmLanguages.capabilities(host)
            assertTrue(capabilities.view.first { it.language == VmLanguage.KOTLIN_COMMON }.available)
        } finally { host.close() }
    }

    @Test fun typedOutputReachesDeclaredCamelMessagesAndRoutedPorts() = runBlocking {
        val host = HypervisorVmHost()
        try {
            val output = SubVmLegos.polyglot(host).run(LcncNode("transform", SubVmLegos.POLYGLOT,
                mapOf("language" to "js", "source" to "return {review: input.case, accepted: false}")),
                mapOf("input" to mapOf("case" to "synthetic")))
            val routed = SubVmLegos.camel(host).run(LcncNode("route", SubVmLegos.CAMEL,
                mapOf("from" to "direct:polyglot-test", "to" to "log:polyglot-test", "module" to "")),
                mapOf("messages?" to output["value"]))
            assertEquals(true, routed["ok"])
            assertEquals(output["value"], reify(routed["routed"] as String))
        } finally { host.close() }
    }
}
