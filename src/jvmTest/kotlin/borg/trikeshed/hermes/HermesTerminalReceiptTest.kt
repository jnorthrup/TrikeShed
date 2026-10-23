package borg.trikeshed.hermes

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.*
import borg.trikeshed.lib.*
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.Teleported
import java.nio.file.Path
import kotlin.test.*

class HermesTerminalReceiptTest {
    @Test
    fun closeFailsAnUnfinishedTerminalInputOnceAndClearsItsIdentity() {
        val console = HermesVmConsole(Path.of("unused"), Path.of("unused"))
        val cause = console.workerInputStarted("unfinished terminal turn")
        console.close()
        console.close()
        val outcome = console.panel.signals().view.filter { it.causeSignalId == cause }
        assertEquals(1, outcome.size, "Closed input must have exactly one terminal outcome")
        assertEquals("failed", outcome[0].kind)
        assertFailsWith<IllegalStateException> {
            console.workerInputFinished(cause, mapOf("input_processed" to true))
        }
        val next = console.workerInputStarted("next worker turn")
        console.workerInputFinished(next, mapOf("input_processed" to true, "turn_attempted" to false))
        assertEquals("completed", console.panel.signals().view.last().kind)
        console.close()
    }

    @Test
    fun terminalInputsHaveOneHostIdentityAndOneValidatedOutcome() {
        val board = ConfixBlackboard.empty()
        HermesVmConsole(Path.of("unused"), Path.of("unused"), blackboard = board).use { console ->
            val cause = console.workerInputStarted("first terminal turn")
            assertFailsWith<IllegalStateException> { console.workerInputStarted("overlap") }
            val result = mapOf("input_processed" to true, "turn_attempted" to true,
                "turn" to mapOf("completed" to true, "failed" to false), "history_size" to 2,
                "model" to "fixture", "session_id" to "retained-session")
            assertFailsWith<IllegalStateException> { console.workerInputFinished("foreign", result) }
            console.workerInputFinished(cause, result)
            assertFailsWith<IllegalStateException> { console.workerInputFinished(cause, result) }
            assertEquals(result, console.snapshotMap()["cli"])
            assertEquals("fixture", console.snapshotMap()["model"])
            val signals = console.panel.signals().view.toList()
            assertEquals(listOf("command", "completed"), signals.map { it.kind })
            assertEquals(cause, signals.last().causeSignalId)
            assertTrue(signals.all { board.keys().contains("hermes/console/signal/${it.id}") })

            val slash = console.workerInputStarted("/help")
            console.workerInputFinished(slash, mapOf("input_processed" to true, "turn_attempted" to false))
            assertEquals("completed", console.panel.signals().view.last().kind)
        }
    }

    @Test
    fun missingFailedPartialAndInterruptedTurnsNeverPublishCompletion() {
        HermesVmConsole(Path.of("unused"), Path.of("unused")).use { console ->
            for (result in listOf(
                mapOf("input_processed" to false),
                mapOf("input_processed" to true, "input_error" to "terminal failure"),
                mapOf("input_processed" to true, "turn_attempted" to true),
                mapOf("input_processed" to true, "turn" to emptyMap<String, Any>()),
                mapOf("input_processed" to true, "turn" to mapOf("completed" to false)),
                mapOf("input_processed" to true, "turn" to mapOf("completed" to true, "failed" to true)),
                mapOf("input_processed" to true, "turn" to mapOf("completed" to true, "partial" to true)),
                mapOf("input_processed" to true, "turn" to mapOf("completed" to true, "interrupted" to true)),
                mapOf("input_processed" to true, "turn" to mapOf("completed" to true, "error" to "failure")),
            )) {
                val cause = console.workerInputStarted("attempt")
                console.workerInputFinished(cause, result)
                val outcome = console.panel.signals().view.single { it.causeSignalId == cause }
                assertEquals("failed", outcome.kind, result.toString())
            }
        }
    }

    @Test
    fun nativeBridgeReceiptsRawInputWithoutDuplicatingSubmittedCommands() {
        // Only CLI processing/status are fixtures. Exercise the production observer in a native worker;
        // the installed donor and rendered terminal are verified separately by the browser checkpoint.
        val events = mutableListOf<Pair<String, List<Teleported>>>()
        ProcessIsolate("terminal-receipts", VmFacet.GRAAL_PYTHON,
            budget = Budget(statements = 0, wallMillis = 0),
            pythonWorker = PythonWorker(pythonPath = listOf(Path.of("utils/graalpy-sleeve/bridges").toAbsolutePath().toString()))).use { guest ->
            guest.delegate("sleeve_cli_input") { args ->
                events.add("input" to args)
                Teleported.Str("host-cause-${events.size}")
            }
            guest.delegate("sleeve_cli_result") { args ->
                events.add("result" to args)
                Teleported.Null
            }
            assertEquals(Teleported.Bool(true), guest.eval("""
                import trikeshed_cli_worker as bridge
                from types import SimpleNamespace
                import threading
                bridge._host_call = host.call
                bridge._cli = SimpleNamespace(_last_turn_result=None)
                bridge.status = lambda: {'history_size': 2}
                def chat():
                    bridge._cli._last_turn_result = {'completed': True, 'failed': False}
                bridge._chat = chat
                bridge._process = lambda line: bridge._observe_chat()
                bridge._observe('typed')
                assert bridge._receipt.current is None
                pending = {'line': 'submitted', 'done': threading.Event(), 'error': None,
                           'turn_attempted': False, 'turn': None}
                bridge._pending.append(pending)
                bridge._observe('submitted')
                assert pending['done'].is_set()
                assert pending['turn_attempted'] and pending['turn']['completed']
                def failure(line):
                    raise ValueError('input processing failed')
                bridge._process = failure
                try:
                    bridge._observe('failure')
                except ValueError:
                    pass
                else:
                    raise AssertionError('processing failure swallowed')
                assert bridge._receipt.current is None
                True
            """.trimIndent()))
        }
        assertEquals(listOf("input", "result", "input", "result"), events.map { it.first })
        assertEquals(listOf(Teleported.Str("typed")), events[0].second)
        assertEquals(Teleported.Str("host-cause-1"), events[1].second[0])
        val success = borg.trikeshed.parse.reifyMap((events[1].second[1] as Teleported.Str).v)
        assertEquals(true, success["turn_attempted"])
        assertEquals(true, (success["turn"] as Map<*, *>)["completed"])
        val failure = borg.trikeshed.parse.reifyMap((events[3].second[1] as Teleported.Str).v)
        assertNull(failure["turn"], "A failed input cannot reuse the previous turn result")
        assertTrue((failure["input_error"] as String).contains("input processing failed"))
    }
}
