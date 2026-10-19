package borg.trikeshed.hermes

import borg.trikeshed.graal.subvm.InProcessIsolate
import borg.trikeshed.lib.*
import borg.trikeshed.lcnc.media.LcncSignalLane
import borg.trikeshed.lcnc.media.LcncUserSignal
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.Teleported
import java.nio.file.Path
import kotlin.test.*

class HermesConsoleEvalTest {
    @Test
    fun shadowedExecDoesNotDisableFollowingCommands() = shadow("exec")

    @Test
    fun shadowedCompileDoesNotDisableFollowingCommands() = shadow("compile")

    @Test
    fun shadowedGlobalsDoesNotDisableFollowingCommands() = shadow("globals")

    @Test
    fun shadowedImportDoesNotDisableFollowingCommands() = shadow("__import__")

    private fun shadow(name: String) = console { console ->
        output(console, "$name = 17", "")
        output(console, name, "17\n")
        output(console, "6 * 7", "42\n")
        val failure = evaluate(console, "$name('still a user binding')")
            .single { it.kind == "completed" || it.kind == "failed" }
        assertEquals("failed", failure.kind, "User code must retain ordinary Python name resolution")
        output(console, "del $name", "")
        output(console, "6 * 7", "42\n")
        assertEquals(HermesVmConsole.State.READY, console.state)
    }

    @Test
    fun printAndNoneDoNotProjectTheGuestNamespace() = console { console ->
        output(console, "print('console-marker')", "console-marker\n")
        output(console, "None", "")
        output(console, "pass", "")
        assertTrue(console.panel.terminal.plainText().contains("console-marker"))
    }

    @Test
    fun expressionsUsePythonDisplayAndStatementsRetainOneSharedNamespace() = console { console ->
        output(console, "values = []", "")
        output(console, "values.append(41)", "")
        output(console, "values[0] + 1", "42\n")
        output(console, "False; ''; []; {}", "False\n''\n[]\n{}\n")
        output(console, "{'__name__': 'explicit', '__builtins__': 7}",
            "{'__name__': 'explicit', '__builtins__': 7}\n")
        output(console, """
            def add(value):
                values.append(value)
                return len(values)
            add(42)
            for value in range(2):
                values.append(value)
            len(values)
        """.trimIndent(), "2\n4\n")
        output(console, "values", "[41, 42, 0, 1]\n")
        val module = evaluate(console, "__import__('sys')")
        assertEquals("completed", module.single { it.kind == "completed" || it.kind == "failed" }.kind)
        val text = module.filter { it.kind == "output" }.joinToString("") { it.payload }
        assertTrue(text.startsWith("<module 'sys'") && text.length < 200,
            "An explicitly requested module uses Python repr, not recursive member projection: ${text.take(200)}")
    }

    @Test
    fun syntaxAndRuntimeFailuresHaveOneOutcomeAndNeverReplaySideEffects() = console { console ->
        output(console, "attempts = []", "")
        val syntax = evaluate(console, "attempts.append('must-not-run')\nif :")
        assertEquals(listOf("failed"), syntax.filter { it.kind == "completed" || it.kind == "failed" }.map { it.kind })
        output(console, "attempts", "[]\n")
        output(console, """
            def fail_once():
                attempts.append('once')
                raise SyntaxError('runtime syntax refusal')
        """.trimIndent(), "")
        val runtime = evaluate(console, "fail_once()")
        val failure = runtime.single { it.kind == "completed" || it.kind == "failed" }
        assertEquals("failed", failure.kind)
        assertTrue(failure.payload.contains("runtime syntax refusal"), failure.payload)
        output(console, "attempts", "['once']\n")
        assertEquals(HermesVmConsole.State.READY, console.state)
    }

    @Test
    fun displayHookOutputAndFailuresUseTheCurrentCommandReceipt() = console { console ->
        output(console, """
            import sys
            def hook(value):
                if value is not None:
                    print('hook:' + repr(value))
            sys.displayhook = hook
        """.trimIndent(), "")
        output(console, "6 * 7", "hook:42\n")
        output(console, "None", "")
        output(console, """
            def hook(value):
                raise ValueError('display refused')
            sys.displayhook = hook
        """.trimIndent(), "")
        val signals = evaluate(console, "42")
        val failure = signals.single { it.kind == "completed" || it.kind == "failed" }
        assertEquals("failed", failure.kind)
        assertTrue(failure.payload.contains("display refused"), failure.payload)
        output(console, "sys.displayhook = sys.__displayhook__", "")
        output(console, "42", "42\n")
    }

    @Test
    fun rawVmEvaluationRetainsItsModuleResultContract() {
        InProcessIsolate("console-eval-contract", VmFacet.GRAAL_PYTHON).use { guest ->
            val module = assertIs<Teleported.Obj>(guest.eval("console_value = 42"))
            assertEquals(Teleported.Num(42), module.v["console_value"])
            assertEquals(Teleported.Num(42), guest.eval("console_value"))
        }
    }

    private fun console(check: (HermesVmConsole) -> Unit) {
        // Real no-native GraalPy and production terminal/receipts; no installed donor or live ModelMux.
        HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 100, rows = 24).use { console ->
            console.inventoryLoader = { port -> port.inventorySources(mapOf(
                "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                "hermes_cli.main" to ("hermes_cli/main.py" to ""),
            )) }
            assertEquals(HermesVmConsole.State.READY, console.open())
            check(console)
        }
    }

    private fun evaluate(console: HermesVmConsole, source: String): Iterable<LcncUserSignal> {
        val manual = console.prepareCommand(":eval $source")
        console.execute(manual, echo = false)
        val signals = console.panel.signals().filter { it.causeSignalId == manual.signal.id }.view
        assertTrue(signals.all { it.lane == LcncSignalLane.CAUSAL })
        assertEquals(1, signals.count { it.kind == "completed" || it.kind == "failed" })
        return signals
    }

    private fun output(console: HermesVmConsole, source: String, expected: String) {
        val signals = evaluate(console, source)
        assertEquals("completed", signals.single { it.kind == "completed" || it.kind == "failed" }.kind,
            signals.last().payload.take(240))
        val actual = signals.filter { it.kind != "completed" }.joinToString("") { it.payload }
            .replace("\r\n", "\n")
        assertTrue(actual == expected,
            "Expected ${expected.length} output characters, received ${actual.length}: ${actual.take(240)}")
    }
}
