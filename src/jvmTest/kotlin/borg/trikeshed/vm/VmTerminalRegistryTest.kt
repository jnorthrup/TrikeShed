package borg.trikeshed.vm

import borg.trikeshed.forge.server.VmWire
import borg.trikeshed.lcnc.media.LcncSignalLane
import borg.trikeshed.lib.view
import borg.trikeshed.pointcut.VmFacet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VmTerminalRegistryTest {
    @Test
    fun everyInProcessVmGetsAnIndependentVt220TerminalAndCausalEvalLineage() = runTest {
        val host = HypervisorVmHost()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val wire = VmWire(host, scope)
            val a = wire.route("POST", "/api/vm/spawn", "{\"id\":\"alpha\",\"facet\":\"js\"}", null)
            val b = wire.route("POST", "/api/vm/spawn", "{\"id\":\"beta\",\"facet\":\"python\"}", null)
            assertEquals(200, a?.status)
            assertEquals(200, b?.status)
            assertTrue(a?.body?.contains("/vm-terminal?id=alpha") == true)
            assertEquals(listOf("alpha", "beta"), host.terminals.ids())

            val evaluated = wire.route(
                "POST", "/api/vm/alpha/eval",
                "{\"source\":\"print('alpha-output'); 7\",\"name\":\"web\"}", null,
            )
            assertEquals(200, evaluated?.status)
            val alpha = host.terminals["alpha"]!!
            val beta = host.terminals["beta"]!!
            assertTrue(alpha.panel.terminal.plainText().contains("alpha-output"))
            assertTrue(!beta.panel.terminal.plainText().contains("alpha-output"))
            val signals = alpha.panel.signals().view.toList()
            val manual = signals.first { it.lane == LcncSignalLane.MANUAL }
            assertTrue(signals.any { it.lane == LcncSignalLane.CAUSAL && it.causeSignalId == manual.id })

            val page = wire.route("GET", "/vm-terminal?id=alpha", "", null)
            assertEquals(200, page?.status)
            assertTrue(page?.bytes?.decodeToString()?.contains("PROCESS TERMINALS") == true)
            val snapshot = wire.route("GET", "/api/vm/alpha/terminal", "", null)
            assertTrue(snapshot?.body?.contains("\"vmId\":\"alpha\"") == true)
        } finally {
            host.close(); scope.cancel()
        }
    }

    @Test
    fun processIsolateMultiplexesGuestStdoutWithoutCorruptingProtocol() {
        val host = HypervisorVmHost()
        try {
            val handle = host.spawn(VmSpec("fenced", VmFacet.GRAAL_PYTHON, VmTrust.UNTRUSTED))
            val terminal = host.terminals["fenced"]!!
            val manual = terminal.prepare("print('process-output') or 9")
            terminal.begin(manual)
            val result = handle.eval("print('process-output') or 9", "terminal")
            terminal.complete(result, manual)

            assertEquals(Teleported.Num(9), result)
            assertTrue(terminal.panel.terminal.plainText().contains("process-output"))
            assertTrue(handle.isAlive)
        } finally {
            host.close()
        }
    }

    @Test
    fun processTerminalStdinUnblocksAWaitingGuestWithoutStealingProtocolLines() = runTest {
        val host = HypervisorVmHost()
        try {
            val handle = host.spawn(VmSpec("interactive", VmFacet.GRAAL_PYTHON, VmTrust.UNTRUSTED))
            val terminal = host.terminals["interactive"]!!
            val evalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val pending = evalScope.async { handle.eval("input('name? ')", "terminal") }
            val prompted = withContext(Dispatchers.IO) {
                withTimeoutOrNull(10_000) {
                    while (!terminal.panel.terminal.plainText().contains("name?")) delay(10)
                    true
                }
            }
            assertEquals(true, prompted, "alive=${handle.isAlive}\n${terminal.panel.terminal.plainText()}")
            terminal.pushInput("Ada\n")
            val result = runCatching { withContext(Dispatchers.IO) { withTimeout(10_000) { pending.await() } } }
            assertEquals(Teleported.Str("Ada"), result.getOrNull(), "${result.exceptionOrNull()}\n${terminal.panel.terminal.plainText()}")
            assertTrue(handle.isAlive)
            evalScope.cancel()
        } finally {
            host.close()
        }
    }
}
