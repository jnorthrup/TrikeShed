package borg.trikeshed.agent

import borg.trikeshed.job.CasStore
import borg.trikeshed.lcnc.*
import kotlinx.coroutines.runBlocking
import borg.trikeshed.agent.modelmux.Airgap
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class AgentAirgapTest {
    @TempDir lateinit var directory: Path

    @Test fun admissionPrecedesScratchAndSubprocesses() = runBlocking(Airgap()) {
        var definitions = 0
        val runner = JvmAgentRunner(
            rosterRows = listOf(AgentCliInfo("fixture", "/not-an-agent", "fixture", true)),
            repoDir = directory.resolve("repo").toFile(),
            forgeHome = directory.resolve("forge").toFile(),
            cas = CasStore.inMemory(),
            definitions = { definitions++; error("agent definition reached") },
            toolDirs = emptyList(),
        )
        assertFailsWith<Airgap.Rejection> { runner.run(AgentRunRequest("fixture", "must stay local")) }
        assertEquals(0, definitions)
        assertTrue(directory.toFile().listFiles()!!.isEmpty())
    }
}
