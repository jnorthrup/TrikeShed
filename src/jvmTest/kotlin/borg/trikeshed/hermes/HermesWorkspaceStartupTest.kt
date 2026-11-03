package borg.trikeshed.hermes

import borg.trikeshed.agent.keymux.*
import borg.trikeshed.agent.modelmux.*
import borg.trikeshed.btrfs.BtrfsWorldStore
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.*
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class HermesWorkspaceStartupTest {
    @Test fun realCliStartsWithItsSessionDatabaseInTheGuestWorkspace() = runBlocking<Unit>(Dispatchers.IO) {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = Files.createTempDirectory("hermes-workspace-")
        val world = BtrfsWorldStore.ofFiles(JvmFileOperations(), root.toString())
        val seed = world.mount("hermes-python")
        assertTrue(seed.createSubvolume("hermes-python"))
        assertTrue(seed.createDirectory("hermes-python", "workspace"))
        assertTrue(seed.createDirectory("hermes-python", "workspace/.hermes"))
        assertTrue(seed.writeFile("hermes-python", "workspace/.hermes/state.db", "".encodeToByteArray()))
        val mux = ModelMux(KeyMux { bind("llm.fixture.key", TestKeySource()) }) {
            model("fixture", caps = _s["chat", "tools"], baseUrl = "https://fixture.invalid/v1")
            defaultModel("fixture")
        }
        try {
            HermesVmConsole(Path.of("utils/hermes-trikeshed-graalpy"), Path.of("utils/graalpy-sleeve/hermes"),
                modelMuxProvider = { mux }, muxContext = BreadcrumbElement(), socketScope = owner, breadcrumbs = true,
                world = world).use { console ->
                val state = console.open()
                assertEquals(HermesVmConsole.State.READY, state, console.snapshotMap().toString())
                assertNotNull(console.snapshotMap()["breadcrumbContext"])

            }
            assertTrue(world.mount("hermes-python").listDirectory("hermes-python", "workspace/.hermes")!!.any { it.startsWith("state.db.zeroed-") })
        } finally {
            owner.cancel()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
