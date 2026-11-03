package borg.trikeshed.hermes

import borg.trikeshed.agent.keymux.*
import borg.trikeshed.agent.modelmux.*
import borg.trikeshed.btrfs.BtrfsWorldStore
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.vm.Teleported
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class HermesRealCliResetTest {
    @Test fun donorSessionResumesFromWorkspaceAndFreshResetRotatesIt() = runBlocking<Unit>(Dispatchers.IO) {
        val root = Files.createTempDirectory("hermes-cli-reset-")
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mux = ModelMux(KeyMux { bind("llm.fixture.key", TestKeySource()) }) {
            model("fixture", caps = _s["chat", "tools"], baseUrl = "https://fixture.invalid/v1")
            defaultModel("fixture")
        }
        try {
            HermesVmConsole(Path.of("utils/hermes-trikeshed-graalpy"), Path.of("utils/graalpy-sleeve/hermes"),
                modelMuxProvider = { mux }, socketScope = owner,
                world = BtrfsWorldStore.ofFiles(JvmFileOperations(), root.toString())).use { console ->
                lateinit var port: HermesPythonPort
                console.inventoryLoader = { candidate ->
                    port = candidate
                    candidate.inventory(Path.of("utils/hermes-trikeshed-graalpy"), Path.of("utils/graalpy-sleeve/hermes"))
                }
                fun cli(): Map<*, *> = assertIs<Map<*, *>>(console.snapshotMap()["cli"])
                assertEquals(HermesVmConsole.State.READY, console.open(), console.snapshotMap().toString())
                val first = assertIs<String>(cli()["session_id"])
                // Seed a retained transcript through the donor's real SQLite session store.
                assertEquals(Teleported.Bool(true), port.evalInVm("""
                    db = __hermes_cli._session_db
                    db.create_session(__hermes_cli.session_id, 'cli', model='fixture')
                    db.append_message(__hermes_cli.session_id, 'user', 'retained amber')
                    db.append_message(__hermes_cli.session_id, 'assistant', 'retained violet')
                    True
                """.trimIndent(), "hermes-reset-seed.py"))
                console.reset(resume = true)
                assertEquals(HermesVmConsole.State.READY, console.state, console.snapshotMap().toString())
                assertEquals(first, cli()["session_id"])
                assertEquals(2L, (cli()["history_size"] as Number).toLong())
                assertEquals(Teleported.Bool(true), port.evalInVm("""
                    assert [m['content'] for m in __hermes_cli.conversation_history] == ['retained amber', 'retained violet']
                    True
                """.trimIndent(), "hermes-reset-verify.py"))
                console.reset(resume = false)
                assertEquals(HermesVmConsole.State.READY, console.state, console.snapshotMap().toString())
                assertNotEquals(first, cli()["session_id"])
                assertEquals(0L, (cli()["history_size"] as Number).toLong())
            }
        } finally {
            owner.cancel()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
