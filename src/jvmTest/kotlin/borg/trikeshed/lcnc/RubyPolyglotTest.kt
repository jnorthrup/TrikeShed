package borg.trikeshed.lcnc

import borg.trikeshed.graal.subvm.*
import borg.trikeshed.parse.reify
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class RubyPolyglotTest {
    @Test fun nativeContainersComposeWithCamelWithoutMutatingInput() = runBlocking {
        HypervisorVmHost().use { host ->
            assertTrue(VmLanguages.require(host, VmLanguage.RUBY).available)
            val input = mapOf("text" to "quote'\"\\\n雪", "values" to listOf(1.25, null, false),
                "nested" to mapOf("empty" to emptyList<Any>()))
            val node = LcncNode("ruby-containers", SubVmLegos.POLYGLOT, mapOf(
                "language" to "ruby", "inputKind" to "OBJECT", "outputKind" to "OBJECT", "source" to """
                raise 'Hash required' unless input.is_a?(Hash) && input['nested'].is_a?(Hash)
                raise 'Array required' unless input['values'].is_a?(Array)
                raise 'String required' unless input['text'].is_a?(String)
                raise 'nil required' unless input['values'][1].nil?
                raise 'false required' unless input['values'][2] == false
                input['values'][0] *= 2
                input['nested']['empty'] << 'review'
                return input
                """.trimIndent()))
            val expected = mapOf("text" to input["text"], "values" to listOf(2.5, null, false),
                "nested" to mapOf("empty" to listOf("review")))
            val output = SubVmLegos.polyglot(host).run(node, mapOf("input" to input))
            assertEquals(expected, output["value"])
            assertEquals(listOf(1.25, null, false), input["values"])
            val routed = SubVmLegos.camel(host).run(LcncNode("ruby-route", SubVmLegos.CAMEL,
                mapOf("from" to "direct:ruby-test", "to" to "log:ruby-test")), mapOf("messages?" to output["value"]))
            assertEquals(expected, reify(routed["routed"] as String))
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
        }
    }

    @Test fun scalarAndArrayArgumentsAreDataNotSource() = runBlocking {
        HypervisorVmHost().use { host ->
            val runner = SubVmLegos.polyglot(host)
            val node = LcncNode("ruby-values", SubVmLegos.POLYGLOT,
                mapOf("language" to "ruby", "source" to "return input"))
            for (input in listOf(null, true, false, 42L, 1.25, "raise 'not source'", listOf("雪", null, false)))
                assertEquals(input, runner.run(node, mapOf("input" to input))["value"])
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
        }
    }

    @Test fun untrustedSourceUsesTheExistingProcessTierAndClosesIt() = runBlocking {
        HypervisorVmHost().use { host ->
            val output = SubVmLegos.polyglot(host).run(LcncNode("ruby-process", SubVmLegos.POLYGLOT,
                mapOf("language" to "ruby", "trust" to "UNTRUSTED", "source" to "{'score' => input['score'] * 2}")),
                mapOf("input" to mapOf("score" to 1.25)))
            assertEquals(mapOf("score" to 2.5), output["value"])
            val handle = assertNotNull(host.get(output["vmId"] as String))
            assertEquals("process", handle.tier)
            assertFalse(handle.isAlive)
        }
    }

    @Test fun diagnosticsAndLossyResultsReleaseTheirGuests() = runBlocking {
        HypervisorVmHost().use { host ->
            val runner = SubVmLegos.polyglot(host)
            suspend fun invoke(source: String) = runner.run(LcncNode("ruby-diagnostic", SubVmLegos.POLYGLOT,
                mapOf("language" to "ruby", "source" to source)), emptyMap())
            for (source in listOf("return [", "raise 'operator failure'"))
                assertEquals(GuestFailure.GUEST_ERROR, assertFailsWith<GuestException> { invoke(source) }.kind)
            for (source in listOf(":case", "{'case' => 1, :case => 2}", "{1 => 'number', '1' => 'text'}",
                "Object.new", "->(x) { x }", "Float::NAN", "{'nested' => Object.new}", "Array.new(10001, 1)")) {
                assertFailsWith<IllegalArgumentException>(source) { invoke(source) }
                assertTrue(host.ids().none { host.get(it)?.isAlive == true }, source)
            }
        }
    }

    @Test fun hostFileProcessAndNativeFacilitiesStayDenied() {
        InProcessIsolate("ruby-access", VmFacet.GRAAL_RUBY).use { iso ->
            for (source in listOf("File.read('/etc/hosts')", "Process.spawn('/usr/bin/true')",
                "Polyglot.eval('js', '1')", "require 'json'"))
                assertEquals(GuestFailure.GUEST_ERROR, assertFailsWith<GuestException>(source) { iso.eval(source) }.kind)
            assertEquals(Teleported.Num(42), iso.eval("6 * 7"))
        }
    }

    @Test fun statementLimitExcludesBootstrapButCancelsGuestLoop() {
        InProcessIsolate("ruby-limit", VmFacet.GRAAL_RUBY, Budget(statements = 5_000, wallMillis = 0)).use { iso ->
            assertEquals(Teleported.Num(42), iso.eval("6 * 7"))
            assertEquals(GuestFailure.EXHAUSTED,
                assertFailsWith<GuestException> { iso.eval("while true; end") }.kind)
            assertFalse(iso.isAlive)
        }
    }

    @Test fun wallInterruptLeavesTheContextReusable() {
        InProcessIsolate("ruby-interrupt", VmFacet.GRAAL_RUBY, Budget(statements = 1_000_000_000, wallMillis = 100)).use { iso ->
            assertEquals(GuestFailure.INTERRUPTED,
                assertFailsWith<GuestException> { iso.eval("while true; end") }.kind)
            assertTrue(iso.isAlive)
            assertEquals(Teleported.Num(42), iso.eval("6 * 7"))
        }
    }

    @Test fun methodRootsAreObservedWithoutClaimingBindingDelegation() {
        val roots = mutableListOf<String>()
        InProcessIsolate("ruby-roots", VmFacet.GRAAL_RUBY, onRootReturn = { roots += it.root }).use { iso ->
            assertTrue(roots.isEmpty(), "Engine bootstrap is not user work")
            iso.eval("def identity(input)\n input\nend", "ruby-root")
            repeat(10) { assertEquals(Teleported.Num(42), iso.call("identity", Teleported.Num(42))) }
            assertEquals(10, roots.count { it == "Object#identity" })
            assertFalse(iso.bounds.rootBindingsMutable)
        }
    }
}
