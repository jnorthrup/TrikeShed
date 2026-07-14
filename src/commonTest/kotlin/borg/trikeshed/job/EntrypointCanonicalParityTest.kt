package borg.trikeshed.job

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C14 RED — All entrypoints produce byte-identical canonical specs.
 *
 * The plan: "DSL, fluent builder, JSON/YAML/CBOR decoder, direct factory,
 * scoped convenience, and migrated compatibility adapters yield the same
 * component creation/open trace for one spec."
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntrypointCanonicalParityTest {

    @Test
    fun jsonBuilderAndDslProduceSameCanonicalBytes() {
        val json = """
        {
          "channels": {"commands": 256, "committed": 256, "facts": 512, "activations": 256, "telemetry": 128},
          "storage": {"backend": "file", "durability": "fsync", "checkpointEvery": 4096},
          "supervision": {"drainTimeoutMs": 30000},
          "rete": {"cycleBudget": 10000}
        }
        """.trimIndent()

        val dslSpec = jobNexusSpec {
            channels { commands(256); committed(256); facts(512); activations(256); telemetry(128) }
            storage { backend(StorageBackend.File); durability(Durability.Fsync); checkpointEvery(4096) }
            supervision { drainTimeoutMs(30_000) }
            rete { cycleBudget(10_000) }
        }

        val builderSpec = JobNexusSpec.builder()
            .channels { commands(256); committed(256); facts(512); activations(256); telemetry(128) }
            .storage { backend(StorageBackend.File); durability(Durability.Fsync); checkpointEvery(4096) }
            .supervision { drainTimeoutMs(30_000) }
            .rete { cycleBudget(10_000) }
            .build()

        val jsonSpec = JobNexusSpecDecoder.decode(Syntax.Json, json.encodeToByteArray())

        assertEquals(dslSpec.canonicalBytes, builderSpec.canonicalBytes,
            "DSL and builder must produce byte-identical specs")
        assertEquals(dslSpec.canonicalBytes, jsonSpec.canonicalBytes,
            "DSL and JSON must produce byte-identical specs")
    }

    @Test
    fun yamlAndCborAlsoProduceSameCanonicalBytes() {
        val yaml = """
          channels:
            commands: 256
            committed: 256
            facts: 512
            activations: 256
            telemetry: 128
          storage:
            backend: file
            durability: fsync
            checkpointEvery: 4096
        """.trimIndent()

        val yamlSpec = JobNexusSpecDecoder.decode(Syntax.Yaml, yaml.encodeToByteArray())

        // CBOR round-trip: encode the spec to CBOR then decode it back
        val dslSpec = jobNexusSpec {
            channels { commands(256); committed(256); facts(512); activations(256); telemetry(128) }
            storage { backend(StorageBackend.File); durability(Durability.Fsync); checkpointEvery(4096) }
        }
        val cborBytes = CanonicalCbor.encode(dslSpec)
        val cborSpec = JobNexusSpecDecoder.decode(Syntax.Cbor, cborBytes)

        assertEquals(yamlSpec.canonicalBytes, dslSpec.canonicalBytes,
            "YAML must produce same canonical bytes as DSL")
        assertEquals(cborSpec.canonicalBytes, dslSpec.canonicalBytes,
            "CBOR must produce same canonical bytes as DSL")
    }

    @Test
    fun decodedSpecPassesSameValidatorAsDslSpec() {
        val json = """{"channels":{"commands":64}}""".encodeToByteArray()
        val jsonSpec = JobNexusSpecDecoder.decode(Syntax.Json, json)

        val dslSpec = jobNexusSpec {
            channels { commands(64) }
        }

        // Both specs must pass validation with identical results
        val jsonResult = JobNexusSpecValidator.validate(jsonSpec)
        val dslResult = JobNexusSpecValidator.validate(dslSpec)

        assertEquals(dslResult.valid, jsonResult.valid)
        assertEquals(dslResult.errors, jsonResult.errors)
    }

    @Test
    fun builderValidatorAndFactoryArePureUntilOpen() {
        val builder = JobNexusSpec.builder()
            .channels { commands(64) }
            .storage { backend(StorageBackend.Memory) }

        // Builder must not perform IO
        assertEquals(0, builder.ioCount, "builder must not perform IO before build()")
        assertEquals(0, builder.coroutineLaunchCount, "builder must not launch coroutines")
        assertEquals(0, builder.channelCreateCount, "builder must not create channels")
        assertEquals(0, builder.scopeCreateCount, "builder must not create scopes")
    }
}
