package borg.trikeshed.narsese

import kotlinx.coroutines.runBlocking
import borg.trikeshed.agent.modelmux.Airgap
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class BertAirgapAssetsTest {
    @TempDir lateinit var directory: Path

    @Test fun missingLocalWeightsFailWithoutAHttpClientOrDownload() = runBlocking(Airgap()) {
        assertFails { JvmBertEncoder.open(this, directory.toFile()) }
        assertTrue(directory.toFile().listFiles().orEmpty().isEmpty(), "Loading cannot install absent assets")
        assertTrue(Airgap.active())
    }
}
