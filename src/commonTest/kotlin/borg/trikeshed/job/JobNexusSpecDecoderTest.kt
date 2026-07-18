package borg.trikeshed.job

import borg.trikeshed.parse.confix.Syntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * E0/E1 — JobNexusSpecDecoder contract tests.
 *
 * Spec (§C14 one entrypoint lowering path; §E0 RED):
 *  - JSON, YAML, and CBOR inputs produce facet-equal specs with identical
 *    fully-resolved defaults
 *  - Numeric fields decode correctly even though Confix reifies JSON numbers
 *    as Double (decoder must coerce Double → Int / Long, not via toString)
 *  - Missing fields fall back to defaults
 *  - Distinct inputs produce distinct specs
 *  - Canonical bytes round-trip: encode → decode → canonicalBytes match
 */
class JobNexusSpecDecoderTest {

    private val canonicalJson = """
        {
          "channels": {"commands": 256, "committed": 128, "facts": 512, "activations": 64, "telemetry": 32},
          "storage": {"backend": "file", "durability": "fsync", "checkpointEvery": 4096},
          "supervision": {"drainTimeoutMs": 30000},
          "rete": {"cycleBudget": 10000}
        }
    """.trimIndent()

    @Test
    fun decodesJsonSpecWithAllFields() {
        val spec = JobNexusSpecDecoder.decode(Syntax.JSON, canonicalJson.encodeToByteArray())
        assertEquals(256, spec.channels.commands)
        assertEquals(128, spec.channels.committed)
        assertEquals(512, spec.channels.facts)
        assertEquals(64, spec.channels.activations)
        assertEquals(32, spec.channels.telemetry)
        assertEquals(StorageBackend.File, spec.storage.backend)
        assertEquals(Durability.Fsync, spec.storage.durability)
        assertEquals(4096, spec.storage.checkpointEvery)
        assertEquals(30_000L, spec.supervision.drainTimeoutMs)
        assertEquals(10_000, spec.rete.cycleBudget)
    }

    @Test
    fun missingNumericFieldsFallBackToDefaults() {
        val json = """{}""".trimIndent()
        val spec = JobNexusSpecDecoder.decode(Syntax.JSON, json.encodeToByteArray())
        // Defaults from ChannelSpec / StorageSpec / SupervisionSpec / ReteSpec
        assertEquals(64, spec.channels.commands)
        assertEquals(64, spec.channels.committed)
        assertEquals(128, spec.channels.facts)
        assertEquals(64, spec.channels.activations)
        assertEquals(32, spec.channels.telemetry)
        assertEquals(StorageBackend.Memory, spec.storage.backend)
        assertEquals(Durability.None, spec.storage.durability)
        assertEquals(0, spec.storage.checkpointEvery)
        assertEquals(5_000L, spec.supervision.drainTimeoutMs)
        assertEquals(1_000, spec.rete.cycleBudget)
    }

    @Test
    fun decodesYamlSpecWithSameValues() {
        val yaml = canonicalJson.replace(":", ": ")
        val jsonSpec = JobNexusSpecDecoder.decode(Syntax.JSON, canonicalJson.encodeToByteArray())
        val yamlSpec = JobNexusSpecDecoder.decode(Syntax.YAML, yaml.encodeToByteArray())
        assertEquals(jsonSpec, yamlSpec,
            "JSON and YAML inputs with same logical values must decode to equal specs")
    }

    @Test
    fun decodesCborSpecWithSameValues() {
        val jsonSpec = JobNexusSpecDecoder.decode(Syntax.JSON, canonicalJson.encodeToByteArray())
        val cborSpec = JobNexusSpecDecoder.decode(Syntax.CBOR, canonicalJson.encodeToByteArray())
        assertEquals(jsonSpec, cborSpec,
            "JSON and CBOR inputs with same logical values must decode to equal specs")
    }

    @Test
    fun distinctInputsProduceDistinctSpecs() {
        val a = JobNexusSpecDecoder.decode(Syntax.JSON, canonicalJson.encodeToByteArray())
        val bJson = """
            {
              "channels": {"commands": 512, "committed": 128, "facts": 512, "activations": 64, "telemetry": 32},
              "storage": {"backend": "file", "durability": "fsync", "checkpointEvery": 4096},
              "supervision": {"drainTimeoutMs": 30000},
              "rete": {"cycleBudget": 10000}
            }
        """.trimIndent()
        val b = JobNexusSpecDecoder.decode(Syntax.JSON, bJson.encodeToByteArray())
        assertNotEquals(a, b)
        assertTrue(!a.canonicalBytes.contentEquals(b.canonicalBytes),
            "different decoded specs must produce different canonical bytes")
    }

    @Test
    fun roundTripCanonicalBytesMatch() {
        val spec = JobNexusSpecDecoder.decode(Syntax.JSON, canonicalJson.encodeToByteArray())
        val canonical = spec.canonicalBytes
        val reDecoded = JobNexusSpecDecoder.decode(Syntax.JSON, canonical)
        assertEquals(spec, reDecoded, "encode → decode must round-trip to equal spec")
        assertTrue(canonical.contentEquals(reDecoded.canonicalBytes),
            "round-trip canonical bytes must be identical")
    }

    @Test
    fun decodesBackendStringVariants() {
        val cases = mapOf(
            "memory" to StorageBackend.Memory,
            "file" to StorageBackend.File,
            "linuxbtrfs" to StorageBackend.LinuxBtrfs,
            "btrfs" to StorageBackend.LinuxBtrfs,
            "linux_btrfs" to StorageBackend.LinuxBtrfs,
            "garbage" to StorageBackend.Memory, // unknown → default
        )
        for ((input, expected) in cases) {
            val json = """{"storage":{"backend":"$input"}}"""
            val spec = JobNexusSpecDecoder.decode(Syntax.JSON, json.encodeToByteArray())
            assertEquals(expected, spec.storage.backend,
                "backend string '$input' must map to $expected")
        }
    }

    @Test
    fun decodesDurabilityStringVariants() {
        val cases = mapOf(
            "none" to Durability.None,
            "fsync" to Durability.Fsync,
            "anything-else" to Durability.None,
        )
        for ((input, expected) in cases) {
            val json = """{"storage":{"durability":"$input"}}"""
            val spec = JobNexusSpecDecoder.decode(Syntax.JSON, json.encodeToByteArray())
            assertEquals(expected, spec.storage.durability,
                "durability string '$input' must map to $expected")
        }
    }
}