package borg.trikeshed.job

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * E0 — JobNexusSpec contract tests.
 *
 * Spec (§E0 RED):
 *  - DSL, fluent builder, equivalent JSON/YAML/CBOR inputs produce facet-equal specs
 *  - Defaults are identical across all entry shapes
 *  - canonicalBytes are deterministic and reflect every resolved value
 *  - Mutation of channel/storage/rete facets produces distinct canonical bytes
 */
class JobNexusSpecTest {

    @Test
    fun defaultSpecHasCanonicalBytes() {
        val spec = JobNexusSpec()
        val bytes = spec.canonicalBytes
        assertTrue(bytes.isNotEmpty(), "default spec must produce canonical bytes")
    }

    @Test
    fun defaultSpecCanonicalOrderIsStable() {
        // The canonical byte sequence for the default spec is anchored here.
        // Any change to field ordering, key casing, or whitespace is a breaking
        // change and must be caught by this test before any downstream consumer
        // (decoders, factories, replay tools) caches the wrong bytes.
        val expected = (
            "{" +
                "\"channels\":{" +
                    "\"commands\":64,\"committed\":64,\"facts\":128,\"activations\":64,\"telemetry\":32" +
                "}," +
                "\"storage\":{" +
                    "\"backend\":\"memory\",\"durability\":\"none\",\"checkpointEvery\":0" +
                "}," +
                "\"supervision\":{\"drainTimeoutMs\":5000}," +
                "\"rete\":{\"cycleBudget\":1000}" +
            "}"
        ).encodeToByteArray()
        val actual = JobNexusSpec().canonicalBytes
        assertTrue(expected.contentEquals(actual),
            "default canonical bytes drifted from anchor: got=${actual.decodeToString()}")
    }

    @Test
    fun defaultSpecIsDeterministicAcrossInvocations() {
        val a = JobNexusSpec().canonicalBytes
        val b = JobNexusSpec().canonicalBytes
        assertTrue(a.contentEquals(b), "same default spec must produce identical bytes")
    }

    @Test
    fun dslAndBuilderProduceEqualSpecs() {
        val dsl = jobNexusSpec {
            channels {
                commands(256)
                committed(128)
                facts(512)
                activations(64)
                telemetry(32)
            }
            storage {
                backend(StorageBackend.File)
                durability(Durability.Fsync)
                checkpointEvery(4096)
            }
            supervision { drainTimeoutMs(30_000) }
            rete { cycleBudget(10_000) }
        }
        val built = JobNexusSpec.builder().apply {
            channels {
                commands(256); committed(128); facts(512); activations(64); telemetry(32)
            }
            storage {
                backend(StorageBackend.File); durability(Durability.Fsync); checkpointEvery(4096)
            }
            supervision { drainTimeoutMs(30_000) }
            rete { cycleBudget(10_000) }
        }.build()

        assertEquals(dsl, built, "DSL and fluent builder must produce equal specs")
        assertTrue(dsl.canonicalBytes.contentEquals(built.canonicalBytes),
            "DSL and fluent builder must produce byte-identical canonical specs")
    }

    @Test
    fun distinctChannelCapacitiesProduceDistinctBytes() {
        val a = JobNexusSpec(channels = ChannelSpec(commands = 64, committed = 64, facts = 128, activations = 64, telemetry = 32))
        val b = JobNexusSpec(channels = ChannelSpec(commands = 256, committed = 64, facts = 128, activations = 64, telemetry = 32))
        assertTrue(!a.canonicalBytes.contentEquals(b.canonicalBytes),
            "changing commands capacity must change canonical bytes")
    }

    @Test
    fun distinctStorageBackendsProduceDistinctBytes() {
        val mem = JobNexusSpec(storage = StorageSpec(backend = StorageBackend.Memory))
        val file = JobNexusSpec(storage = StorageSpec(backend = StorageBackend.File))
        assertNotEquals(ContentId.of(mem.canonicalBytes), ContentId.of(file.canonicalBytes),
            "backend change must yield different CIDs")
    }

    @Test
    fun builderMutationsAfterBuildDoNotAffectSpec() {
        val channelBuilder = ChannelBuilder().apply { commands(512) }
        val spec = JobNexusSpec(channels = ChannelSpec(
            commands = channelBuilder.commands,
            committed = channelBuilder.committed,
            facts = channelBuilder.facts,
            activations = channelBuilder.activations,
            telemetry = channelBuilder.telemetry,
        ))
        val cidBefore = ContentId.of(spec.canonicalBytes)
        channelBuilder.commands(99_999) // mutate the auxiliary builder
        val cidAfter = ContentId.of(spec.canonicalBytes)
        assertEquals(cidBefore, cidAfter,
            "mutating the builder after constructing the spec must not change the spec's CID")
    }

    @Test
    fun defaultValuesArePredictable() {
        val spec = JobNexusSpec()
        assertEquals(64, spec.channels.commands)
        assertEquals(StorageBackend.Memory, spec.storage.backend)
        assertEquals(5_000L, spec.supervision.drainTimeoutMs)
        assertEquals(1_000, spec.rete.cycleBudget)
    }

    @Test
    fun reteCycleBudgetChangeIsReflectedInBytes() {
        val a = JobNexusSpec(rete = ReteSpec(cycleBudget = 1_000))
        val b = JobNexusSpec(rete = ReteSpec(cycleBudget = 10_000))
        assertTrue(!a.canonicalBytes.contentEquals(b.canonicalBytes))
    }
}