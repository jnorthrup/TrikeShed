package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.*
import kotlinx.coroutines.*
import kotlin.test.*

class SubVmDebugCapabilityTest {
    @Test fun resolvedInputsAndHostRestrictionsDetermineSourceSupportWithoutExecution() = runBlocking {
        HypervisorVmHost(languages = setOf(VmFacet.GRAAL_JS)).use { host ->
            val runner = SubVmLegos.polyglot(host)
            val node = LcncNode("guest", SubVmLegos.POLYGLOT, VmLanguage.JAVA.params("return input"))
            assertTrue(runner.sourceDebugging(node, mapOf("language" to "js")))
            assertTrue(runner.sourceDebugging(node, mapOf("language?" to "javascript")))
            assertFalse(runner.sourceDebugging(node, mapOf("language" to "js", "trust?" to "untrusted")))
            assertFalse(runner.sourceDebugging(node, mapOf("language" to "python")))
            for (invalid in listOf("missing-language", "", 42, listOf("js")))
                assertFalse(runner.sourceDebugging(node, mapOf("language" to invalid)))
            assertFalse(runner.sourceDebugging(node, mapOf("language" to "js", "trust" to "unknown")))
            assertFalse(host.sourceDebugging(VmSpec("world", VmFacet.GRAAL_JS, world = listOf("/unused"))))
            assertTrue(host.ids().isEmpty())
        }
    }

    @Test fun jvmCompilerLanesAndUntrustedGuestsOfferOnlyNodeStepping() = runBlocking {
        HypervisorVmHost().use { host ->
            for (language in VmLanguage.entries) {
                if (language.facet == VmFacet.JVM)
                    pause(SubVmLegos.polyglot(host), language.params("return input"), false)
                else pause(SubVmLegos.polyglot(host), language.params("return input") + ("trust" to "UNTRUSTED"), false)
                assertTrue(host.ids().isEmpty(), "Observing debug support must not spawn a guest")
            }
        }
    }

    @Test fun pauseUsesTheCallersHostRatherThanTheCapturedDefault() = runBlocking {
        HypervisorVmHost().use { host ->
            withContext(VmHostKey(VmHost.NONE)) {
                pause(SubVmLegos.polyglot(host), VmLanguage.JAVASCRIPT.params("return input"), false)
            }
            withContext(VmHostKey(host)) {
                for (language in listOf(VmLanguage.JAVASCRIPT, VmLanguage.PYTHON, VmLanguage.RUBY))
                    pause(SubVmLegos.polyglot(VmHost.NONE), language.params("return input"), true)
            }
            assertTrue(host.ids().isEmpty(), "Capability inspection must not evaluate or spawn")
        }
    }

    private suspend fun pause(runner: LcncNodeRunner, params: Map<String, String>, source: Boolean) = coroutineScope {
        val debug = LcncDebug()
        val execution = async(debug) { LcncRunner(mapOf(SubVmLegos.POLYGLOT to runner)).runProcedure(
            LcncProgram("capability", s_[LcncNode("guest", SubVmLegos.POLYGLOT, params)], emptySeriesOf())) }
        try {
            val snapshot = withTimeout(5000) {
                var state = debug.snapshot()
                while (state["state"] != "paused") { yield(); state = debug.snapshot() }
                state
            }
            assertEquals(if (source) listOf("step", "continue", "source-into") else listOf("step", "continue"),
                snapshot["actions"], params.toString())
            if (!source) {
                assertFalse(debug.command(snapshot["ordinal"] as Int, LcncDebug.Command.SOURCE_INTO))
                assertEquals(snapshot, debug.snapshot(), "Rejected source control must preserve the node pause")
            }
        } finally { execution.cancelAndJoin(); debug.close() }
    }
}
