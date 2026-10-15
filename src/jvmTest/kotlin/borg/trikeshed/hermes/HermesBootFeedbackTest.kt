package borg.trikeshed.hermes

import borg.trikeshed.lib.*
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlin.test.*

class HermesBootFeedbackTest {
    @Test
    fun interruptionAfterACaughtOptionalFailureNamesTheStillLoadingEntry() {
        HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 100, rows = 24).use { console ->
            console.inventoryLoader = { port ->
                port.inventorySources(mapOf(
                    "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                    "hermes_cli.main" to ("hermes_cli/main.py" to """
                        try:
                            import optional
                        except RuntimeError:
                            print('optional failure caught')
                        while True:
                            pass
                    """.trimIndent()),
                    "optional" to ("optional.py" to "raise RuntimeError('optional probe refused')"),
                ))
            }

            assertEquals(HermesVmConsole.State.FAILED, console.open())
            val boot = console.snapshotMap()["boot"] as Map<*, *>
            assertEquals("import", boot["phase"])
            assertEquals("failed", boot["status"])
            assertEquals("hermes_cli.main", boot["module"], "a swallowed failure must not hide the module interrupted by the watchdog")
            val failure = assertNotNull(boot["failure"] as? String)
            assertTrue(failure.contains("INTERRUPTED"), failure)
            val detail = assertNotNull(boot["detail"] as? String)
            assertTrue(detail.contains("${HermesPythonPort.IMPORT_WALL_MILLIS / 1000}s execution limit"), detail)
            assertEquals(false, boot["steadyState"])
            assertTrue(console.panel.signals().view.any { it.payload.contains("optional failure caught") },
                "the guest must have caught the optional failure before its active import exceeded the wall budget")
            assertFalse(console.events.replayCache.filterIsInstance<HermesVmConsole.Event.StateChanged>()
                .any { it.state == HermesVmConsole.State.READY })
        }
    }

    @Test
    fun nestedImportFailureRetainsItsModuleAndGuestTracebackBeforeConsoleReadiness() {
        HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 100, rows = 24).use { console ->
            console.inventoryLoader = { port ->
                port.inventorySources(mapOf(
                    "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                    "hermes_cli.main" to ("hermes_cli/main.py" to """
                        try:
                            import optional
                        except RuntimeError:
                            pass
                        import dependency
                    """.trimIndent()),
                    "optional" to ("optional.py" to "raise RuntimeError('optional probe refused')"),
                    "dependency" to ("dependency.py" to """
                        try:
                            import optional
                        except RuntimeError:
                            pass
                        def load():
                            raise RuntimeError('nested load refused')
                        load()
                    """.trimIndent()),
                ))
            }

            assertEquals(HermesVmConsole.State.FAILED, console.open())
            val boot = console.snapshotMap()["boot"] as Map<*, *>
            assertEquals("failed", boot["status"])
            assertEquals("import", boot["phase"])
            assertEquals(false, boot["steadyState"])
            assertEquals("dependency", boot["module"], "the entry's cascading failure must retain the nested cause")
            assertEquals("nested load refused", boot["failure"], "a caught optional import must not become the boot failure")
            val traceback = assertNotNull(boot["traceback"] as? String)
            assertTrue(traceback.contains("/workspace/dependency.py"), traceback)
            assertTrue(traceback.contains("RuntimeError: nested load refused"), traceback)
            val text = console.panel.terminal.plainText()
            assertTrue(text.contains("nested load refused"), text)
            assertTrue(text.contains("Python diagnostic console unavailable"), text)
            assertFalse(console.events.replayCache.filterIsInstance<HermesVmConsole.Event.StateChanged>()
                .any { it.state == HermesVmConsole.State.READY })
        }
    }

    @Test
    fun providerRegistrationFailureDoesNotReuseACaughtImportWithTheSameMessage() {
        HermesVmConsole(Path.of("unused"), Path.of("unused")).use { console ->
            console.inventoryLoader = { port ->
                port.inventorySources(mapOf(
                    "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                    "hermes_cli.main" to ("hermes_cli/main.py" to ""),
                    "providers" to ("providers/__init__.py" to """
                        def register_provider(profile):
                            try:
                                import optional
                            except RuntimeError:
                                pass
                            raise RuntimeError('registry refused')
                    """.trimIndent()),
                    "providers.base" to ("providers/base.py" to """
                        class ProviderProfile:
                            def __init__(self, **options):
                                self.__dict__.update(options)
                    """.trimIndent()),
                    "optional" to ("optional.py" to "raise RuntimeError('registry refused')"),
                ))
            }

            assertEquals(HermesVmConsole.State.FAILED, console.open())
            val boot = console.snapshotMap()["boot"] as Map<*, *>
            assertEquals("provider", boot["phase"])
            assertEquals("trikeshed_modelmux", boot["module"])
            assertTrue(assertNotNull(boot["failure"] as? String).contains("registry refused"))
            val traceback = assertNotNull(boot["traceback"] as? String)
            assertTrue(traceback.contains("register_provider"), traceback)
            assertTrue(traceback.contains("providers/__init__.py"), traceback)
            assertFalse(traceback.contains("optional.py"), "a caught import must not replace the registration traceback: $traceback")
        }
    }

    @Test
    fun retryClearsProviderFailureBeforeInventoryAndCompletesANewBoot() {
        HermesVmConsole(Path.of("unused"), Path.of("unused")).use { console ->
            console.inventoryLoader = { port ->
                port.inventorySources(mapOf(
                    "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                    "hermes_cli.main" to ("hermes_cli/main.py" to ""),
                    "providers" to ("providers/__init__.py" to "raise RuntimeError('registry refused')"),
                    "providers.base" to ("providers/base.py" to ""),
                ))
            }
            assertEquals(HermesVmConsole.State.FAILED, console.open())
            val failed = console.snapshotMap()["boot"] as Map<*, *>
            assertEquals("provider", failed["phase"])
            assertEquals(false, failed["steadyState"])
            assertEquals("registry refused", failed["failure"])
            assertNotNull(failed["traceback"])

            console.inventoryLoader = { port ->
                val snapshot = console.snapshotMap()
                val running = snapshot["boot"] as Map<*, *>
                assertEquals("booting", snapshot["state"])
                assertNull(snapshot["inventory"], "the failed attempt's inventory must not survive into a retry")
                assertEquals("inventory", running["phase"])
                assertEquals("running", running["status"])
                assertEquals(false, running["steadyState"])
                assertNull(running["module"])
                assertNull(running["failure"])
                assertNull(running["traceback"])
                port.inventorySources(mapOf(
                    "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                    "hermes_cli.main" to ("hermes_cli/main.py" to ""),
                ))
            }

            assertEquals(HermesVmConsole.State.READY, console.open())
            val ready = console.snapshotMap()["boot"] as Map<*, *>
            assertEquals("bootstrap", ready["phase"])
            assertEquals("ready", ready["status"])
            assertEquals(true, ready["steadyState"])
            assertNull(ready["failure"])
            assertNull(ready["traceback"])
            assertEquals("registry refused", failed["failure"], "published failure evidence is an immutable attempt snapshot")
        }
    }

    @Test
    fun oneShotRuntimeBindingMustCompleteBeforeConsoleReadiness() {
        HermesVmConsole(Path.of("unused"), Path.of("unused")).use { console ->
            console.inventoryLoader = { port ->
                port.inventorySources(mapOf(
                    "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                    "hermes_cli.main" to ("hermes_cli/main.py" to ""),
                    "hermes_cli.runtime_provider" to ("hermes_cli/runtime_provider.py" to ""),
                    "hermes_cli.oneshot" to ("hermes_cli/oneshot.py" to "def run_oneshot(*args, **options): return 0"),
                    "agent" to ("agent/__init__.py" to ""),
                    "agent.auxiliary_client" to ("agent/auxiliary_client.py" to ""),
                ))
            }

            assertEquals(HermesVmConsole.State.FAILED, console.open())
            val boot = console.snapshotMap()["boot"] as Map<*, *>
            assertEquals("bootstrap", boot["phase"])
            assertEquals("hermes_cli.oneshot", boot["module"], "completed imports must not replace the runtime evaluation anchor")
            assertEquals(false, boot["steadyState"])
            assertTrue(assertNotNull(boot["failure"] as? String).contains("resolve_runtime_provider"))
            assertTrue(assertNotNull(boot["traceback"] as? String).contains("bind_runtime"))
            assertFalse(console.events.replayCache.filterIsInstance<HermesVmConsole.Event.StateChanged>()
                .any { it.state == HermesVmConsole.State.READY })
        }
    }

    @Test
    fun progressEventsRetainTheirPhaseAndPublishReadyAfterProviderAndBootstrap() = runBlocking {
        HermesVmConsole(Path.of("unused"), Path.of("unused")).use { console ->
            console.inventoryLoader = { port ->
                port.inventorySources(mapOf(
                    "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                    "hermes_cli.main" to ("hermes_cli/main.py" to ""),
                    "providers" to ("providers/__init__.py" to """
                        registered = []
                        def register_provider(profile):
                            registered.append(profile)
                    """.trimIndent()),
                    "providers.base" to ("providers/base.py" to """
                        class ProviderProfile:
                            def __init__(self, **options):
                                self.__dict__.update(options)
                    """.trimIndent()),
                    "late_failure" to ("late_failure.py" to "raise RuntimeError('late import refused')"),
                ))
            }
            val states = mutableListOf<HermesVmConsole.Event.StateChanged>()
            val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                console.events.collect { if (it is HermesVmConsole.Event.StateChanged) states += it }
            }
            try {
                assertEquals(HermesVmConsole.State.READY, console.open())
                val running = states.filter { it.state == HermesVmConsole.State.BOOTING }
                assertEquals(
                    listOf(HermesVmConsole.BootPhase.INVENTORY, HermesVmConsole.BootPhase.IMPORT,
                        HermesVmConsole.BootPhase.PROVIDER, HermesVmConsole.BootPhase.BOOTSTRAP),
                    running.map { assertNotNull(it.boot).phase }.distinct(),
                )
                assertTrue(running.all { it.boot?.status == HermesVmConsole.State.BOOTING },
                    "later transitions must not mutate the boot snapshots already delivered to SSE")
                assertTrue(running.all { it.boot?.toMap()?.get("steadyState") == false })
                assertEquals(HermesVmConsole.State.READY, states.last().state)
                assertEquals(1, states.count { it.state == HermesVmConsole.State.READY })
                val ready = assertNotNull(states.last().boot)
                assertEquals(HermesVmConsole.BootPhase.BOOTSTRAP, ready.phase)
                assertEquals("Python diagnostic console ready", ready.detail)
                assertEquals(true, ready.toMap()["steadyState"])
                assertTrue(running.all { assertNotNull(it.boot).startedAtMs == ready.startedAtMs })

                val command = console.submit(":eval assert callable(__trikeshed_hermes) and __import__('providers').registered[0].name == 'trikeshed'")
                assertTrue(console.panel.signals().view.any { it.causeSignalId == command.id && it.kind == "completed" },
                    "ready must mean the provider registered and the console bootstrap exists in the same VM")

                val late = console.submit(":import late_failure")
                assertTrue(console.panel.signals().view.any { it.causeSignalId == late.id && it.kind == "failed" })
                assertEquals(HermesVmConsole.State.READY, console.state,
                    "import callbacks during a command must not reenter boot")
                assertEquals(ready, console.boot, "a command failure must not replace successful boot evidence")

                console.close()
                assertEquals(HermesVmConsole.State.CLOSED, states.last().state)
                assertEquals(HermesVmConsole.State.CLOSED, states.last().boot?.status)
                assertEquals("closed", (console.snapshotMap()["boot"] as Map<*, *>)["status"])
                assertEquals(HermesVmConsole.State.READY, ready.status,
                    "closing must not mutate the earlier ready event")
            } finally {
                observer.cancelAndJoin()
            }
        }
    }
}
