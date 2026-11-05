package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import borg.trikeshed.job.PackCasStore
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
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

    @Test fun historicalReadRefusesDamagedRecordTailsWithoutRepairingTheEvidencePack() {
        val reference = "retained source".encodeToByteArray()
        val source = Files.write(directory.resolve("ref.txt"), reference)
        PackCasStore(JvmFileOperations(), directory.toString()).use { cas ->
            cas.put("complete blob".encodeToByteArray())
            cas.putRef(source.toString(), reference, ContentId.of(reference))
            cas.sync()
        }
        val pack = directory.resolve("pack")
        val complete = Files.readAllBytes(pack)
        val args = _a["--cas", directory.toString(), "--inspection", "sha256:" + "0".repeat(64)]
        // Complete BLOB + REF framing reaches the existing missing-receipt refusal without changing the pack.
        assertFailsWith<IllegalArgumentException> { SyntheticNalExperimentCli.report(args) }
        assertContentEquals(complete, Files.readAllBytes(pack))
        val unknownKind = complete.copyOf().also { it[0] = 127 }
        val negativeLength = complete.copyOf().also { bytes -> for (i in 1..4) bytes[i] = 0xff.toByte() }
        val excessiveLength = negativeLength.copyOf().also { it[1] = 0x7f }
        for ((label, damaged) in s_[
            "torn-header" j (complete + _a[127.toByte()]),
            "torn-ref-payload" j complete.copyOf(complete.size - 1),
            "unknown-kind" j unknownKind, "negative-length" j negativeLength, "excessive-length" j excessiveLength,
        ].view) {
            Files.write(pack, damaged)
            val refusal = assertFailsWith<IllegalArgumentException>(label) { SyntheticNalExperimentCli.report(args) }
            assertTrue(refusal.message.orEmpty().startsWith("Historical CAS pack"), label)
            assertContentEquals(damaged, Files.readAllBytes(pack), "Historical input was repaired: $label")
        }
        assertContentEquals(reference, Files.readAllBytes(source))
    }
}
