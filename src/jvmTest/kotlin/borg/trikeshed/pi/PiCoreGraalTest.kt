package borg.trikeshed.pi

import borg.trikeshed.graal.subvm.Budget
import borg.trikeshed.graal.subvm.ProcessIsolate
import borg.trikeshed.lib.*
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.Teleported
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.io.IOAccess
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real pinned Pi core, fixture transport only: no provider credentials or Node runtime. */
class PiCoreGraalTest {
    private val bundle: String get() = requireNotNull(javaClass.getResourceAsStream("/pi-core/pi-core-fixture.js"))
        .bufferedReader().use { it.readText() }

    // Test doubles for browser globals absent from plain Graal JS. These deliberately do not
    // claim production AbortSignal semantics or a monotonic clock. Neither crosses to the host.
    private val fixtureGlobals = """
        globalThis.AbortController = class {
            constructor() { this.signal = {aborted: false}; }
            abort() { this.signal.aborted = true; }
        };
        globalThis.performance = {now: () => Date.now()};
        globalThis.structuredClone = value => JSON.parse(JSON.stringify(value));
    """.trimIndent()

    @Test fun pinnedUpstreamAgentExecutesATurnAndAValidatedToolLoopInGraalJs() {
        val source = bundle
        val hash = MessageDigest.getInstance("SHA-256").digest(source.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("a2b48cc09eac0be07cc19a883a7ed5fa266b7c9146e40be72d8563d89006e49a", hash)
        Context.newBuilder("js")
            .allowHostAccess(HostAccess.NONE).allowHostClassLookup { false }
            .allowIO(IOAccess.NONE).allowCreateThread(false).allowCreateProcess(false).allowNativeAccess(false)
            .resourceLimits(ResourceLimits.newBuilder().statementLimit(5_000_000, null).build())
            .build().use { context ->
                assertTrue(context.eval("js", "typeof process === 'undefined' && typeof require === 'undefined' && typeof fetch === 'undefined'").asBoolean())
                assertTrue(context.eval("js", "(() => {try {Java.type('java.lang.System'); return false;} catch (_) {return true;}})()").asBoolean())
                context.eval("js", fixtureGlobals)
                context.eval("js", source)
                for (tool in _l[false, true]) {
                    assertEquals("admitted", context.eval("js", "piFixtureStart($tool)").asString())
                    assertTrue(context.eval("js", "typeof piFixtureResult === 'string'").asBoolean(), "Pi Promise did not complete")
                    val result = Teleported.parseCanonical(context.eval("js", "piFixtureResult").asString()) as Teleported.Obj
                    assertEquals(Teleported.Num(if (tool) 2L else 1L), result.v["calls"])
                    assertEquals(Teleported.Num(if (tool) 1L else 0L), result.v["recalls"])
                    val messages = (result.v.getValue("messages") as Teleported.Arr).v
                    val content = ((messages.last() as Teleported.Obj).v.getValue("content") as Teleported.Arr).v
                    assertEquals(Teleported.Str("fixture reply"), (content.single() as Teleported.Obj).v["text"])
                    if (tool) assertTrue((result.v.getValue("events") as Teleported.Arr).v.contains(Teleported.Str("tool_execution_end")))
                }
            }
    }

    @Test fun realPiCoreExecutesInRestrictedJsChildWithoutHostDelegates() {
        ProcessIsolate("pi-core-fixture", VmFacet.GRAAL_JS,
            Budget(statements = 0, wallMillis = 5_000), replyTimeoutMillis = 15_000).use { guest ->
            assertTrue(guest.restrictedJsLeaseCapable)
            assertEquals(Teleported.Bool(true), guest.eval("typeof process === 'undefined' && typeof require === 'undefined' && typeof fetch === 'undefined'"))
            guest.eval(fixtureGlobals)
            guest.eval(bundle, "pinned-pi-core-fixture.js")
            assertEquals(Teleported.Str("admitted"), guest.call("piFixtureStart", Teleported.Bool(true)))
            val result = guest.eval("JSON.parse(piFixtureResult).calls === 2 && JSON.parse(piFixtureResult).recalls === 1 && JSON.parse(piFixtureResult).messages.at(-1).content[0].text === 'fixture reply'")
            assertEquals(Teleported.Bool(true), result)
        }
    }
}
