package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SyntheticNalExperimentCliTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    @Test fun retainedArgumentsNeverFallBackToTheSyntheticExperimentOrCreateMissingStores() {
        val missing = directory.resolve("missing").toString()
        val cid = "sha256:" + "0".repeat(64)
        for (args in s_[
            _a["--cas", missing], _a["--inspection", cid],
            _a["--cas", missing, "--cas", missing], _a["--inspection", cid, "--inspection", cid],
            _a["--cas", missing, "--input", "manual.json"], _a["--cas=$missing", "--inspection", cid],
            _a["--cas", missing, "--inspection", cid, "extra"],
            _a["--cas", missing, "--inspection", "sha256:invalid"],
            _a["--cas", "", "--inspection", cid], _a["--cas", "x".repeat(4097), "--inspection", cid],
        ].view) assertFailsWith<IllegalArgumentException> { SyntheticNalExperimentCli.report(args) }
        assertFalse(Files.exists(directory.resolve("missing")))
        assertFailsWith<IllegalArgumentException> {
            SyntheticNalExperimentCli.report(_a["--cas", directory.toString(), "--inspection", cid])
        }
        assertFalse(Files.exists(directory.resolve("pack")), "A missing pack must not be created by a historical read")
    }

    @Test fun missingInspectionRefusesAfterOpeningAnExistingPackWithoutSyntheticFallback() {
        val pack = Files.createFile(directory.resolve("pack"))
        val cid = "sha256:" + "0".repeat(64)
        assertFailsWith<IllegalArgumentException> {
            SyntheticNalExperimentCli.report(_a["--inspection", cid, "--cas", directory.toString()])
        }
        assertEquals(0L, Files.size(pack), "A missing inspection must not append an experiment artifact")
        Files.delete(pack)
    }
}
