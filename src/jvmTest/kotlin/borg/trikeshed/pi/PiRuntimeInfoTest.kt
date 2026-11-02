package borg.trikeshed.pi

import borg.trikeshed.graal.subvm.Budget
import borg.trikeshed.graal.subvm.ProcessIsolate
import borg.trikeshed.lib.*
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.Teleported
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Production diagnostic authored through Pi's source tools; no provider or tool execution. */
class PiRuntimeInfoTest {
    @Test fun productionDiagnosticReportsActualAgentInitialization(): Unit {
        val source = requireNotNull(javaClass.getResourceAsStream("/pi-core/pi-core-model.js"))
            .bufferedReader().use { it.readText() }
        val hash = MessageDigest.getInstance("SHA-256").digest(source.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(PiModelPort.BUNDLE_SHA256, hash)
        ProcessIsolate("pi-runtime-diagnostic", VmFacet.GRAAL_JS,
            Budget(statements = 0, wallMillis = 5_000), replyTimeoutMillis = 15_000).use { guest ->
            assertTrue(guest.restrictedJsLeaseCapable)
            var clockCalls = 0
            guest.delegate("pi_clock", 1_000) { args ->
                require(args.isEmpty())
                clockCalls++
                Teleported.Real(System.nanoTime().toDouble() / 1_000_000.0)
            }
            guest.eval(source, "pi-core-model.js")
            fun info(): Map<String, Teleported> {
                val json = guest.call("piRuntimeInfo") as Teleported.Str
                return (Teleported.parseCanonical(json.v) as Teleported.Obj).v
            }
            val before = info()
            assertEquals(Teleported.Str("GraalJS"), before["runtime"])
            assertEquals(Teleported.Str("ModelMux"), before["transport"])
            assertEquals(Teleported.Bool(false), before["initialized"])
            assertEquals(Teleported.parseCanonical("null"), before["model"])
            assertEquals(Teleported.parseCanonical("[]"), before["tools"])
            assertEquals(Teleported.Bool(true), before["buffered"])
            val definitions = """[{"name":"read","description":"Inert diagnostic schema","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}}]"""
            assertEquals(Teleported.Bool(true), guest.call("piInit", Teleported.Str("diagnostic"), Teleported.Str(definitions)))
            val after = info()
            assertEquals(Teleported.Bool(true), after["initialized"])
            assertEquals(Teleported.Str("diagnostic"), after["model"])
            assertEquals(Teleported.parseCanonical("[\"read\"]"), after["tools"])
            assertEquals(before["runtime"], after["runtime"])
            assertEquals(before["transport"], after["transport"])
            assertEquals(before["buffered"], after["buffered"])
            assertEquals(0, clockCalls)
            assertEquals(Teleported.Bool(true), guest.eval("typeof process === 'undefined' && typeof require === 'undefined' && typeof fetch === 'undefined'"))
        }
    }
}
